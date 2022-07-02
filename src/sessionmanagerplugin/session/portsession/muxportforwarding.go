// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License"). You may not
// use this file except in compliance with the License. A copy of the
// License is located at
//
// http://aws.amazon.com/apache2.0/
//
// or in the "license" file accompanying this file. This file is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
// either express or implied. See the License for the specific language governing
// permissions and limitations under the License.

// Package portsession starts port session.
package portsession

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"hash/fnv"
	"io"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/sessionutil"
	"github.com/aws/session-manager-plugin/src/version"
	"github.com/xtaci/smux"
	"golang.org/x/sync/errgroup"
)

// MuxClient contains smux client session and corresponding network connection
type MuxClient struct {
	conn    net.Conn
	session *smux.Session
}

// MgsConn contains local server and corresponding connection to smux client
type MgsConn struct {
	listener net.Listener
	conn     net.Conn
}

// MuxPortForwarding is type of port session
// accepts multiple client connections through multiplexing
type MuxPortForwarding struct {
	port           IPortSession
	sessionId      string
	socketFile     string
	portParameters PortParameters
	session        session.Session
	muxClient      *MuxClient
	mgsConn        *MgsConn
}

func (c *MgsConn) close() {
	c.listener.Close()
	c.conn.Close()
}

func (c *MuxClient) close() {
	c.session.Close()
	c.conn.Close()
}

// IsStreamNotSet checks if stream is not set
func (p *MuxPortForwarding) IsStreamNotSet() (status bool) {
	return p.muxClient.conn == nil
}

// Stop closes all open stream
func (p *MuxPortForwarding) Stop() {
	if p.mgsConn != nil {
		p.mgsConn.close()
	}
	if p.muxClient != nil {
		p.muxClient.close()
	}
	p.cleanUp()
	os.Exit(0)
}

// InitializeStreams initializes i/o streams
func (p *MuxPortForwarding) InitializeStreams(log log.T, agentVersion string) (err error) {

	p.handleControlSignals(log)
	p.socketFile = getUnixSocketPath(p.sessionId, os.TempDir(), "session_manager_plugin_mux.sock")

	if err = p.initialize(log, agentVersion); err != nil {
		p.cleanUp()
	}
	return
}

// ReadStream reads data from different connections
func (p *MuxPortForwarding) ReadStream(log log.T) (err error) {
	g, ctx := errgroup.WithContext(context.Background())

	// reads data from smux client and transfers to server over datachannel
	g.Go(func() error {
		return p.transferDataToServer(log, ctx)
	})

	// set up network listener on SSM port and handle client connections
	g.Go(func() error {
		return p.handleClientConnections(log, ctx)
	})

	return g.Wait()
}

// WriteStream writes data to stream
func (p *MuxPortForwarding) WriteStream(outputMessage message.ClientMessage) error {
	switch message.PayloadType(outputMessage.PayloadType) {
	case message.Output:
		_, err := p.mgsConn.conn.Write(outputMessage.Payload)
		return err
	case message.Flag:
		var flag message.PayloadTypeFlag
		buf := bytes.NewBuffer(outputMessage.Payload)
		binary.Read(buf, binary.BigEndian, &flag)

		if message.ConnectToPortError == flag {
			fmt.Printf("\nConnection to destination port failed, check SSM Agent logs.\n")
		}
	}
	return nil
}

// cleanUp deletes unix socket file
func (p *MuxPortForwarding) cleanUp() {
	os.Remove(p.socketFile)
}

// initialize opens a network connection that acts as smux client
func (p *MuxPortForwarding) initialize(log log.T, agentVersion string) (err error) {

	// open a network listener
	var listener net.Listener
	if listener, err = sessionutil.NewListener(log, p.socketFile); err != nil {
		return
	}

	var g errgroup.Group
	// start a go routine to accept connections on the network listener
	g.Go(func() error {
		if conn, err := listener.Accept(); err != nil {
			return err
		} else {
			p.mgsConn = &MgsConn{listener, conn}
		}
		return nil
	})

	// start a connection to the local network listener and set up client side of mux
	g.Go(func() error {
		if muxConn, err := net.Dial(listener.Addr().Network(), listener.Addr().String()); err != nil {
			return err
		} else {
			smuxConfig := smux.DefaultConfig()
			if version.DoesAgentSupportDisableSmuxKeepAlive(log, agentVersion) {
				// Disable smux KeepAlive or else it breaks Session Manager idle timeout.
				smuxConfig.KeepAliveDisabled = true
			}
			if muxSession, err := smux.Client(muxConn, smuxConfig); err != nil {
				return err
			} else {
				p.muxClient = &MuxClient{muxConn, muxSession}
			}
		}
		return nil
	})

	return g.Wait()
}

// handleControlSignals handles terminate signals
func (p *MuxPortForwarding) handleControlSignals(log log.T) {
	c := make(chan os.Signal, 1)
	signal.Notify(c, sessionutil.ControlSignals...)
	go func() {
		<-c
		fmt.Println("Terminate signal received, exiting.")

		if err := p.session.DataChannel.SendFlag(log, message.TerminateSession); err != nil {
			log.Errorf("Failed to send TerminateSession flag: %v", err)
		}
		fmt.Fprintf(os.Stdout, "\n\nExiting session with sessionId: %s.\n\n", p.sessionId)
		p.Stop()
	}()
}

// transferDataToServer reads from smux client connection and sends on data channel
func (p *MuxPortForwarding) transferDataToServer(log log.T, ctx context.Context) (err error) {
	msg := make([]byte, config.StreamDataPayloadSize)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			var numBytes int
			if numBytes, err = p.mgsConn.conn.Read(msg); err != nil {
				log.Debugf("Reading from port failed with error: %v.", err)
				return
			}

			log.Tracef("Received message of size %d from mux client.", numBytes)
			if err = p.session.DataChannel.SendInputDataMessage(log, message.Output, msg[:numBytes]); err != nil {
				log.Errorf("Failed to send packet on data channel: %v", err)
				return
			}
			// sleep to process more data
			time.Sleep(time.Millisecond)
		}
	}
}

// handleClientConnections sets up network server on local ssm port to accept connections from clients (browser/terminal)
func (p *MuxPortForwarding) handleClientConnections(log log.T, ctx context.Context) (err error) {
	var (
		listener   net.Listener
		displayMsg string
	)

	if p.portParameters.LocalConnectionType == "unix" {
		if listener, err = net.Listen(p.portParameters.LocalConnectionType, p.portParameters.LocalUnixSocket); err != nil {
			return err
		}
		displayMsg = fmt.Sprintf("Unix socket %s opened for sessionId %s.", p.portParameters.LocalUnixSocket, p.sessionId)
	} else {
		localPortNumber := p.portParameters.LocalPortNumber
		if p.portParameters.LocalPortNumber == "" {
			localPortNumber = "0"
		}
		if listener, err = net.Listen("tcp", "localhost:"+localPortNumber); err != nil {
			return err
		}
		p.portParameters.LocalPortNumber = strconv.Itoa(listener.Addr().(*net.TCPAddr).Port)
		displayMsg = fmt.Sprintf("Port %s opened for sessionId %s.", p.portParameters.LocalPortNumber, p.sessionId)
	}

	defer listener.Close()

	log.Infof(displayMsg)
	fmt.Printf(displayMsg)

	log.Infof("Waiting for connections...\n")
	fmt.Printf("\nWaiting for connections...\n")

	var once sync.Once
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
			if conn, err := listener.Accept(); err != nil {
				log.Errorf("Error while accepting connection: %v", err)
			} else {
				log.Infof("Connection accepted from %s\n for session [%s]", conn.RemoteAddr(), p.sessionId)

				once.Do(func() {
					fmt.Printf("\nConnection accepted for session [%s]\n", p.sessionId)
				})

				stream, err := p.muxClient.session.OpenStream()
				if err != nil {
					continue
				}
				log.Debugf("Client stream opened %d\n", stream.ID())
				go handleDataTransfer(stream, conn)
			}
		}
	}
}

// handleDataTransfer launches routines to transfer data between source and destination
func handleDataTransfer(dst io.ReadWriteCloser, src io.ReadWriteCloser) {
	var wait sync.WaitGroup
	wait.Add(2)

	go func() {
		io.Copy(dst, src)
		dst.Close()
		wait.Done()
	}()

	go func() {
		io.Copy(src, dst)
		src.Close()
		wait.Done()
	}()

	wait.Wait()
}

// getUnixSocketPath generates the unix socket file name based on sessionId and returns the path.
func getUnixSocketPath(sessionId string, dir string, suffix string) string {
	hash := fnv.New32a()
	hash.Write([]byte(sessionId))
	return filepath.Join(dir, fmt.Sprintf("%d_%s", hash.Sum32(), suffix))
}
