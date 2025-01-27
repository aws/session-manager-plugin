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
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/signer/v4"
	"github.com/aws/session-manager-plugin/src/communicator/mocks"
	"github.com/aws/session-manager-plugin/src/datachannel"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
)

var (
	agentVersion         = "2.3.750.0"
	mockLog              = log.NewMockLog()
	mockWebSocketChannel = mocks.IWebSocketChannel{}
	outputMessage        = message.ClientMessage{
		PayloadType:   uint32(message.Output),
		Payload:       []byte("testing123"),
		PayloadLength: 10,
	}
	properties = map[string]interface{}{
		"PortNumber": "22",
	}
	region     = "us-east-1"
	mockSigner = &v4.Signer{Credentials: credentials.NewStaticCredentials("AKID", "SECRET", "SESSION")}
)

func getSessionMock() session.Session {
	return getSessionMockWithParams(properties, agentVersion)
}

func getSessionMockWithParams(properties interface{}, agentVersion string) session.Session {
	datachannel := &datachannel.DataChannel{}
	datachannel.SetAgentVersion(agentVersion)

	var mockSession = session.Session{
		DataChannel: datachannel,
	}

	mockSession.DataChannel.Initialize(mockLog, "clientId", "sessionId", "targetId", false)
	mockSession.DataChannel.SetWsChannel(&mockWebSocketChannel)
	mockSession.SessionProperties = properties
	return mockSession
}
