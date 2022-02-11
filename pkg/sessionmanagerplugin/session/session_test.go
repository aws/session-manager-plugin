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

// Package session starts the session.
package session

import (
	"bytes"
	"fmt"
	"sync"
	"testing"
	"time"

	wsChannelMock "github.com/aws/session-manager-plugin/pkg/communicator/mocks"
	dataChannelMock "github.com/aws/session-manager-plugin/pkg/datachannel/mocks"
	"github.com/aws/session-manager-plugin/pkg/log"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

var (
	logger          = log.NewMockLog()
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel   = &wsChannelMock.IWebSocketChannel{}
)

func TestValidateInputAndStartSessionWithNoInputArgument(t *testing.T) {
	var buffer bytes.Buffer
	args := []string{""}
	ValidateInputAndStartSession(args, &buffer)
	assert.Contains(t, buffer.String(), "The Session Manager plugin was installed successfully")
}

func TestValidateInputAndStartSessionWithWrongInputArgument(t *testing.T) {
	var buffer bytes.Buffer
	args := []string{1: "version"}
	ValidateInputAndStartSession(args, &buffer)
	assert.Contains(t, buffer.String(), "Use session-manager-plugin --version to check the version")
}

func TestValidateInputAndStartSession(t *testing.T) {
	var buffer bytes.Buffer
	sessionResponse := "{\"SessionId\": \"user-012345\", \"TokenValue\": \"ABCD\", \"StreamUrl\": \"wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/user-012345?role=publish_subscribe\"}"
	args := []string{"session-manager-plugin",
		sessionResponse,
		"us-east-1", "StartSession", "", "{\"Target\": \"i-0123abc\"}", "https://ssm.us-east-1.amazonaws.com"}
	startSession = func(session *Session, log log.T) error {
		return fmt.Errorf("Some error")
	}
	ValidateInputAndStartSession(args, &buffer)
	assert.Contains(t, buffer.String(), "Cannot perform start session: Some error")
}

func TestExecute(t *testing.T) {
	sessionMock := &Session{}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	isSessionTypeSetMock := make(chan bool, 1)
	isSessionTypeSetMock <- true
	mockDataChannel.On("IsSessionTypeSet").Return(isSessionTypeSetMock)
	mockDataChannel.On("GetSessionType").Return("Standard_Stream")
	mockDataChannel.On("GetSessionProperties").Return("SessionProperties")

	isStreamMessageResendTimeout := make(chan bool, 1)
	mockDataChannel.On("IsStreamMessageResendTimeout").Return(isStreamMessageResendTimeout)

	setSessionHandlersWithSessionType = func(session *Session, log log.T) error {
		return fmt.Errorf("start session error for %s", session.SessionType)
	}

	err := sessionMock.Execute(logger)
	assert.NotNil(t, err)
	assert.Contains(t, err.Error(), "start session error for Standard_Stream")
}

func TestExecuteAndStreamMessageResendTimesOut(t *testing.T) {
	sessionMock := &Session{}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	isStreamMessageResendTimeout := make(chan bool, 1)
	mockDataChannel.On("IsStreamMessageResendTimeout").Return(isStreamMessageResendTimeout)

	var wg sync.WaitGroup
	wg.Add(1)
	handleStreamMessageResendTimeout = func(session *Session, log log.T) {
		time.Sleep(10 * time.Millisecond)
		isStreamMessageResendTimeout <- true
		wg.Done()
		return
	}

	isSessionTypeSetMock := make(chan bool, 1)
	isSessionTypeSetMock <- true
	mockDataChannel.On("IsSessionTypeSet").Return(isSessionTypeSetMock)
	mockDataChannel.On("GetSessionType").Return("Standard_Stream")
	mockDataChannel.On("GetSessionProperties").Return("SessionProperties")

	setSessionHandlersWithSessionType = func(session *Session, log log.T) error {
		return nil
	}

	var err error
	go func() {
		err = sessionMock.Execute(logger)
		time.Sleep(200 * time.Millisecond)
	}()
	wg.Wait()
	assert.Nil(t, err)
}

func SetupMockActions() {
	mockDataChannel.On("Initialize", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).Return()
	mockDataChannel.On("SetWebsocket", mock.Anything, mock.Anything, mock.Anything).Return()
	mockDataChannel.On("GetWsChannel").Return(mockWsChannel)
	mockDataChannel.On("RegisterOutputStreamHandler", mock.Anything, mock.Anything)
	mockDataChannel.On("ResendStreamDataMessageScheduler", mock.Anything).Return(nil)

	mockWsChannel.On("SetOnMessage", mock.Anything)
	mockWsChannel.On("SetOnError", mock.Anything)
}
