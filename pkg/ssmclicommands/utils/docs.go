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
	"io"
	"sort"
)

// DisplayCommandUsage prints cli usage info to console
func DisplayCommandUsage(out io.Writer) {
	fmt.Fprintf(out, "usage: %v [options] <command> [parameters]\n", SsmCliName)
	fmt.Fprintf(out, "To see help text, you can run:\n\n")
	fmt.Fprintf(out, "  %v %v\n", SsmCliName, HelpFlag)
	fmt.Fprintf(out, "  %v <command> %v\n", SsmCliName, HelpFlag)
}

// DisplayHelp shows help for the ssmcli
func DisplayHelp(out io.Writer) {
	fmt.Fprintf(out, "%v\n", SsmCliName)
	fmt.Fprintf(out, "Available commands are:\n")
	DisplaySupportedCommands(out)
}

// DisplaySupportedCommands prints a list of supported cli commands to the console
func DisplaySupportedCommands(out io.Writer) {
	commands := make([]string, 0, len(SsmCliCommands))
	for command := range SsmCliCommands {
		commands = append(commands, command)
	}
	sort.Strings(commands)
	for _, command := range commands {
		fmt.Fprintf(out, "%v\n", command)
	}
}
