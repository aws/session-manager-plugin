// Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/jsonutil"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
	"github.com/aws/session-manager-plugin/src/version"
)

const (
	LocalPortForwardingType = "LocalPortForwarding"
)

type PortSession struct {
	session.Session
	portParameters  PortParameters
	portSessionType IPortSession
}

type IPortSession interface {
	IsStreamNotSet() (status bool)
	InitializeStreams(log log.T, agentVersion string) (err error)
	ReadStream(log log.T) (err error)
	WriteStream(outputMessage message.ClientMessage) (err error)
	Stop()
}

type PortParameters struct {
	PortNumber          string `json:"portNumber"`
	LocalPortNumber     string `json:"localPortNumber"`
	LocalUnixSocket     string `json:"localUnixSocket"`
	LocalConnectionType string `json:"localConnectionType"`
	Type                string `json:"type"`
}

func init() {
	session.Register(&PortSession{})
}

// Name is the session name used inputStream the plugin
func (PortSession) Name() string {
	return config.PortPluginName
}

func (s *PortSession) Initialize(log log.T, sessionVar *session.Session) {
	s.Session = *sessionVar
	if err := jsonutil.Remarshal(s.SessionProperties, &s.portParameters); err != nil {
		log.Errorf("Invalid format: %v", err)
	}

	if s.portParameters.Type == LocalPortForwardingType {
		if version.DoesAgentSupportTCPMultiplexing(log, s.DataChannel.GetAgentVersion()) {
			s.portSessionType = &MuxPortForwarding{
				sessionId:      s.SessionId,
				portParameters: s.portParameters,
				session:        s.Session,
			}
		} else {
			s.portSessionType = &BasicPortForwarding{
				sessionId:      s.SessionId,
				portParameters: s.portParameters,
				session:        s.Session,
			}
		}
	} else {
		s.portSessionType = &StandardStreamForwarding{
			portParameters: s.portParameters,
			session:        s.Session,
		}
	}

	s.DataChannel.RegisterOutputStreamHandler(s.ProcessStreamMessagePayload, true)
	s.DataChannel.GetWsChannel().SetOnMessage(func(input []byte) {
		if s.portSessionType.IsStreamNotSet() {
			outputMessage := &message.ClientMessage{}
			if err := outputMessage.DeserializeClientMessage(log, input); err != nil {
				log.Debugf("Ignore message deserialize error while stream connection had not set.")
				return
			}
			if outputMessage.MessageType == message.OutputStreamMessage {
				log.Debugf("Waiting for user to establish connection before processing incoming messages.")
				return
			} else {
				log.Infof("Received %s message while establishing connection", outputMessage.MessageType)
			}
		}
		s.DataChannel.OutputMessageHandler(log, s.Stop, s.SessionId, input)
	})
	log.Infof("Connected to instance[%s] on port: %s", sessionVar.TargetId, s.portParameters.PortNumber)
}

func (s *PortSession) Stop() {
	s.portSessionType.Stop()
}

// StartSession redirects inputStream/outputStream data to datachannel.
func (s *PortSession) SetSessionHandlers(log log.T) (err error) {
	if err = s.portSessionType.InitializeStreams(log, s.DataChannel.GetAgentVersion()); err != nil {
		return err
	}

	if err = s.portSessionType.ReadStream(log); err != nil {
		return err
	}
	return
}

// ProcessStreamMessagePayload writes messages received on datachannel to stdout
func (s *PortSession) ProcessStreamMessagePayload(log log.T, outputMessage message.ClientMessage) (isHandlerReady bool, err error) {
	if s.portSessionType.IsStreamNotSet() {
		log.Debugf("Waiting for streams to be established before processing incoming messages.")
		return false, nil
	}
	log.Tracef("Received payload of size %d from datachannel.", outputMessage.PayloadLength)
	err = s.portSessionType.WriteStream(outputMessage)
	return true, err
}
