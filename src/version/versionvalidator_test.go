// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

// Package version contains version constants and utilities.
package version

import (
	"testing"

	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/stretchr/testify/assert"
)

type Comparison struct {
	agentVersion     string
	supportedVersion string
}

var mockLog = log.NewMockLog()

func TestDoesAgentSupportTCPMultiplexing(t *testing.T) {
	// Test exact version of feature; TCPMultiplexingSupported after 3.0.196.0
	assert.False(t, DoesAgentSupportTCPMultiplexing(mockLog, config.TCPMultiplexingSupportedAfterThisAgentVersion))

	// Test versions prior to feature implementation
	oldVersions := []string{
		"1.2.3.4",
		"2.3.4.5",
		"3.0.195.100",
		"2.99.1000.0",
	}
	for _, tc := range oldVersions {
		assert.False(t, DoesAgentSupportTCPMultiplexing(mockLog, tc))
	}

	// Test versions after feature implementation
	newVersions := []string{
		"3.1.0.0",
		"3.0.197.0",
		"3.0.196.1",
		"4.0.0.0",
	}
	for _, tc := range newVersions {
		assert.True(t, DoesAgentSupportTCPMultiplexing(mockLog, tc))
	}
}

func TestDoesAgentSupportTerminateSessionFlag(t *testing.T) {
	// Test exact version of feature; TerminateSessionFlag supported after 2.3.722.0
	assert.False(t, DoesAgentSupportTerminateSessionFlag(mockLog, config.TerminateSessionFlagSupportedAfterThisAgentVersion))

	// Test versions prior to feature implementation
	oldVersions := []string{
		"1.2.3.4",
		"2.3.4.5",
		"2.3.721.100",
		"0.3.1000.0",
	}
	for _, tc := range oldVersions {
		assert.False(t, DoesAgentSupportTerminateSessionFlag(mockLog, tc))
	}

	// Test versions after feature implementation
	newVersions := []string{
		"3.1.0.0",
		"3.0.197.0",
		"2.3.723.0",
		"4.0.0.0",
	}
	for _, tc := range newVersions {
		assert.True(t, DoesAgentSupportTerminateSessionFlag(mockLog, tc))
	}
}

func TestIsAgentVersionGreaterThanSupportedVersionWithNormalInputs(t *testing.T) {
	const (
		defaultSupportedVersion = "3.0.0.0"
	)

	// Test normal inputs where agentVersion <= supportedVersion
	normalNegativeCases := []Comparison{
		{"3.0.0.0", defaultSupportedVersion},
		{"1.2.3.4", defaultSupportedVersion},
		{"2.99.99.99", defaultSupportedVersion},
		{"3.4.5.2", "3.4.5.2"},
	}
	for _, tc := range normalNegativeCases {
		assert.False(t, isAgentVersionGreaterThanSupportedVersion(mockLog, tc.agentVersion, tc.supportedVersion))
	}

	// Test normal inputs where agentVersion > supportedVersion
	normalPositiveCases := []Comparison{
		{"3.0.0.1", defaultSupportedVersion},
		{"4.0.0.0", defaultSupportedVersion},
		{"3.1.0.0", defaultSupportedVersion},
		{"3.0.100.0", defaultSupportedVersion},
		{"5.0.0.2", "5.0.0.0"},
	}
	for _, tc := range normalPositiveCases {
		assert.True(t, isAgentVersionGreaterThanSupportedVersion(mockLog, tc.agentVersion, tc.supportedVersion))
	}
}

func TestIsAgentVersionGreaterThanSupportedVersionEdgeCases(t *testing.T) {
	// Test non-numeric strings
	t.Run("Non-numeric strings", func(t *testing.T) {
		errorLog := log.NewMockLog()
		notNumberCase := Comparison{"randomString", "randomString"}
		assert.False(t, isAgentVersionGreaterThanSupportedVersion(errorLog, notNumberCase.agentVersion, notNumberCase.supportedVersion))
	})
	t.Run("Uneven-length strings", func(t *testing.T) {
		errorLog := log.NewMockLog()
		unevenLengthCase := Comparison{"1.4.1.2.4.1", "3.0.0.0"}
		assert.False(t, isAgentVersionGreaterThanSupportedVersion(errorLog, unevenLengthCase.agentVersion, unevenLengthCase.supportedVersion))
	})
	t.Run("Invalid Version Numbers", func(t *testing.T) {
		errorLog := log.NewMockLog()
		invalidVersionNumberCases := []Comparison{
			{"", "3.0.0.0"},
			{"3.0.0.0", ""},
			{"3,0.0.0", "3.0.2.0"},
		}
		for _, tc := range invalidVersionNumberCases {
			assert.False(t, isAgentVersionGreaterThanSupportedVersion(errorLog, tc.agentVersion, tc.supportedVersion))
		}
	})
}

func TestDoesAgentSupportTerminateSessionFlagForSupportedScenario(t *testing.T) {
	assert.True(t, DoesAgentSupportTerminateSessionFlag(mockLog, "2.3.750.0"))
}

func TestDoesAgentSupportTerminateSessionFlagForNotSupportedScenario(t *testing.T) {
	assert.False(t, DoesAgentSupportTerminateSessionFlag(mockLog, "2.3.614.0"))
}

func TestDoesAgentSupportTerminateSessionFlagWhenAgentVersionIsEqualSupportedAfterVersion(t *testing.T) {
	assert.False(t, DoesAgentSupportTerminateSessionFlag(mockLog, "2.3.722.0"))
}

func TestDoesAgentSupportDisableSmuxKeepAliveForNotSupportedScenario(t *testing.T) {
	assert.False(t, DoesAgentSupportDisableSmuxKeepAlive(mockLog, "3.1.1476.0"))
}

func TestDoesAgentSupportDisableSmuxKeepAliveForSupportedScenario(t *testing.T) {
	assert.True(t, DoesAgentSupportDisableSmuxKeepAlive(mockLog, "3.1.1600.0"))
}
