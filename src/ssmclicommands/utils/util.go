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

// Package utils contains all the utility functions.
package utils

import (
	"fmt"
	"strings"
)

const (
	HelpFlag   = "help"
	SsmCliName = "ssmcli"
	FlagPrefix = "--"
)

// CliCommands is the set of support commands
var SsmCliCommands map[string]SsmCliCommand

// CliCommand defines the interface for all commands the cli can execute
type SsmCliCommand interface {
	Execute(parameters map[string][]string) (error, string)
	Help() string
	Name() string
}

// init creates the map of commands - all imported commands will add themselves to the map
func init() {
	SsmCliCommands = make(map[string]SsmCliCommand)
}

// Register
func Register(command SsmCliCommand) {
	SsmCliCommands[command.Name()] = command
}

// IsFlag returns true if val is a flag
func IsFlag(val string) bool {
	return strings.HasPrefix(val, FlagPrefix)
}

// GetFlag returns the flag name if val is a flag, or empty if it is not
func GetFlag(val string) string {
	if strings.HasPrefix(val, FlagPrefix) {
		return strings.ToLower(strings.TrimLeft(val, FlagPrefix))
	}
	return ""
}

// IsHelp determines if a subcommand or flag is a request for help
func IsHelp(subcommand string, parameters map[string][]string) bool {

	if subcommand == HelpFlag {
		return true
	}

	if _, exists := parameters[HelpFlag]; exists {
		return true
	}
	return false
}

// FormatFlag returns a parameter name formatted as a command line flag
func FormatFlag(flagName string) string {
	return fmt.Sprintf("%v%v", FlagPrefix, flagName)
}
