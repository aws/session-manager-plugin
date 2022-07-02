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
	"io/ioutil"
	"os"
	"testing"
	"time"

	"github.com/aws/session-manager-plugin/src/datachannel"
	"github.com/aws/session-manager-plugin/src/jsonutil"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

// Test Initialize
func TestInitializePortSession(t *testing.T) {
	var portParameters PortParameters
	jsonutil.Remarshal(properties, &portParameters)

	mockWebSocketChannel.On("SetOnMessage", mock.Anything)

	portSession := PortSession{
		Session: getSessionMock(),
	}
	portSession.Initialize(mockLog, &portSession.Session)

	mockWebSocketChannel.AssertExpectations(t)
	assert.Equal(t, portParameters, portSession.portParameters, "Initialize port parameters")
	assert.IsType(t, &StandardStreamForwarding{}, portSession.portSessionType)
}

func TestInitializePortSessionForPortForwardingWithOldAgent(t *testing.T) {
	var portParameters PortParameters
	jsonutil.Remarshal(map[string]interface{}{"portNumber": "8080", "type": "LocalPortForwarding"}, &portParameters)

	mockWebSocketChannel.On("SetOnMessage", mock.Anything)

	portSession := PortSession{
		Session: getSessionMockWithParams(portParameters, "2.2.0.0"),
	}
	portSession.Initialize(mockLog, &portSession.Session)

	mockWebSocketChannel.AssertExpectations(t)
	assert.Equal(t, portParameters, portSession.portParameters, "Initialize port parameters")
	assert.IsType(t, &BasicPortForwarding{}, portSession.portSessionType)
}

func TestInitializePortSessionForPortForwarding(t *testing.T) {
	var portParameters PortParameters
	jsonutil.Remarshal(map[string]interface{}{"portNumber": "8080", "type": "LocalPortForwarding"}, &portParameters)

	mockWebSocketChannel.On("SetOnMessage", mock.Anything)

	portSession := PortSession{
		Session: getSessionMockWithParams(portParameters, "3.1.0.0"),
	}
	portSession.Initialize(mockLog, &portSession.Session)

	mockWebSocketChannel.AssertExpectations(t)
	assert.Equal(t, portParameters, portSession.portParameters, "Initialize port parameters")
	assert.IsType(t, &MuxPortForwarding{}, portSession.portSessionType)
}

func TestStartSessionWithClosedWsConn(t *testing.T) {
	in, out, _ := os.Pipe()
	out.Write(outputMessage.Payload)
	oldStdin := os.Stdin
	os.Stdin = in

	var actualPayload []byte
	datachannel.SendMessageCall = func(log log.T, dataChannel *datachannel.DataChannel, input []byte, inputType int) error {
		actualPayload = input
		return nil
	}

	// Spawning a separate go routine to close files after a few seconds.
	// This is required as startSession has a for loop which will continuously reads data.
	go func() {
		time.Sleep(time.Second)
		os.Stdin = oldStdin
		in.Close()
		out.Close()
	}()

	portSession := PortSession{
		Session:        getSessionMock(),
		portParameters: PortParameters{PortNumber: "22"},
		portSessionType: &StandardStreamForwarding{
			inputStream:  in,
			outputStream: out,
			session:      getSessionMock(),
		},
	}
	portSession.SetSessionHandlers(mockLog)
	deserializedMsg := &message.ClientMessage{}
	err := deserializedMsg.DeserializeClientMessage(mockLog, actualPayload)
	assert.Nil(t, err)
	assert.Equal(t, outputMessage.Payload, deserializedMsg.Payload)
}

// Test ProcessStreamMessagePayload
func TestProcessStreamMessagePayload(t *testing.T) {
	in, out, _ := os.Pipe()
	defer func() {
		in.Close()
		out.Close()
	}()

	go func() {
		portSession := PortSession{
			Session:        getSessionMock(),
			portParameters: PortParameters{PortNumber: "22"},
			portSessionType: &StandardStreamForwarding{
				inputStream:  in,
				outputStream: out,
			},
		}
		portSession.ProcessStreamMessagePayload(mockLog, outputMessage)
		out.Close()
	}()

	payload, _ := ioutil.ReadAll(in)
	assert.Equal(t, outputMessage.Payload, payload)
}
