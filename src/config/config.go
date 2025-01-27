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

// config package implement configuration retrieval for session manager apis
package config

import "time"

const (
	ServiceName                        = "ssmmessages"
	RolePublishSubscribe               = "publish_subscribe"
	MessageSchemaVersion               = "1.0"
	DefaultTransmissionTimeout         = 200 * time.Millisecond
	DefaultRoundTripTime               = 100 * time.Millisecond
	DefaultRoundTripTimeVariation      = 0
	ResendSleepInterval                = 100 * time.Millisecond
	ResendMaxAttempt                   = 3000 // 5 minutes / ResendSleepInterval
	StreamDataPayloadSize              = 1024
	OutgoingMessageBufferCapacity      = 10000
	IncomingMessageBufferCapacity      = 10000
	RTTConstant                        = 1.0 / 8.0 // Round trip time constant
	RTTVConstant                       = 1.0 / 4.0 // Round trip time variation constant
	ClockGranularity                   = 10 * time.Millisecond
	MaxTransmissionTimeout             = 1 * time.Second
	RetryBase                          = 2
	DataChannelNumMaxRetries           = 5
	DataChannelRetryInitialDelayMillis = 100
	DataChannelRetryMaxIntervalMillis  = 5000
	RetryAttempt                       = 5
	PingTimeInterval                   = 5 * time.Minute

	// Plugin names
	ShellPluginName                  = "Standard_Stream"
	PortPluginName                   = "Port"
	InteractiveCommandsPluginName    = "InteractiveCommands"
	NonInteractiveCommandsPluginName = "NonInteractiveCommands"

	//Agent Versions
	TerminateSessionFlagSupportedAfterThisAgentVersion            = "2.3.722.0"
	TCPMultiplexingSupportedAfterThisAgentVersion                 = "3.0.196.0"
	TCPMultiplexingWithSmuxKeepAliveDisabledAfterThisAgentVersion = "3.1.1511.0"
)
