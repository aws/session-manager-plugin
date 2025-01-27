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
	"math/rand"
	"os"

	sdkSession "github.com/aws/aws-sdk-go/aws/session"
	v4 "github.com/aws/aws-sdk-go/aws/signer/v4"
	"github.com/aws/aws-sdk-go/service/ssm"
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/retry"
	"github.com/aws/session-manager-plugin/src/sdkutil"
)

// OpenDataChannel initializes datachannel
func (s *Session) OpenDataChannel(log log.T) (err error) {
	s.retryParams = retry.RepeatableExponentialRetryer{
		GeometricRatio:      config.RetryBase,
		InitialDelayInMilli: rand.Intn(config.DataChannelRetryInitialDelayMillis) + config.DataChannelRetryInitialDelayMillis,
		MaxDelayInMilli:     config.DataChannelRetryMaxIntervalMillis,
		MaxAttempts:         config.DataChannelNumMaxRetries,
	}

	sess, err := sdkutil.GetNewSessionWithEndpoint(s.Endpoint)
	if err != nil {
		log.Errorf("Failed to get credential to create aws session")
	} else {
		s.Signer = v4.NewSigner(sess.Config.Credentials)
	}

	s.DataChannel.Initialize(log, s.ClientId, s.SessionId, s.TargetId, s.IsAwsCliUpgradeNeeded)
	s.DataChannel.SetWebsocket(log, s.StreamUrl, s.TokenValue, s.Region, s.Signer)
	s.DataChannel.GetWsChannel().SetOnMessage(
		func(input []byte) {
			s.DataChannel.OutputMessageHandler(log, s.Stop, s.SessionId, input)
		})
	s.DataChannel.RegisterOutputStreamHandler(s.ProcessFirstMessage, false)

	if err = s.DataChannel.Open(log); err != nil {
		log.Errorf("Retrying connection for data channel id: %s failed with error: %s", s.SessionId, err)
		s.retryParams.CallableFunc = func() (err error) { return s.DataChannel.Reconnect(log) }
		if err = s.retryParams.Call(); err != nil {
			log.Error(err)
		}
	}

	s.DataChannel.GetWsChannel().SetOnError(
		func(err error) {
			log.Errorf("Trying to reconnect the session: %v with seq num: %d", s.StreamUrl, s.DataChannel.GetStreamDataSequenceNumber())
			s.retryParams.CallableFunc = func() (err error) { return s.ResumeSessionHandler(log) }
			if err = s.retryParams.Call(); err != nil {
				log.Error(err)
			}
		})

	// Scheduler for resending of data
	s.DataChannel.ResendStreamDataMessageScheduler(log)

	return nil
}

// ProcessFirstMessage only processes messages with PayloadType Output to determine the
// sessionType of the session to be launched. This is a fallback for agent versions that do not support handshake, they
// immediately start sending shell output.
func (s *Session) ProcessFirstMessage(log log.T, outputMessage message.ClientMessage) (isHandlerReady bool, err error) {
	// Immediately deregister self so that this handler is only called once, for the first message
	s.DataChannel.DeregisterOutputStreamHandler(s.ProcessFirstMessage)
	// Only set session type if the session type has not already been set. Usually session type will be set
	// by handshake protocol which would be the first message but older agents may not perform handshake
	if s.SessionType == "" {
		if outputMessage.PayloadType == uint32(message.Output) {
			log.Warn("Setting session type to shell based on PayloadType!")
			s.DataChannel.SetSessionType(config.ShellPluginName)
			s.DisplayMode.DisplayMessage(log, outputMessage)
		}
	}
	return true, nil
}

// Stop will end the session
func (s *Session) Stop() {
	os.Exit(0)
}

// GetResumeSessionParams calls ResumeSession API and gets tokenvalue for reconnecting
func (s *Session) GetResumeSessionParams(log log.T) (string, error) {
	var (
		resumeSessionOutput *ssm.ResumeSessionOutput
		err                 error
		sdkSession          *sdkSession.Session
	)

	if sdkSession, err = sdkutil.GetNewSessionWithEndpoint(s.Endpoint); err != nil {
		return "", err
	}
	s.sdk = ssm.New(sdkSession)
	s.Signer = v4.NewSigner(sdkSession.Config.Credentials)

	resumeSessionInput := ssm.ResumeSessionInput{
		SessionId: &s.SessionId,
	}

	log.Debugf("Resume Session input parameters: %v", resumeSessionInput)
	if resumeSessionOutput, err = s.sdk.ResumeSession(&resumeSessionInput); err != nil {
		log.Errorf("Resume Session failed: %v", err)
		return "", err
	}

	if resumeSessionOutput.TokenValue == nil {
		return "", nil
	}

	return *resumeSessionOutput.TokenValue, nil
}

// ResumeSessionHandler gets token value and tries to Reconnect to datachannel
func (s *Session) ResumeSessionHandler(log log.T) (err error) {
	s.TokenValue, err = s.GetResumeSessionParams(log)
	if err != nil {
		log.Errorf("Failed to get token: %v", err)
		return
	} else if s.TokenValue == "" {
		log.Debugf("Session: %s timed out", s.SessionId)
		fmt.Fprintf(os.Stdout, "Session: %s timed out.\n", s.SessionId)
		os.Exit(0)
	}
	s.DataChannel.GetWsChannel().SetChannelToken(s.TokenValue)
	err = s.DataChannel.Reconnect(log)
	return
}

// TerminateSession calls TerminateSession API
func (s *Session) TerminateSession(log log.T) error {
	var (
		err        error
		newSession *sdkSession.Session
	)

	if newSession, err = sdkutil.GetNewSessionWithEndpoint(s.Endpoint); err != nil {
		log.Errorf("Terminate Session failed: %v", err)
		return err
	}
	s.sdk = ssm.New(newSession)
	s.Signer = v4.NewSigner(newSession.Config.Credentials)

	terminateSessionInput := ssm.TerminateSessionInput{
		SessionId: &s.SessionId,
	}

	log.Debugf("Terminate Session input parameters: %v", terminateSessionInput)
	if _, err = s.sdk.TerminateSession(&terminateSessionInput); err != nil {
		log.Errorf("Terminate Session failed: %v", err)
		return err
	}
	return nil
}
