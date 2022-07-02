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
	"errors"
	"fmt"
	"io"
	"strings"

	"github.com/aws/session-manager-plugin/src/ssmclicommands/utils"
	"github.com/twinj/uuid"
)

const (
	ArgumentLength = 2
)

// ParseCliCommand function parses command and returns options to validate.
func ParseCliCommand(args []string) (err error, options []string, command string, subcommand string, parameters map[string][]string) {
	argCount := len(args)
	pos := 1

	// Options
	options = make([]string, 0)
	for _, val := range args[pos:] {
		if !utils.IsFlag(val) {
			break
		}
		options = append(options, utils.GetFlag(val))
		pos++
	}

	// Command
	if pos >= argCount {
		err = errors.New("command is required")
		return
	}
	command = strings.ToLower(args[pos])
	pos++

	//subcommand
	if pos >= argCount {
		return
	}
	subcommand = strings.ToLower(args[pos])
	pos++

	// Parameters
	if pos >= argCount {
		return
	}
	parameters = make(map[string][]string)
	var parameterName string
	for _, val := range args[2:] {
		if utils.IsFlag(val) {
			parameterName = utils.GetFlag(val)
			if parameterName == "" {
				// aws cli doesn't valid this
				err = fmt.Errorf("input contains parameter with no name")
				return
			}
			if _, exists := parameters[parameterName]; exists {
				// aws cli doesn't valid this
				err = fmt.Errorf("duplicate parameter %v", parameterName)
				return
			}
			parameters[parameterName] = make([]string, 0)
		} else {
			parameters[parameterName] = append(parameters[parameterName], val)
		}
	}
	return
}

// ValidateInput function validates the input and displays response accordingly.
func ValidateInput(args []string, out io.Writer) {
	uuid.SwitchFormat(uuid.CleanHyphen)

	if len(args) < ArgumentLength {
		utils.DisplayCommandUsage(out)
		return
	}

	err, _, command, subcommand, parameters := ParseCliCommand(args)

	if err != nil {
		utils.DisplayCommandUsage(out)
		fmt.Fprint(out, err.Error())
		return
	}

	if cmd, exists := utils.SsmCliCommands[command]; exists {
		if utils.IsHelp(subcommand, parameters) {
			fmt.Fprintln(out, cmd.Help())
		} else {
			cmdErr, result := cmd.Execute(parameters)
			if cmdErr != nil {
				utils.DisplayCommandUsage(out)
				fmt.Fprint(out, cmdErr.Error())
				return
			} else {
				fmt.Fprint(out, result)
			}
		}
	} else if command == utils.HelpFlag {
		utils.DisplayHelp(out)
	} else {
		utils.DisplayCommandUsage(out)
		fmt.Fprintf(out, "\nInvalid command %v.  The following commands are supported:\n\n", command)
		utils.DisplaySupportedCommands(out)
	}
}
