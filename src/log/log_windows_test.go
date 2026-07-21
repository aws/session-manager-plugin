// Copyright 2026 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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
	"io/ioutil"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
)

const testClientName = "session-manager-plugin"

// resetGlobals resets the package-level state that getLogConfigBytes mutates,
// so tests don't leak state into each other.
func resetGlobals() {
	DefaultSeelogConfigFilePath = ""
	DefaultLogDir = ""
	ApplicationLogFile = ""
	ErrorLogFile = ""
	err = nil
}

func TestGetLogConfigBytes_UsesBinaryDirConfig_WhenPresent(t *testing.T) {
	defer resetGlobals()

	tempDir := t.TempDir()
	seelogContent := []byte(`<seelog type="adaptive" minlevel="debug"></seelog>`)
	seelogPath := filepath.Join(tempDir, SeelogConfigFileName)
	assert.NoError(t, ioutil.WriteFile(seelogPath, seelogContent, 0644))

	originalGetExecutableDir := getExecutableDir
	getExecutableDir = func() (string, error) {
		return tempDir, nil
	}
	defer func() { getExecutableDir = originalGetExecutableDir }()

	result := getLogConfigBytes(testClientName)

	assert.Equal(t, seelogContent, result)
	assert.Equal(t, seelogPath, DefaultSeelogConfigFilePath)
	assert.Equal(t, filepath.Join(tempDir, LogsDirectory), DefaultLogDir)
}

func TestGetLogConfigBytes_FallsBackToProgramFiles_WhenBinaryDirConfigMissing(t *testing.T) {
	defer resetGlobals()

	// Binary directory exists but has no seelog.xml in it.
	tempBinDir := t.TempDir()
	originalGetExecutableDir := getExecutableDir
	getExecutableDir = func() (string, error) {
		return tempBinDir, nil
	}
	defer func() { getExecutableDir = originalGetExecutableDir }()

	// Point %ProgramFiles% at a temp dir that also has no seelog.xml, so we
	// exercise the built-in default config fallback and can assert on the
	// resulting path without needing a real Program Files layout.
	tempProgramFiles := t.TempDir()
	originalEnvProgramFiles := EnvProgramFiles
	EnvProgramFiles = tempProgramFiles
	defer func() { EnvProgramFiles = originalEnvProgramFiles }()

	result := getLogConfigBytes(testClientName)

	expectedProgramFolder := filepath.Join(tempProgramFiles, ApplicationFolderPrefix, getApplicationName(testClientName))
	expectedSeelogPath := filepath.Join(expectedProgramFolder, SeelogConfigFileName)

	assert.Equal(t, expectedSeelogPath, DefaultSeelogConfigFilePath)
	assert.Equal(t, filepath.Join(expectedProgramFolder, LogsDirectory), DefaultLogDir)
	assert.Equal(t, DefaultConfig(), result)
}

func TestGetLogConfigBytes_UsesProgramFilesConfig_WhenPresentAndNoBinaryDirConfig(t *testing.T) {
	defer resetGlobals()

	tempBinDir := t.TempDir()
	originalGetExecutableDir := getExecutableDir
	getExecutableDir = func() (string, error) {
		return tempBinDir, nil
	}
	defer func() { getExecutableDir = originalGetExecutableDir }()

	tempProgramFiles := t.TempDir()
	originalEnvProgramFiles := EnvProgramFiles
	EnvProgramFiles = tempProgramFiles
	defer func() { EnvProgramFiles = originalEnvProgramFiles }()

	expectedProgramFolder := filepath.Join(tempProgramFiles, ApplicationFolderPrefix, getApplicationName(testClientName))
	assert.NoError(t, os.MkdirAll(expectedProgramFolder, 0755))
	seelogContent := []byte(`<seelog type="adaptive" minlevel="info"></seelog>`)
	assert.NoError(t, ioutil.WriteFile(filepath.Join(expectedProgramFolder, SeelogConfigFileName), seelogContent, 0644))

	result := getLogConfigBytes(testClientName)

	assert.Equal(t, seelogContent, result)
	assert.Equal(t, filepath.Join(expectedProgramFolder, SeelogConfigFileName), DefaultSeelogConfigFilePath)
}

func TestGetLogConfigBytes_FallsBackToProgramFiles_WhenExecutableDirLookupFails(t *testing.T) {
	defer resetGlobals()

	originalGetExecutableDir := getExecutableDir
	getExecutableDir = func() (string, error) {
		return "", os.ErrNotExist
	}
	defer func() { getExecutableDir = originalGetExecutableDir }()

	tempProgramFiles := t.TempDir()
	originalEnvProgramFiles := EnvProgramFiles
	EnvProgramFiles = tempProgramFiles
	defer func() { EnvProgramFiles = originalEnvProgramFiles }()

	expectedProgramFolder := filepath.Join(tempProgramFiles, ApplicationFolderPrefix, getApplicationName(testClientName))
	assert.NoError(t, os.MkdirAll(expectedProgramFolder, 0755))
	seelogContent := []byte(`<seelog type="adaptive" minlevel="info"></seelog>`)
	assert.NoError(t, ioutil.WriteFile(filepath.Join(expectedProgramFolder, SeelogConfigFileName), seelogContent, 0644))

	result := getLogConfigBytes(testClientName)

	assert.Equal(t, seelogContent, result)
	assert.Equal(t, filepath.Join(expectedProgramFolder, SeelogConfigFileName), DefaultSeelogConfigFilePath)
}
