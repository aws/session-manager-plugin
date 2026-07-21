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

//go:build windows
// +build windows

// Package log is used to initialize logger
package log

import (
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
)

const (
	// ApplicationFolder is the path under local app data.
	ApplicationFolderPrefix = "Amazon\\"
	LogsDirectory           = "Logs"
)

var EnvProgramFiles = os.Getenv("ProgramFiles") // Windows environment variable %ProgramFiles%

func getApplicationName(clientName string) string {
	var applicationName string
	if clientName == "ssmcli" {
		applicationName = "SSMCLI"
	} else if clientName == "session-manager-plugin" {
		applicationName = "SessionManagerPlugin"
	}

	return applicationName
}

// getExecutableDir returns the directory containing the currently running executable.
// This is a variable so it can be overridden in tests.
var getExecutableDir = func() (string, error) {
	exePath, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.Dir(exePath), nil
}

// getLogConfigBytes reads and returns the seelog configs from the config file path if present
// otherwise returns the seelog default configurations
// Windows uses default log configuration if there is no seelog.xml override provided.
//
// Lookup order:
//  1. seelog.xml in the same directory as the running executable. This allows
//     users who copy the binary to a directory they own (instead of using the
//     official installer under %ProgramFiles%) to configure logging without
//     requiring administrator permissions.
//  2. seelog.xml under the default %ProgramFiles% install location.
//  3. Built-in default configuration.
func getLogConfigBytes(clientName string) (logConfigBytes []byte) {
	ApplicationLogFile = fmt.Sprintf("%s%s", clientName, LogFileExtension)
	ErrorLogFile = fmt.Sprintf("%s%s", ErrorLogFileSuffix, LogFileExtension)

	if binDir, dirErr := getExecutableDir(); dirErr == nil {
		binSeelogConfigFilePath := filepath.Join(binDir, SeelogConfigFileName)
		if bytes, readErr := ioutil.ReadFile(binSeelogConfigFilePath); readErr == nil {
			DefaultSeelogConfigFilePath = binSeelogConfigFilePath
			DefaultLogDir = filepath.Join(binDir, LogsDirectory)
			return bytes
		}
	}

	DefaultProgramFolder := filepath.Join(
		EnvProgramFiles,
		ApplicationFolderPrefix,
		getApplicationName(clientName))
	DefaultSeelogConfigFilePath = filepath.Join(DefaultProgramFolder, SeelogConfigFileName)
	DefaultLogDir = filepath.Join(
		DefaultProgramFolder,
		LogsDirectory)

	if logConfigBytes, err = ioutil.ReadFile(DefaultSeelogConfigFilePath); err != nil {
		logConfigBytes = DefaultConfig()
	}
	return
}
