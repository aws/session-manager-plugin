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
	"os"
	"testing"
	"time"

	"github.com/aws/session-manager-plugin/src/datachannel"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/stretchr/testify/assert"
)

// Test StartSession
func TestStartSessionForStandardStreamForwarding(t *testing.T) {
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
			session:        getSessionMock(),
			portParameters: PortParameters{PortNumber: "22"},
		},
	}
	portSession.SetSessionHandlers(mockLog)
	deserializedMsg := &message.ClientMessage{}
	err := deserializedMsg.DeserializeClientMessage(mockLog, actualPayload)
	assert.Nil(t, err)
	assert.Equal(t, outputMessage.Payload, deserializedMsg.Payload)
}
