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

//go:build darwin || freebsd || linux || netbsd || openbsd
// +build darwin freebsd linux netbsd openbsd

// Package sessionutil contains utility methods required to start session.
package sessionutil

import (
	"os"
	"syscall"
)

// All the signals to handles interrupt
// SIGINT captures Ctrl+C
// SIGQUIT captures Ctrl+\
// SIGTSTP captures Ctrl+Z
var SignalsByteMap = map[os.Signal]byte{
	syscall.SIGINT:  '\003',
	syscall.SIGQUIT: '\x1c',
	syscall.SIGTSTP: '\032',
}

var ControlSignals = []os.Signal{syscall.SIGINT, syscall.SIGTSTP, syscall.SIGQUIT}
