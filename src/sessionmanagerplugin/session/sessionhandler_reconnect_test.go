// Copyright 2026 Amazon.com, Inc. or its affiliates. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License"). You may not
// use this file except in compliance with the License. A copy of the
// License is located at
//
// http://aws.amazon.com/apache2.0/

// Package session - tests for data channel reconnection fixes.
// Verifies ResumeSessionHandler propagates StreamUrl and credentials.
package session

import (
	"testing"

	"github.com/aws/aws-sdk-go-v2/aws"
	wsChannelMock "github.com/aws/session-manager-plugin/src/communicator/mocks"
	dataChannelMock "github.com/aws/session-manager-plugin/src/datachannel/mocks"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
)

// TestResumeSessionHandler_PropagatesStreamUrl verifies that ResumeSessionHandler
// updates the WebSocket channel URL with the fresh StreamUrl from ResumeSession.
func TestResumeSessionHandler_PropagatesStreamUrl(t *testing.T) {
	mockDC := &dataChannelMock.IDataChannel{}
	mockWs := &wsChannelMock.IWebSocketChannel{}

	oldStreamUrl := "wss://ssmmessages.us-west-2.amazonaws.com/v1/data-channel/session123?role=publish_subscribe&cell-number=OLD_EXPIRED_CELL"
	newStreamUrl := "wss://ssmmessages.us-west-2.amazonaws.com/v1/data-channel/session123?role=publish_subscribe&cell-number=NEW_FRESH_CELL"
	newToken := "new-token-value"
	freshCreds := aws.Credentials{AccessKeyID: "FRESH_KEY", SecretAccessKey: "FRESH_SECRET"}

	session := &Session{
		SessionId:   "session123",
		StreamUrl:   oldStreamUrl,
		Credentials: freshCreds,
	}
	session.DataChannel = mockDC

	// Inject mock for GetResumeSessionParams
	originalFn := getResumeSessionParams
	getResumeSessionParams = func(s *Session, l log.T) (string, string, error) {
		s.Credentials = freshCreds
		return newToken, newStreamUrl, nil
	}
	defer func() { getResumeSessionParams = originalFn }()

	mockDC.On("GetWsChannel").Return(mockWs)
	mockDC.On("Reconnect", mock.Anything).Return(nil)
	mockWs.On("SetChannelToken", newToken).Return()
	mockWs.On("SetCredentials", freshCreds).Return()
	mockWs.On("SetStreamUrl", newStreamUrl).Return()

	// Call the actual production code
	err := session.ResumeSessionHandler(logger)

	assert.Nil(t, err)
	mockWs.AssertCalled(t, "SetStreamUrl", newStreamUrl)
	mockWs.AssertCalled(t, "SetCredentials", freshCreds)
	mockWs.AssertCalled(t, "SetChannelToken", newToken)
	assert.Equal(t, newStreamUrl, session.StreamUrl)
}

// TestResumeSessionHandler_PropagatesCredentials verifies that ResumeSessionHandler
// updates the WebSocket channel credentials with fresh ones before Reconnect.
func TestResumeSessionHandler_PropagatesCredentials(t *testing.T) {
	mockDC := &dataChannelMock.IDataChannel{}
	mockWs := &wsChannelMock.IWebSocketChannel{}

	freshCreds := aws.Credentials{
		AccessKeyID:     "FRESH_KEY",
		SecretAccessKey: "FRESH_SECRET",
		SessionToken:    "FRESH_TOKEN",
	}

	session := &Session{
		SessionId: "session123",
		StreamUrl: "wss://ssmmessages.us-west-2.amazonaws.com/v1/data-channel/session123?role=publish_subscribe",
	}
	session.DataChannel = mockDC

	originalFn := getResumeSessionParams
	getResumeSessionParams = func(s *Session, l log.T) (string, string, error) {
		s.Credentials = freshCreds
		return "token", "", nil
	}
	defer func() { getResumeSessionParams = originalFn }()

	mockDC.On("GetWsChannel").Return(mockWs)
	mockDC.On("Reconnect", mock.Anything).Return(nil)
	mockWs.On("SetChannelToken", "token").Return()
	mockWs.On("SetCredentials", freshCreds).Return()

	err := session.ResumeSessionHandler(logger)

	assert.Nil(t, err)
	mockWs.AssertCalled(t, "SetCredentials", freshCreds)
}

// TestResumeSessionHandler_EmptyStreamUrlNotSet verifies that if ResumeSession
// returns an empty StreamUrl, the existing URL is preserved.
func TestResumeSessionHandler_EmptyStreamUrlNotSet(t *testing.T) {
	mockDC := &dataChannelMock.IDataChannel{}
	mockWs := &wsChannelMock.IWebSocketChannel{}

	originalUrl := "wss://ssmmessages.us-west-2.amazonaws.com/v1/data-channel/session123?role=publish_subscribe&cell-number=STILL_VALID"
	creds := aws.Credentials{AccessKeyID: "KEY"}

	session := &Session{
		SessionId: "session123",
		StreamUrl: originalUrl,
	}
	session.DataChannel = mockDC

	originalFn := getResumeSessionParams
	getResumeSessionParams = func(s *Session, l log.T) (string, string, error) {
		s.Credentials = creds
		return "token", "", nil // Empty StreamUrl
	}
	defer func() { getResumeSessionParams = originalFn }()

	mockDC.On("GetWsChannel").Return(mockWs)
	mockDC.On("Reconnect", mock.Anything).Return(nil)
	mockWs.On("SetChannelToken", "token").Return()
	mockWs.On("SetCredentials", creds).Return()

	err := session.ResumeSessionHandler(logger)

	assert.Nil(t, err)
	mockWs.AssertNotCalled(t, "SetStreamUrl", mock.Anything)
	assert.Equal(t, originalUrl, session.StreamUrl)
}
