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
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	v4 "github.com/aws/aws-sdk-go-v2/aws/signer/v4"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/datachannel"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/retry"
	"github.com/aws/session-manager-plugin/src/sdkutil"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/sessionutil"
	"github.com/aws/session-manager-plugin/src/version"
	"github.com/twinj/uuid"
)

const (
	LegacyArgumentLength  = 4
	ArgumentLength        = 7
	StartSessionOperation = "StartSession"
	VersionFile           = "VERSION"
)

var SessionRegistry = map[string]ISessionPlugin{}

type ISessionPlugin interface {
	SetSessionHandlers(log.T) error
	ProcessStreamMessagePayload(log log.T, streamDataMessage message.ClientMessage) (isHandlerReady bool, err error)
	Initialize(log log.T, sessionVar *Session)
	Stop()
	Name() string
}

type ISession interface {
	Execute(log.T) error
	OpenDataChannel(log.T) error
	ProcessFirstMessage(log log.T, outputMessage message.ClientMessage) (isHandlerReady bool, err error)
	Stop()
	GetResumeSessionParams(log.T) (string, error)
	ResumeSessionHandler(log.T) error
	TerminateSession(log.T) error
}

func init() {
	SessionRegistry = make(map[string]ISessionPlugin)
}

func Register(session ISessionPlugin) {
	SessionRegistry[session.Name()] = session
}

type Session struct {
	DataChannel           datachannel.IDataChannel
	SessionId             string
	StreamUrl             string
	TokenValue            string
	IsAwsCliUpgradeNeeded bool
	Endpoint              string
	ClientId              string
	TargetId              string
	sdk                   *ssm.Client
	retryParams           retry.RepeatableExponentialRetryer
	SessionType           string
	SessionProperties     interface{}
	DisplayMode           sessionutil.DisplayMode
	Region                string
	Signer                *v4.Signer
	Credentials           aws.Credentials
}

// startSession create the datachannel for session
var startSession = func(session *Session, log log.T) error {
	return session.Execute(log)
}

// setSessionHandlersWithSessionType set session handlers based on session subtype
var setSessionHandlersWithSessionType = func(session *Session, log log.T) error {
	// SessionType is set inside DataChannel
	sessionSubType := SessionRegistry[session.SessionType]
	sessionSubType.Initialize(log, session)
	return sessionSubType.SetSessionHandlers(log)
}

// Set up a scheduler to listen on stream data resend timeout event
var handleStreamMessageResendTimeout = func(session *Session, log log.T) {
	log.Tracef("Setting up scheduler to listen on IsStreamMessageResendTimeout event.")
	go func() {
		for {
			// Repeat this loop for every 200ms
			time.Sleep(config.ResendSleepInterval)
			if <-session.DataChannel.IsStreamMessageResendTimeout() {
				log.Errorf("Terminating session %s as the stream data was not processed before timeout.", session.SessionId)
				if err := session.TerminateSession(log); err != nil {
					log.Errorf("Unable to terminate session upon stream data timeout. %v", err)
				}
				return
			}
		}
	}()
}

// ValidateInputAndStartSession validates input sent from AWS CLI and starts a session if validation is successful.
// AWS CLI sends input in the order of
// args[0] will be path of executable (ignored)
// args[1] is session response
// args[2] is client region
// args[3] is operation name
// args[4] is profile name from aws credentials/config files
// args[5] is parameters input to aws cli for StartSession api
// args[6] is endpoint for ssm service
func ValidateInputAndStartSession(args []string, out io.Writer) {
	var (
		err                error
		session            Session
		startSessionOutput ssm.StartSessionOutput
		response           []byte
		region             string
		operationName      string
		profile            string
		ssmEndpoint        string
		target             string
	)
	log := log.Logger(true, "session-manager-plugin")
	uuid.SwitchFormat(uuid.CleanHyphen)

	if len(args) == 1 {
		fmt.Fprint(out, "\nThe Session Manager plugin was installed successfully. "+
			"Use the AWS CLI to start a session.\n\n")
		return
	} else if len(args) == 2 && args[1] == "--version" {
		fmt.Fprintf(out, "%s\n", string(version.Version))
		return
	} else if len(args) >= 2 && len(args) < LegacyArgumentLength {
		fmt.Fprintf(out, "\nUnknown operation %s. \nUse "+
			"session-manager-plugin --version to check the version.\n\n", string(args[1]))
		return

	} else if len(args) == LegacyArgumentLength {
		// If arguments do not have Profile passed from AWS CLI to Session-Manager-Plugin then
		// should be upgraded to use Session Manager encryption feature
		session.IsAwsCliUpgradeNeeded = true
	}

	for argsIndex := 1; argsIndex < len(args); argsIndex++ {
		switch argsIndex {
		case 1:
			if strings.HasPrefix(args[1], "AWS_SSM_START_SESSION_RESPONSE") == true {
				response = []byte(os.Getenv(args[1]))
				if err = os.Unsetenv(args[1]); err != nil {
					log.Errorf("Failed to remove temporary session env parameter: %v", err)
				}
			} else {
				response = []byte(args[1])
			}
		case 2:
			region = args[2]
		case 3:
			operationName = args[3]
		case 4:
			profile = args[4]
		case 5:
			// args[5] is parameters input to aws cli for StartSession api call
			startSessionRequest := make(map[string]interface{})
			json.Unmarshal([]byte(args[5]), &startSessionRequest)
			target = startSessionRequest["Target"].(string)
		case 6:
			ssmEndpoint = args[6]
		}
	}
	sdkutil.SetRegionAndProfile(region, profile)
	clientId := uuid.NewV4().String()

	switch operationName {
	case StartSessionOperation:
		if err = json.Unmarshal(response, &startSessionOutput); err != nil {
			log.Errorf("Cannot perform start session: %v", err)
			fmt.Fprintf(out, "Cannot perform start session: %v\n", err)
			return
		}

		if startSessionOutput.SessionId == nil || startSessionOutput.StreamUrl == nil ||
			startSessionOutput.TokenValue == nil {
			log.Error("Cannot perform start session: StartSession response is missing SessionId, StreamUrl or TokenValue")
			fmt.Fprint(out, "Cannot perform start session: StartSession response is missing required fields\n")
			return
		}

		session.SessionId = *startSessionOutput.SessionId
		session.StreamUrl = *startSessionOutput.StreamUrl
		session.TokenValue = *startSessionOutput.TokenValue
		session.Endpoint = ssmEndpoint
		session.ClientId = clientId
		session.TargetId = target
		session.Region = region
		session.DataChannel = &datachannel.DataChannel{}

		if err = validateStreamUrl(session.StreamUrl, session.Region, session.Endpoint); err != nil {
			log.Errorf("Cannot perform start session, invalid StreamUrl: %v", err)
			fmt.Fprintf(out, "Cannot perform start session: %v\n", err)
			return
		}
		if strings.TrimSpace(session.TokenValue) == "" {
			log.Error("Cannot perform start session: TokenValue is empty")
			fmt.Fprint(out, "Cannot perform start session: TokenValue is empty\n")
			return
		}

	default:
		fmt.Fprint(out, "Invalid Operation")
		return
	}

	if err = startSession(&session, log); err != nil {
		log.Errorf("Cannot perform start session: %v", err)
		fmt.Fprintf(out, "Unable to establish session connection: %v\n", err)
		return
	}
}

const ssmMessagesServiceName = "ssmmessages"

// validateStreamUrl checks that streamUrl is a wss URL for the client's region on an AWS-owned host.
func validateStreamUrl(streamUrl string, region string, ssmEndpoint string) error {
	if strings.TrimSpace(streamUrl) == "" {
		return errors.New("StreamUrl is empty")
	}
	if strings.TrimSpace(region) == "" {
		return errors.New("client region is empty, cannot validate StreamUrl host")
	}

	parsedUrl, err := url.Parse(streamUrl)
	if err != nil {
		return fmt.Errorf("StreamUrl is not a valid URL: %v", err)
	}
	if !strings.EqualFold(parsedUrl.Scheme, "wss") {
		return fmt.Errorf("StreamUrl has unexpected scheme %q, expected \"wss\"", parsedUrl.Scheme)
	}
	if parsedUrl.User != nil {
		return errors.New("StreamUrl must not contain userinfo")
	}

	host := strings.TrimSuffix(strings.ToLower(parsedUrl.Hostname()), ".")
	if host == "" {
		return errors.New("StreamUrl has no host")
	}
	region = strings.ToLower(region)

	if !hasServiceRegionLabels(host, region) {
		return fmt.Errorf("StreamUrl host %q is not an ssmmessages endpoint for region %q", host, region)
	}
	for _, suffix := range trustedDnsSuffixes(region, ssmEndpoint) {
		if suffix != "" && (host == suffix || strings.HasSuffix(host, "."+suffix)) {
			return nil
		}
	}
	return fmt.Errorf("StreamUrl host %q is not under a recognized AWS DNS suffix", host)
}

// hasServiceRegionLabels reports whether host has an ssmmessages[-fips] label followed by the region label.
func hasServiceRegionLabels(host, region string) bool {
	labels := strings.Split(host, ".")
	for i := 0; i+1 < len(labels); i++ {
		if (labels[i] == ssmMessagesServiceName || labels[i] == ssmMessagesServiceName+"-fips") &&
			labels[i+1] == region {
			return true
		}
	}
	return false
}

// trustedDnsSuffixes returns the AWS DNS suffixes a StreamUrl host may end with, taken from the ssm
// endpoint the CLI passed and the SDK's standard and dual-stack endpoints for the region.
func trustedDnsSuffixes(region, ssmEndpoint string) []string {
	suffixes := []string{resolvedSsmDnsSuffix(region, false), resolvedSsmDnsSuffix(region, true)}
	if s := dnsSuffixFromSsmEndpoint(ssmEndpoint, region); s != "" {
		suffixes = append(suffixes, s)
	}
	return suffixes
}

// resolvedSsmDnsSuffix returns the DNS suffix of the SDK-resolved ssm endpoint for region, or "".
func resolvedSsmDnsSuffix(region string, dualStack bool) string {
	endpoint, err := ssm.NewDefaultEndpointResolverV2().ResolveEndpoint(context.Background(),
		ssm.EndpointParameters{Region: aws.String(region), UseDualStack: aws.Bool(dualStack)})
	if err != nil {
		return ""
	}
	return dnsSuffixFromHost(endpoint.URI.Hostname(), region)
}

// dnsSuffixFromSsmEndpoint returns the DNS suffix of an ssm endpoint URL, or "".
func dnsSuffixFromSsmEndpoint(ssmEndpoint string, region string) string {
	if strings.TrimSpace(ssmEndpoint) == "" {
		return ""
	}
	host := ssmEndpoint
	if parsed, err := url.Parse(ssmEndpoint); err == nil && parsed.Host != "" {
		host = parsed.Hostname()
	}
	return dnsSuffixFromHost(host, region)
}

// dnsSuffixFromHost strips the ssm[-fips] and region labels from an ssm host, leaving the DNS suffix.
func dnsSuffixFromHost(host, region string) string {
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	for _, servicePrefix := range []string{"ssm-fips.", "ssm."} {
		if strings.HasPrefix(host, servicePrefix) {
			host = host[len(servicePrefix):]
			break
		}
	}
	host = strings.TrimPrefix(host, strings.ToLower(region)+".")
	if host == "" || strings.ContainsAny(host, "/:@") {
		return ""
	}
	return host
}

// Execute create data channel and start the session
func (s *Session) Execute(log log.T) (err error) {
	fmt.Fprintf(os.Stdout, "\nStarting session with SessionId: %s\n", s.SessionId)

	// sets the display mode
	s.DisplayMode = sessionutil.NewDisplayMode(log)

	if err = s.OpenDataChannel(log); err != nil {
		log.Errorf("Error in Opening data channel: %v", err)
		return
	}

	handleStreamMessageResendTimeout(s, log)

	// The session type is set either by handshake or the first packet received.
	// Add timeout to avoid hanging indefinitely on authorization failures
	select {
	case sessionTypeSet := <-s.DataChannel.IsSessionTypeSet():
		if !sessionTypeSet {
			log.Errorf("unable to set SessionType for session %s", s.SessionId)
			return errors.New("unable to determine SessionType")
		}
		s.SessionType = s.DataChannel.GetSessionType()
		s.SessionProperties = s.DataChannel.GetSessionProperties()
		if err = setSessionHandlersWithSessionType(s, log); err != nil {
			log.Errorf("Session ending with error: %v", err)
			return
		}
	case <-time.After(30 * time.Second):
		err = errors.New("Session creation timed out.")
		log.Errorf("Session %s: %v", s.SessionId, err)
		return err
	}

	return
}
