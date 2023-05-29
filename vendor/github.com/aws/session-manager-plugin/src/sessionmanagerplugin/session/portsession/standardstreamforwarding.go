// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
	"io"
	"os"
	"time"

	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
)

type StandardStreamForwarding struct {
	port           IPortSession
	inputStream    *os.File
	outputStream   *os.File
	portParameters PortParameters
	session        session.Session
}

// IsStreamNotSet checks if streams are not set
func (p *StandardStreamForwarding) IsStreamNotSet() (status bool) {
	return p.inputStream == nil || p.outputStream == nil
}

// Stop closes the streams
func (p *StandardStreamForwarding) Stop() {
	p.inputStream.Close()
	p.outputStream.Close()
	os.Exit(0)
}

// InitializeStreams initializes the streams with its file descriptors
func (p *StandardStreamForwarding) InitializeStreams(log log.T, agentVersion string) (err error) {
	p.inputStream = os.Stdin
	p.outputStream = os.Stdout
	return
}

// ReadStream reads data from the input stream
func (p *StandardStreamForwarding) ReadStream(log log.T) (err error) {
	msg := make([]byte, config.StreamDataPayloadSize)
	for {
		numBytes, err := p.inputStream.Read(msg)
		if err != nil {
			return p.handleReadError(log, err)
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

// WriteStream writes data to output stream
func (p *StandardStreamForwarding) WriteStream(outputMessage message.ClientMessage) error {
	_, err := p.outputStream.Write(outputMessage.Payload)
	return err
}

// handleReadError handles read error
func (p *StandardStreamForwarding) handleReadError(log log.T, err error) error {
	if err == io.EOF {
		log.Infof("Session to instance[%s] on port[%s] was closed.", p.session.TargetId, p.portParameters.PortNumber)
		return nil
	} else {
		log.Errorf("Reading input failed with error: %v", err)
		return err
	}
}
