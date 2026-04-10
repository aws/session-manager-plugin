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
	"fmt"
	"os"
	"testing"

	"github.com/aws/session-manager-plugin/src/communicator"
	wsChannelMock "github.com/aws/session-manager-plugin/src/communicator/mocks"
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/datachannel"
	dataChannelMock "github.com/aws/session-manager-plugin/src/datachannel/mocks"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/stretchr/testify/mock"

	"github.com/stretchr/testify/assert"
)

var (
	clientId   = "clientId_abc"
	sessionId  = "sessionId_abc"
	instanceId = "i-123456"
)

func TestOpenDataChannelWithNoCredential(t *testing.T) {
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel = &wsChannelMock.IWebSocketChannel{}

	// Ensure no credentials are available
	os.Unsetenv("AWS_ACCESS_KEY_ID")
	os.Unsetenv("AWS_SECRET_ACCESS_KEY")
	os.Unsetenv("AWS_SESSION_TOKEN")
	os.Unsetenv("AWS_PROFILE")

	sessionMock := &Session{
		StreamUrl: "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?role=publish_subscribe", // Non-presigned URL
		Endpoint:  "",                                                                                              // Add endpoint for session creation
	}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	err := sessionMock.OpenDataChannel(logger)
	assert.Nil(t, err)
}

func TestOpenDataChannel(t *testing.T) {
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel = &wsChannelMock.IWebSocketChannel{}

	sessionMock := &Session{
		// Non-presigned URL
		StreamUrl: "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?role=publish_subscribe",
	}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	// Set up credentials for this test
	os.Setenv("AWS_ACCESS_KEY_ID", "test-access-key-id")
	os.Setenv("AWS_SECRET_ACCESS_KEY", "test-secret-access-key")
	defer func() {
		os.Unsetenv("AWS_ACCESS_KEY_ID")
		os.Unsetenv("AWS_SECRET_ACCESS_KEY")
	}()

	err := sessionMock.OpenDataChannel(logger)
	assert.Nil(t, err)
	assert.NotNil(t, sessionMock.Signer)
}

func TestOpenDataChannelWithClientConfigureSkipped(t *testing.T) {
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel = &wsChannelMock.IWebSocketChannel{}

	// Set environment variable to skip client configuration
	os.Setenv("SSM_PLUGIN_SKIP_CLIENT_CONFIGURE", "true")
	defer os.Unsetenv("SSM_PLUGIN_SKIP_CLIENT_CONFIGURE")

	sessionMock := &Session{
		StreamUrl: "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?role=publish_subscribe",
	}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	err := sessionMock.OpenDataChannel(logger)
	assert.Nil(t, err)
	assert.Nil(t, sessionMock.Signer) // Should be nil because client configuration is skipped
}

func TestOpenDataChannelWithPresignedURL(t *testing.T) {
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel = &wsChannelMock.IWebSocketChannel{}

	sessionMock := &Session{
		StreamUrl: "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230101%2Fus-east-1%2Fssmmessages%2Faws4_request&X-Amz-Date=20230101T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=example-signature",
	}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	err := sessionMock.OpenDataChannel(logger)
	assert.Nil(t, err)
	assert.Nil(t, sessionMock.Signer) // Should be nil because URL is presigned
}

func TestIsPresignedURL(t *testing.T) {
	// Test presigned URL
	presignedURL := "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20230101%2Fus-east-1%2Fssmmessages%2Faws4_request&X-Amz-Date=20230101T000000Z&X-Amz-Expires=900&X-Amz-SignedHeaders=host&X-Amz-Signature=example-signature"
	isPresigned, err := communicator.IsPresignedURL(presignedURL)
	assert.Nil(t, err)
	assert.True(t, isPresigned)

	// Test non-presigned URL
	regularURL := "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?role=publish_subscribe"
	isPresigned, err = communicator.IsPresignedURL(regularURL)
	assert.Nil(t, err)
	assert.False(t, isPresigned)

	// Test invalid URL
	invalidURL := "ht!tp://invalid url with spaces"
	isPresigned, err = communicator.IsPresignedURL(invalidURL)
	assert.NotNil(t, err)
	assert.False(t, isPresigned)
}

func TestOpenDataChannelWithError(t *testing.T) {
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel = &wsChannelMock.IWebSocketChannel{}

	sessionMock := &Session{}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()

	//First reconnection failed when open datachannel, success after retry
	mockDataChannel.On("Open", mock.Anything).Return(fmt.Errorf("error"))
	mockDataChannel.On("Reconnect", mock.Anything).Return(fmt.Errorf("error")).Once()
	mockDataChannel.On("Reconnect", mock.Anything).Return(nil).Once()
	err := sessionMock.OpenDataChannel(logger)
	assert.Nil(t, err)
}

func TestOpenDataChannelWithEndpoint(t *testing.T) {
	mockDataChannel = &dataChannelMock.IDataChannel{}
	mockWsChannel = &wsChannelMock.IWebSocketChannel{}

	// Session with a custom SSM endpoint — should NOT affect credential resolution
	sessionMock := &Session{
		StreamUrl: "wss://ssmmessages.us-east-1.amazonaws.com/v1/data-channel/test-session?role=publish_subscribe",
		Endpoint:  "https://ssm.us-east-1.amazonaws.com",
	}
	sessionMock.DataChannel = mockDataChannel
	SetupMockActions()
	mockDataChannel.On("Open", mock.Anything).Return(nil)

	// Set up credentials for this test
	os.Setenv("AWS_ACCESS_KEY_ID", "test-access-key-id")
	os.Setenv("AWS_SECRET_ACCESS_KEY", "test-secret-access-key")
	defer func() {
		os.Unsetenv("AWS_ACCESS_KEY_ID")
		os.Unsetenv("AWS_SECRET_ACCESS_KEY")
	}()

	err := sessionMock.OpenDataChannel(logger)
	assert.Nil(t, err)
	// Signer should still be set — endpoint should not interfere with credential lookup
	assert.NotNil(t, sessionMock.Signer)
}

func TestProcessFirstMessageOutputMessageFirst(t *testing.T) {
	outputMessage := message.ClientMessage{
		PayloadType: uint32(message.Output),
		Payload:     []byte("testing"),
	}

	dataChannel := &datachannel.DataChannel{}
	dataChannel.Initialize(logger, clientId, sessionId, instanceId, false)
	session := Session{
		DataChannel: dataChannel,
	}

	session.ProcessFirstMessage(logger, outputMessage)
	assert.Equal(t, config.ShellPluginName, session.DataChannel.GetSessionType())
	assert.True(t, <-session.DataChannel.IsSessionTypeSet())
}
