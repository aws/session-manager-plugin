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
	"fmt"
	"net"
	"os"
	"os/signal"
	"strconv"
	"time"

	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/sessionutil"
	"github.com/aws/session-manager-plugin/src/version"
)

// BasicPortForwarding is type of port session
// accepts one client connection at a time
type BasicPortForwarding struct {
	port           IPortSession
	stream         *net.Conn
	listener       *net.Listener
	sessionId      string
	portParameters PortParameters
	session        session.Session
}

// getNewListener returns a new listener to given address and type like tcp, unix etc.
var getNewListener = func(listenerType string, listenerAddress string) (listener net.Listener, err error) {
	return net.Listen(listenerType, listenerAddress)
}

// acceptConnection returns connection to the listener
var acceptConnection = func(log log.T, listener net.Listener) (tcpConn net.Conn, err error) {
	return listener.Accept()
}

// IsStreamNotSet checks if stream is not set
func (p *BasicPortForwarding) IsStreamNotSet() (status bool) {
	return p.stream == nil
}

// Stop closes the stream
func (p *BasicPortForwarding) Stop() {
	if p.stream != nil {
		(*p.stream).Close()
	}
	os.Exit(0)
}

// InitializeStreams establishes connection and initializes the stream
func (p *BasicPortForwarding) InitializeStreams(log log.T, agentVersion string) (err error) {
	p.handleControlSignals(log)
	if err = p.startLocalConn(log); err != nil {
		return
	}
	return
}

// ReadStream reads data from the stream
func (p *BasicPortForwarding) ReadStream(log log.T) (err error) {
	msg := make([]byte, config.StreamDataPayloadSize)
	for {
		numBytes, err := (*p.stream).Read(msg)
		if err != nil {
			log.Debugf("Reading from port %s failed with error: %v. Close this connection, listen and accept new one.",
				p.portParameters.PortNumber, err)

			// Send DisconnectToPort flag to agent when client tcp connection drops to ensure agent closes tcp connection too with server port
			if err = p.session.DataChannel.SendFlag(log, message.DisconnectToPort); err != nil {
				log.Errorf("Failed to send packet: %v", err)
				return err
			}

			if err = p.reconnect(log); err != nil {
				return err
			}

			// continue to read from connection as it has been re-established
			continue
		}

		log.Tracef("Received message of size %d from stdin.", numBytes)
		if err = p.session.DataChannel.SendInputDataMessage(log, message.Output, msg[:numBytes]); err != nil {
			log.Errorf("Failed to send packet: %v", err)
			return err
		}
		// Sleep to process more data
		time.Sleep(time.Millisecond)
	}
}

// WriteStream writes data to stream
func (p *BasicPortForwarding) WriteStream(outputMessage message.ClientMessage) error {
	_, err := (*p.stream).Write(outputMessage.Payload)
	return err
}

// startLocalConn establishes a new local connection to forward remote server packets to
func (p *BasicPortForwarding) startLocalConn(log log.T) (err error) {
	// When localPortNumber is not specified, set port number to 0 to let net.conn choose an open port at random
	localPortNumber := p.portParameters.LocalPortNumber
	if p.portParameters.LocalPortNumber == "" {
		localPortNumber = "0"
	}

	var listener net.Listener
	if listener, err = p.startLocalListener(log, localPortNumber); err != nil {
		log.Errorf("Unable to open tcp connection to port. %v", err)
		return err
	}

	var tcpConn net.Conn
	if tcpConn, err = acceptConnection(log, listener); err != nil {
		log.Errorf("Failed to accept connection with error. %v", err)
		return err
	}
	log.Infof("Connection accepted for session %s.", p.sessionId)
	fmt.Printf("Connection accepted for session %s.\n", p.sessionId)

	p.listener = &listener
	p.stream = &tcpConn

	return
}

// startLocalListener starts a local listener to given address
func (p *BasicPortForwarding) startLocalListener(log log.T, portNumber string) (listener net.Listener, err error) {
	var displayMessage string
	switch p.portParameters.LocalConnectionType {
	case "unix":
		if listener, err = getNewListener(p.portParameters.LocalConnectionType, p.portParameters.LocalUnixSocket); err != nil {
			return
		}
		displayMessage = fmt.Sprintf("Unix socket %s opened for sessionId %s.", p.portParameters.LocalUnixSocket, p.sessionId)
	default:
		if listener, err = getNewListener("tcp", "localhost:"+portNumber); err != nil {
			return
		}
		// get port number the TCP listener opened
		p.portParameters.LocalPortNumber = strconv.Itoa(listener.Addr().(*net.TCPAddr).Port)
		displayMessage = fmt.Sprintf("Port %s opened for sessionId %s.", p.portParameters.LocalPortNumber, p.sessionId)
	}

	log.Info(displayMessage)
	fmt.Println(displayMessage)
	return
}

// handleControlSignals handles terminate signals
func (p *BasicPortForwarding) handleControlSignals(log log.T) {
	c := make(chan os.Signal, 1)
	signal.Notify(c, sessionutil.ControlSignals...)
	go func() {
		<-c
		fmt.Println("Terminate signal received, exiting.")

		if version.DoesAgentSupportTerminateSessionFlag(log, p.session.DataChannel.GetAgentVersion()) {
			if err := p.session.DataChannel.SendFlag(log, message.TerminateSession); err != nil {
				log.Errorf("Failed to send TerminateSession flag: %v", err)
			}
			fmt.Fprintf(os.Stdout, "\n\nExiting session with sessionId: %s.\n\n", p.sessionId)
			p.Stop()
		} else {
			p.session.TerminateSession(log)
		}
	}()
}

// reconnect closes existing connection, listens to new connection and accept it
func (p *BasicPortForwarding) reconnect(log log.T) (err error) {
	// close existing connection as it is in a state from which data cannot be read
	(*p.stream).Close()

	// wait for new connection on listener and accept it
	var conn net.Conn
	if conn, err = acceptConnection(log, *p.listener); err != nil {
		return log.Errorf("Failed to accept connection with error. %v", err)
	}
	p.stream = &conn

	return
}
