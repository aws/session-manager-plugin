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

// Package ssmclicommands contains all the commands with its implementation.
package ssmclicommands

import (
	"fmt"
	"testing"

	"github.com/aws/aws-sdk-go/service/ssm"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"

	"github.com/stretchr/testify/assert"
)

var (
	sessionId          = "session_id"
	streamUrl          = "wss://stream_url"
	tokenValue         = "Token_value_123456"
	startSessionOutput = &ssm.StartSessionOutput{
		SessionId:  &sessionId,
		StreamUrl:  &streamUrl,
		TokenValue: &tokenValue,
	}
)

func TestStartSessionCommand_Help(t *testing.T) {
	command := &StartSessionCommand{
		helpText: "StartSessionCommand Help Context",
	}
	output := command.Help()
	assert.Equal(t, output, "StartSessionCommand Help Context")

	command.helpText = ""
	output = command.Help()
	assert.Contains(t, output, "SYNOPSIS:")
}

func TestStartSessionCommand_ExecuteSuccess(t *testing.T) {
	parameter, _ := getCommandParameter()
	parameter[DOCUMENT_NAME] = []string{"AWS-StartPortForwardingSession"}
	parameter[PARAMETERS] = []string{"{\"portNumber\":[\"80\"],\"localPortNumber\":[\"6789\"]}"}
	command := &StartSessionCommand{
		helpText: "StartSessionCommand Help Context",
	}
	getSSMClient = func(log log.T, region string, profile string, endpoint string) (*ssm.SSM, error) {
		assert.Equal(t, region, "us-east-1")
		assert.Empty(t, profile)
		ssmClient := &ssm.SSM{}
		return ssmClient, nil
	}

	executeSession = func(log log.T, session *session.Session) (err error) {
		assert.NotNil(t, session.DataChannel)
		return nil
	}

	startSession = func(s *StartSessionCommand, input *ssm.StartSessionInput) (*ssm.StartSessionOutput, error) {
		assert.Equal(t, *input.Target, "i-123456")
		assert.Equal(t, *input.Parameters["portNumber"][0], "80")
		assert.Equal(t, *input.Parameters["localPortNumber"][0], "6789")
		assert.Equal(t, *input.DocumentName, "AWS-StartPortForwardingSession")
		return startSessionOutput, nil
	}

	err, msg := command.Execute(parameter)
	assert.Nil(t, err)
	assert.Equal(t, msg, "StartSession executed successfully")
}

func TestStartSessionCommand_ExecuteGetSSMClientFailure(t *testing.T) {
	parameter, _ := getCommandParameter()
	parameter[PROFILE] = []string{"user1"}
	command := &StartSessionCommand{
		helpText: "StartSessionCommand Help Context",
	}
	getSSMClient = func(log log.T, region string, profile string, endpoint string) (*ssm.SSM, error) {
		assert.Equal(t, profile, "user1")
		return nil, fmt.Errorf("Get SSMClient Failure")
	}

	err, msg := command.Execute(parameter)
	assert.NotNil(t, err)
	assert.Equal(t, err.Error(), "Get SSMClient Failure")
	assert.Equal(t, msg, "StartSession failed")
}

func TestStartSessionCommand_ExecuteSessionFailure(t *testing.T) {
	parameter, _ := getCommandParameter()
	command := &StartSessionCommand{
		helpText: "StartSessionCommand Help Context",
	}
	getSSMClient = func(log log.T, region string, profile string, endpoint string) (*ssm.SSM, error) {
		ssmClient := &ssm.SSM{}
		return ssmClient, nil
	}

	executeSession = func(log log.T, session *session.Session) (err error) {
		return fmt.Errorf("Execute Session Failure")
	}

	startSession = func(s *StartSessionCommand, input *ssm.StartSessionInput) (*ssm.StartSessionOutput, error) {
		return startSessionOutput, nil
	}

	err, msg := command.Execute(parameter)
	assert.NotNil(t, err)
	assert.Equal(t, err.Error(), "Execute Session Failure")
	assert.Equal(t, msg, "StartSession failed")
}

func TestStartSessionCommand_validateStartSessionInput(t *testing.T) {
	parameter, _ := getCommandParameter()
	command := &StartSessionCommand{}
	validation := command.validateStartSessionInput(parameter)
	assert.Equal(t, len(validation), 0)
}

func TestStartSessionCommand_validateStartSessionInputWithoutRequiredParameters(t *testing.T) {
	parameters := map[string][]string{INSTANCE_ID: nil}
	command := &StartSessionCommand{}
	validation := command.validateStartSessionInput(parameters)
	assert.Equal(t, len(validation), 1)
	assert.Equal(t, validation[0], "--instance-id is required")
}

func TestStartSessionCommand_validateStartSessionInputWithInvalidParameters(t *testing.T) {

	parameters := map[string][]string{INSTANCE_ID: nil, "random-params": nil}
	command := &StartSessionCommand{}
	validation := command.validateStartSessionInput(parameters)
	assert.Equal(t, len(validation), 2)
	assert.Equal(t, validation[0], "--instance-id is required")
	assert.Equal(t, validation[1], "random-params not a valid command parameter flag")
}

func TestStartSessionCommand_getStartSessionParams(t *testing.T) {
	parameters, _ := getCommandParameter()
	command := &StartSessionCommand{}
	log := log.NewMockLog()
	startSession = func(s *StartSessionCommand, input *ssm.StartSessionInput) (*ssm.StartSessionOutput, error) {
		return startSessionOutput, nil
	}

	id, token, url, err := command.getStartSessionParams(log, parameters)
	assert.Nil(t, err)
	assert.Equal(t, id, sessionId)
	assert.Equal(t, token, tokenValue)
	assert.Equal(t, url, streamUrl)
}

func TestStartSessionCommand_getStartSessionParamsWithStartSessionFailure(t *testing.T) {
	parameters, _ := getCommandParameter()
	command := &StartSessionCommand{}
	log := log.NewMockLog()
	startSession = func(s *StartSessionCommand, input *ssm.StartSessionInput) (*ssm.StartSessionOutput, error) {
		return nil, fmt.Errorf("SendStartSession Failure")
	}

	id, token, url, err := command.getStartSessionParams(log, parameters)
	assert.NotNil(t, err)
	assert.Equal(t, err.Error(), "start session failed with error: SendStartSession Failure")
	assert.Empty(t, id)
	assert.Empty(t, token)
	assert.Empty(t, url)
}

func TestStartSessionCommand_getStartSessionParamsWithNilOutput(t *testing.T) {
	parameters, _ := getCommandParameter()
	command := &StartSessionCommand{}
	log := log.NewMockLog()
	output := &ssm.StartSessionOutput{}
	startSession = func(s *StartSessionCommand, input *ssm.StartSessionInput) (*ssm.StartSessionOutput, error) {
		return output, nil
	}

	id, token, url, err := command.getStartSessionParams(log, parameters)
	assert.NotNil(t, err)
	assert.Contains(t, err.Error(), "token value or sessionId or streamUrl should not be empty.")
	assert.Empty(t, id)
	assert.Empty(t, token)
	assert.Empty(t, url)
}

func getCommandParameter() (parameters map[string][]string, err error) {
	args := []string{1: "start-session", 2: "--instance-id", 3: "i-123456", 4: "--region", 5: "us-east-1"}
	err, _, _, _, parameter := ParseCliCommand(args)
	return parameter, err
}
