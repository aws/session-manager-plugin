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
	"bytes"
	"errors"
	"fmt"
	"html/template"
	"strings"

	sdkSession "github.com/aws/aws-sdk-go/aws/session"
	"github.com/aws/aws-sdk-go/service/ssm"
	"github.com/aws/session-manager-plugin/src/datachannel"
	"github.com/aws/session-manager-plugin/src/jsonutil"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/sdkutil"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
	_ "github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/portsession"
	_ "github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/shellsession"
	"github.com/aws/session-manager-plugin/src/ssmclicommands/utils"
	"github.com/twinj/uuid"
)

const (
	START_SESSION = "start-session"
	INSTANCE_ID   = "instance-id"
	REGION        = "region"
	PROFILE       = "profile"
	ENDPOINT      = "endpoint"
	DOCUMENT_NAME = "document-name"
	PARAMETERS    = "parameters"
)

var ParameterKeys = []string{INSTANCE_ID, REGION, PROFILE, ENDPOINT, DOCUMENT_NAME, PARAMETERS}

const START_SESSION_HELP = `NAME : {{.StartSessionName}}

SYNOPSIS:
	{{.SsmCliName}}
	{{.StartSessionName}}
	{{.InstanceId}}
	{{.Region}}

PARAMETERS:
	{{.InstanceId}} (string) InstanceId
	InstanceId is required to start a session

	{{.Region}} (string) Region
	Region is required if not configured in aws config file (https://docs.aws.amazon.com/credref/latest/refdocs/creds-config-files.html)

Command:
      For any region,
      {{.SsmCliName}} {{.StartSessionName}} --{{.InstanceId}} i-123456 --{{.Region}} us-east-1

      For any aws credentials profile,
      {{.SsmCliName}} {{.StartSessionName}} --{{.InstanceId}} i-123456 --{{.Profile}} profile-name

      For any document with parameters,
      {{.SsmCliName}} {{.StartSessionName}} --{{.InstanceId}} i-123456 --{{.DocumentName}} AWS-StartPortForwardingSession --{{.Parameters}}  '{"localPortNumber":["6789"]}'
`

type StartSessionHelpParams struct {
	SsmCliName       string
	StartSessionName string
	InstanceId       string
	Region           string
	Profile          string
	Endpoint         string
	DocumentName     string
	Parameters       string
}

type StartSessionCommand struct {
	helpText string
	sdk      *ssm.SSM
}

// getSSMClient generate ssm client by configuration
var getSSMClient = func(log log.T, region string, profile string, endpoint string) (*ssm.SSM, error) {
	sdkutil.SetRegionAndProfile(region, profile)

	var sdkSession *sdkSession.Session
	sdkSession, err := sdkutil.GetNewSessionWithEndpoint(endpoint)
	if err != nil {
		log.Errorf("Get session with endpoint Failed: %v", err)
		return nil, err
	}
	return ssm.New(sdkSession), nil
}

// executeSession to open datachannel
var executeSession = func(log log.T, session *session.Session) (err error) {
	return session.Execute(log)
}

// startSession trigger a sdk start session call.
var startSession = func(s *StartSessionCommand, input *ssm.StartSessionInput) (*ssm.StartSessionOutput, error) {
	return s.sdk.StartSession(input)
}

func init() {
	utils.Register(&StartSessionCommand{})
}

// Name is the command name used in the cli
func (StartSessionCommand) Name() string {
	return START_SESSION
}

// Help prints help for the start-session cli command
func (c *StartSessionCommand) Help() string {
	if len(c.helpText) == 0 {
		t, _ := template.New("StartSessionHelp").Parse(START_SESSION_HELP)
		params := StartSessionHelpParams{
			utils.SsmCliName,
			START_SESSION,
			INSTANCE_ID,
			REGION,
			PROFILE,
			ENDPOINT,
			DOCUMENT_NAME,
			PARAMETERS,
		}
		buf := new(bytes.Buffer)
		t.Execute(buf, params)
		c.helpText = buf.String()
	}
	return c.helpText
}

// validates and execute start-session command
func (s *StartSessionCommand) Execute(parameters map[string][]string) (error, string) {
	var (
		err        error
		region     string
		profile    string
		endpoint   string
		instanceId string
	)
	validation := s.validateStartSessionInput(parameters)
	if len(validation) > 0 {
		return errors.New(strings.Join(validation, "\n")), ""
	}

	log := log.Logger(true, "ssmcli")

	if parameters[REGION] != nil {
		region = parameters[REGION][0]
	}

	if parameters[PROFILE] != nil {
		profile = parameters[PROFILE][0]
	}
	if parameters[ENDPOINT] != nil {
		endpoint = parameters[ENDPOINT][0]
	}
	if parameters[INSTANCE_ID] != nil {
		instanceId = parameters[INSTANCE_ID][0]
	}

	if s.sdk, err = getSSMClient(log, region, profile, endpoint); err != nil {
		return err, "StartSession failed"
	}

	log.Infof("Calling StartSession API with parameters: %v", parameters)
	sessionId, tokenValue, streamUrl, err := s.getStartSessionParams(log, parameters)
	if err != nil {
		log.Errorf("Error in getting start awsSession params: %v", err)
		return err, "StartSession failed"
	}
	log.Infof("For SessionId: %s, StartSession returned streamUrl: %s", sessionId, streamUrl)
	clientId := uuid.NewV4().String()

	session := session.Session{
		SessionId:   sessionId,
		StreamUrl:   streamUrl,
		TokenValue:  tokenValue,
		Endpoint:    endpoint,
		ClientId:    clientId,
		TargetId:    instanceId,
		DataChannel: &datachannel.DataChannel{},
	}

	if err = executeSession(log, &session); err != nil {
		log.Errorf("Cannot perform start session: %v", err)
		return err, "StartSession failed"
	}

	return err, "StartSession executed successfully"
}

// func to validate start-session input
func (StartSessionCommand) validateStartSessionInput(parameters map[string][]string) []string {
	validation := make([]string, 0)

	instanceIdValue := parameters[INSTANCE_ID]

	//look for required parameters
	if instanceIdValue == nil {
		validation = append(validation, fmt.Sprintf("%v is required",
			utils.FormatFlag(INSTANCE_ID)))
	}

	for key := range parameters {
		if !contains(ParameterKeys, key) {
			validation = append(validation, fmt.Sprintf("%v not a valid command parameter flag", key))
		}
	}

	return validation
}

func contains(arr []string, item string) bool {
	for _, v := range arr {
		if v == item {
			return true
		}
	}
	return false
}

// function to get start-session parameters
func (s *StartSessionCommand) getStartSessionParams(log log.T, parameters map[string][]string) (string, string, string, error) {
	//Fetch command token
	uuid.SwitchFormat(uuid.CleanHyphen)

	startSessionInput := ssm.StartSessionInput{
		Target: &parameters[INSTANCE_ID][0],
	}

	if parameters[DOCUMENT_NAME] != nil {
		startSessionInput.DocumentName = &parameters[DOCUMENT_NAME][0]
	}

	delete(parameters, INSTANCE_ID)
	delete(parameters, DOCUMENT_NAME)
	delete(parameters, REGION)

	if parameters["parameters"] != nil && len(parameters["parameters"]) == 1 {

		userParameters := make(map[string][]*string)
		params := make(map[string][]string)

		if err := jsonutil.Unmarshal(parameters[PARAMETERS][0], &params); err != nil {
			return "", "", "", fmt.Errorf("%v not valid input, get error: %v", PARAMETERS, err)
		}

		for k, v := range params {
			values := make([]*string, len(v))
			for index, element := range v {
				value := element
				values[index] = &value
			}
			userParameters[k] = values
		}

		startSessionInput.Parameters = userParameters
	}

	log.Infof("StartSession input parameters: %v", startSessionInput)
	startSessionOutput, err := startSession(s, &startSessionInput)
	if err != nil {
		log.Errorf("StartSession Failed: %v", err)
		return "", "", "", fmt.Errorf("start session failed with error: %v", err)
	}

	sessionId := startSessionOutput.SessionId
	tokenValue := startSessionOutput.TokenValue
	streamUrl := startSessionOutput.StreamUrl

	if tokenValue == nil || sessionId == nil || streamUrl == nil {
		return "", "", "", fmt.Errorf("token value or sessionId or streamUrl should not be empty. \n")
	}

	return *sessionId, *tokenValue, *streamUrl, err
}
