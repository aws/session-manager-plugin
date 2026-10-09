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
	"os"
	"sync"
	"testing"
	"time"

	wsChannelMock "github.com/aws/session-manager-plugin/src/communicator/mocks"
	dataChannelMock "github.com/aws/session-manager-plugin/src/datachannel/mocks"
	"github.com/aws/session-manager-plugin/src/log"
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

func TestValidateInputAndStartSessionWithEnvVariableParameter(t *testing.T) {
	var buffer bytes.Buffer
	sessionResponse := "{\"SessionId\": \"user-012345\", \"TokenValue\": \"Session-Token\", \"StreamUrl\": \"wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/user-012345?role=publish_subscribe\"}"
	os.Setenv("AWS_SSM_START_SESSION_RESPONSE", sessionResponse)
	args := []string{"session-manager-plugin",
		"AWS_SSM_START_SESSION_RESPONSE",
		"us-east-1", "StartSession", "", "{\"Target\": \"i-0123abc\"}", "https://ssm.us-east-1.amazonaws.com"}
	parameterPassed := false
	startSession = func(session *Session, log log.T) error {
		if session.TokenValue == "Session-Token" && session.SessionId == "user-012345" {
			parameterPassed = true
		}
		return nil
	}

	ValidateInputAndStartSession(args, &buffer)
	var _, envVariableExist = os.LookupEnv("AWS_SSM_START_SESSION_RESPONSE")
	assert.False(t, envVariableExist)
	assert.True(t, parameterPassed)
}

func TestValidateInputAndStartSessionWithWrongEnvVariableName(t *testing.T) {
	var buffer bytes.Buffer
	sessionResponse := "{\"SessionId\": \"user-012345\", \"TokenValue\": \"Session-Token\", \"StreamUrl\": \"wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/user-012345?role=publish_subscribe\"}"
	os.Setenv("WRONG_ENV_NAME", sessionResponse)
	args := []string{"session-manager-plugin",
		"WRONG_ENV_NAME",
		"us-east-1", "StartSession", "", "{\"Target\": \"i-0123abc\"}", "https://ssm.us-east-1.amazonaws.com"}
	startSessionInvoked := false
	startSession = func(session *Session, log log.T) error {
		startSessionInvoked = true
		return nil
	}

	ValidateInputAndStartSession(args, &buffer)
	var _, envVariableExist = os.LookupEnv("WRONG_ENV_NAME")
	assert.Contains(t, buffer.String(), "Cannot perform start session: invalid character 'W'")
	assert.True(t, envVariableExist)
	assert.False(t, startSessionInvoked)
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
	mockDataChannel.On("SetWebsocket", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).Return()
	mockDataChannel.On("GetWsChannel").Return(mockWsChannel)
	mockDataChannel.On("RegisterOutputStreamHandler", mock.Anything, mock.Anything)
	mockDataChannel.On("ResendStreamDataMessageScheduler", mock.Anything).Return(nil)

	mockWsChannel.On("SetOnMessage", mock.Anything)
	mockWsChannel.On("SetOnError", mock.Anything)
}

// TestValidateStreamUrl covers the client-side StreamUrl endpoint validation.
func TestValidateStreamUrl(t *testing.T) {
	const region = "us-east-1"
	const endpoint = "https://ssm.us-east-1.amazonaws.com"
	cases := []struct {
		name      string
		streamUrl string
		region    string
		endpoint  string
		wantErr   bool
	}{
		{"valid commercial", "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s?role=publish_subscribe", region, endpoint, false},
		{"valid fips", "wss://ssmmessages-fips.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"valid without endpoint (SDK-resolved suffix)", "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s", region, "", false},
		{"valid china partition", "wss://ssmmessages.cn-north-1.amazonaws.com.cn/v1/data-channel/s", "cn-north-1", "https://ssm.cn-north-1.amazonaws.com.cn", false},
		{"dual-stack endpoint", "wss://ssmmessages.us-east-1.api.aws/v1/data-channel/s", region, "", false},
		{"privatelink vpc interface endpoint", "wss://vpce-0abc123def456.ssmmessages.us-east-1.vpce.amazonaws.com/v1/data-channel/s", region, "", false},
		{"host is case-insensitive", "wss://SSMMessages.US-EAST-1.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"loopback ws", "ws://127.0.0.1:9443/stream", region, "", true},
		{"loopback wss", "wss://127.0.0.1:9443/stream", region, "", true},
		{"foreign suffix", "wss://ssmmessages.us-east-1.example.com/v1/data-channel/s", region, "", true},
		{"prefix smuggling", "wss://ssmmessages.us-east-1.amazonaws.com.example.com/v1/data-channel/s", region, endpoint, true},
		{"embedded userinfo", "wss://ssmmessages.us-east-1.amazonaws.com@example.com/v1/data-channel/s", region, endpoint, true},
		{"host not tied to client region", "wss://ssmmessages.us-west-2.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"mgs service label", "wss://stream-mgs.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"gamma service label", "wss://stream-gamma.us-west-2.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"beta service label", "wss://stream-beta.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"fips service label", "wss://stream-fips.us-east-2.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"service label without keyword", "wss://other-service.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"customer-named load balancer", "wss://evil-mgs-123.us-east-1.elb.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"extra leading label", "wss://evil.ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"bare aws suffix", "wss://amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"s3 virtual-hosted bucket", "wss://evil-mgs.s3.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"s3 legacy regional bucket", "ws://evil-mgs.s3-us-west-2.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"s3 website bucket", "ws://evil-mgs.s3-website-us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"airport code region label", "wss://stream-mgs.iad.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"new partition style region label", "wss://ssmmessages.ap-examplea-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"amazon.com suffix", "wss://stream-mgs.pdx.amazon.com/v1/data-channel/s", region, endpoint, false},
		{"amazon.com suffix without keyword", "wss://www.pdx.amazon.com/v1/data-channel/s", region, endpoint, true},
		{"amazon.com suffix with extra leading label", "wss://evil.stream-mgs.pdx.amazon.com/v1/data-channel/s", region, endpoint, true},
		{"ws scheme", "ws://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, false},
		{"wrong scheme https", "https://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s", region, endpoint, true},
		{"empty url", "", region, endpoint, true},
		{"empty region", "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s", "", endpoint, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateStreamUrl(tc.streamUrl, tc.region, tc.endpoint)
			if tc.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}

func TestValidateInputAndStartSessionRejectsFakeStreamUrl(t *testing.T) {
	var buffer bytes.Buffer
	sessionResponse := "{\"SessionId\": \"user-012345\", \"TokenValue\": \"some-token\", \"StreamUrl\": \"ws://127.0.0.1:9443/stream\"}"
	args := []string{"session-manager-plugin",
		sessionResponse,
		"us-east-1", "StartSession", "", "{\"Target\": \"i-0123abc\"}", "https://ssm.us-east-1.amazonaws.com"}
	startSessionCalled := false
	startSession = func(session *Session, log log.T) error {
		startSessionCalled = true
		return nil
	}
	ValidateInputAndStartSession(args, &buffer)
	assert.False(t, startSessionCalled, "session must not start for a non-SSM StreamUrl")
	assert.Contains(t, buffer.String(), "Cannot perform start session")
}

func TestValidateStreamUrlAdditionalHosts(t *testing.T) {
	const region = "us-east-1"
	t.Setenv(additionalStreamHostsEnvVar, " stream-a.example.com , Stream-B.Example.org.")
	cases := []struct {
		name      string
		streamUrl string
		wantErr   bool
	}{
		{"allowed host", "wss://stream-a.example.com/v1/data-channel/s", false},
		{"allowed host is case-insensitive", "wss://stream-b.example.org/v1/data-channel/s", false},
		{"standard host still allowed", "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s", false},
		{"allowed host with ws", "ws://stream-a.example.com/v1/data-channel/s", false},
		{"allowed host still rejects https", "https://stream-a.example.com/v1/data-channel/s", true},
		{"allowed host still rejects userinfo", "wss://stream-a.example.com@example.com/v1/data-channel/s", true},
		{"subdomain of allowed host", "wss://evil.stream-a.example.com/v1/data-channel/s", true},
		{"unlisted host", "wss://stream-c.example.com/v1/data-channel/s", true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := validateStreamUrl(tc.streamUrl, region, "")
			if tc.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
			}
		})
	}
}

func TestValidateStreamUrlAdditionalHostsUnset(t *testing.T) {
	t.Setenv(additionalStreamHostsEnvVar, "")
	assert.Error(t, validateStreamUrl("wss://stream-a.example.com/v1/data-channel/s", "us-east-1", ""))
}

func TestValidateInputAndStartSessionAllowsEmptyToken(t *testing.T) {
	var buffer bytes.Buffer
	sessionResponse := "{\"SessionId\": \"user-012345\", \"TokenValue\": \"\", \"StreamUrl\": \"wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/s\"}"
	args := []string{"session-manager-plugin",
		sessionResponse,
		"us-east-1", "StartSession", "", "{\"Target\": \"i-0123abc\"}", "https://ssm.us-east-1.amazonaws.com"}
	startSessionCalled := false
	startSession = func(session *Session, log log.T) error {
		startSessionCalled = true
		return nil
	}
	ValidateInputAndStartSession(args, &buffer)
	assert.True(t, startSessionCalled, "session must start when TokenValue is empty")
}
