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
	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
)

// DoesAgentSupportTCPMultiplexing returns true if given agentVersion supports TCP multiplexing in port plugin, false otherwise
func DoesAgentSupportTCPMultiplexing(log log.T, agentVersion string) (supported bool) {
	return isAgentVersionGreaterThanSupportedVersion(log, agentVersion, config.TCPMultiplexingSupportedAfterThisAgentVersion)
}

// DoesAgentSupportDisableSmuxKeepAlive returns true if given agentVersion disables smux KeepAlive in TCP multiplexing in port plugin, false otherwise
func DoesAgentSupportDisableSmuxKeepAlive(log log.T, agentVersion string) (supported bool) {
	return isAgentVersionGreaterThanSupportedVersion(log, agentVersion, config.TCPMultiplexingWithSmuxKeepAliveDisabledAfterThisAgentVersion)
}

// DoesAgentSupportTerminateSessionFlag returns true if given agentVersion supports TerminateSession flag, false otherwise
func DoesAgentSupportTerminateSessionFlag(log log.T, agentVersion string) (supported bool) {
	return isAgentVersionGreaterThanSupportedVersion(log, agentVersion, config.TerminateSessionFlagSupportedAfterThisAgentVersion)
}

// isAgentVersionGreaterThanSupportedVersion returns true if agentVersion is greater than supportedVersion,
// false in case of any error and agentVersion is equalTo or less than supportedVersion
func isAgentVersionGreaterThanSupportedVersion(log log.T, agentVersionString string, supportedVersionString string) (supported bool) {
	var (
		supportedVersion version
		agentVersion     version
		compareResult    int
		err              error
	)
	if supportedVersion, err = NewVersion(supportedVersionString); err != nil {
		log.Debugf("supportedVersion initialization failed, %v", err)
		return
	}

	if agentVersion, err = NewVersion(agentVersionString); err != nil {
		log.Debugf("agentVersion initialization failed, %v", err)
		return
	}

	if compareResult, err = agentVersion.compare(supportedVersion); err != nil {
		log.Debugf("version comparison failed, %v", err)
		return
	}

	if compareResult == 1 {
		supported = true
	}
	return
}
