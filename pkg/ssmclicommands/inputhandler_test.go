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
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestParseCliCommand(t *testing.T) {
	args := []string{1: "--instance-id"}
	err, _, _, _, _ := ParseCliCommand(args)
	assert.NotNil(t, err)
	assert.Contains(t, err.Error(), "command is required")

	args = []string{1: "start-session", 2: "--instance-id", 3: "i-123456", 4: "--"}
	err, _, _, _, _ = ParseCliCommand(args)
	assert.NotNil(t, err)
	assert.Contains(t, err.Error(), "input contains parameter with no name")

	args = []string{1: "start-session", 2: "--instance-id", 3: "--instance-id"}
	err, _, _, _, _ = ParseCliCommand(args)
	assert.NotNil(t, err)
	assert.Contains(t, err.Error(), "duplicate parameter instance-id")

	args = []string{1: "start-session", 2: "--instance-id", 3: "i-123456", 4: "--region", 5: "us-east-1"}
	err, _, command, _, parameters := ParseCliCommand(args)
	assert.Nil(t, err)
	assert.Equal(t, command, "start-session")
	assert.Equal(t, parameters["instance-id"][0], "i-123456")
	assert.Equal(t, parameters["region"][0], "us-east-1")
}

func TestValidateInputUsage(t *testing.T) {
	var buffer bytes.Buffer
	var args []string
	ValidateInput(args, &buffer)
	assert.Contains(t, buffer.String(), "To see help text")

	args = []string{1: "ssmcli"}
	buffer.Reset()
	ValidateInput(args, &buffer)
	assert.Contains(t, buffer.String(), "Invalid command ssmcli")

	args = []string{1: "help"}
	buffer.Reset()
	ValidateInput(args, &buffer)
	assert.Contains(t, buffer.String(), "Available commands are")

	args = []string{1: "start-session", 2: "help"}
	buffer.Reset()
	ValidateInput(args, &buffer)
	assert.Contains(t, buffer.String(), "SYNOPSIS:")

	args = []string{1: "start-session", 2: "--instance-id", 3: "--instance-id"}
	buffer.Reset()
	ValidateInput(args, &buffer)
	assert.Contains(t, buffer.String(), "duplicate parameter instance-id")

	args = []string{1: "start-session", 2: "--region", 3: "us-east-1"}
	buffer.Reset()
	ValidateInput(args, &buffer)
	assert.Contains(t, buffer.String(), "instance-id is required")
}
