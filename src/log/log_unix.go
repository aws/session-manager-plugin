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

//go:build darwin || freebsd || linux || netbsd || openbsd
// +build darwin freebsd linux netbsd openbsd

// Package log is used to initialize logger
package log

import (
	"fmt"
	"io/ioutil"
	"path/filepath"
)

const (
	LogsDirectory                = "logs"
	DefaultInstallLocationPrefix = "/usr/local"
)

func getApplicationName(clientName string) string {
	var applicationName string
	if clientName == "ssmcli" {
		applicationName = "SSMCLI"
	} else if clientName == "session-manager-plugin" {
		applicationName = "sessionmanagerplugin"
	}

	return applicationName
}

// getLogConfigBytes reads and returns the seelog configs from the config file path if present
// otherwise returns the seelog default configurations
// Linux uses seelog.xml file as configuration by default.
func getLogConfigBytes(clientName string) (logConfigBytes []byte) {

	applicationName := getApplicationName(clientName)
	DefaultSeelogConfigFilePath = filepath.Join(DefaultInstallLocationPrefix, applicationName, SeelogConfigFileName)
	DefaultLogDir = filepath.Join(DefaultInstallLocationPrefix, applicationName, LogsDirectory)
	ApplicationLogFile = fmt.Sprintf("%s%s", clientName, LogFileExtension)
	ErrorLogFile = fmt.Sprintf("%s%s", ErrorLogFileSuffix, LogFileExtension)
	if logConfigBytes, err = ioutil.ReadFile(DefaultSeelogConfigFilePath); err != nil {
		logConfigBytes = DefaultConfig()
	}
	return
}
