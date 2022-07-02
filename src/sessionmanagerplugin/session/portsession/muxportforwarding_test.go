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
	"net"
	"testing"
	"time"

	"github.com/aws/session-manager-plugin/src/datachannel"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/stretchr/testify/assert"
)

// test readStream
func TestReadStream(t *testing.T) {
	out, in := net.Pipe()
	defer out.Close()

	session := getSessionMock()

	portSession := PortSession{
		Session: session,
		portSessionType: &MuxPortForwarding{
			session:   session,
			muxClient: &MuxClient{in, nil},
			mgsConn:   &MgsConn{nil, out},
		},
	}
	go func() {
		in.Write(outputMessage.Payload)
		in.Close()
	}()

	var actualPayload []byte
	datachannel.SendMessageCall = func(log log.T, dataChannel *datachannel.DataChannel, input []byte, inputType int) error {
		actualPayload = input
		return nil
	}

	go func() {
		portSession.portSessionType.ReadStream(mockLog)
	}()

	select {
	case <-time.After(time.Second):
	}

	deserializedMsg := &message.ClientMessage{}
	err := deserializedMsg.DeserializeClientMessage(mockLog, actualPayload)
	assert.Nil(t, err)
	assert.Equal(t, outputMessage.Payload, deserializedMsg.Payload)
}

// test writeStream
func TestWriteStream(t *testing.T) {
	out, in := net.Pipe()
	defer in.Close()
	defer out.Close()

	portSession := PortSession{
		portSessionType: &MuxPortForwarding{
			session: getSessionMock(),
			mgsConn: &MgsConn{nil, in},
		},
	}

	go func() {
		portSession.portSessionType.WriteStream(outputMessage)
	}()

	msg := make([]byte, 20)
	n, _ := out.Read(msg)
	msg = msg[:n]

	assert.Equal(t, outputMessage.Payload, msg)
}

// Test handleDataTransfer
func TestHandleDataTransferSrcToDst(t *testing.T) {
	msg := make([]byte, 20)
	out, in := net.Pipe()
	out1, in1 := net.Pipe()

	defer out1.Close()
	go func() {
		in.Write(outputMessage.Payload)
		in.Close()
	}()
	go func() {
		n, _ := out1.Read(msg)
		msg = msg[:n]
	}()

	handleDataTransfer(in1, out)
	assert.EqualValues(t, outputMessage.Payload, msg)
}

func TestHandleDataTransferDstToSrc(t *testing.T) {
	msg := make([]byte, 20)
	out, in := net.Pipe()
	out1, in1 := net.Pipe()

	defer out.Close()
	go func() {
		in1.Write(outputMessage.Payload)
		in1.Close()
	}()
	go func() {
		n, _ := out.Read(msg)
		msg = msg[:n]
	}()

	handleDataTransfer(in, out1)
	assert.EqualValues(t, outputMessage.Payload, msg)
}
