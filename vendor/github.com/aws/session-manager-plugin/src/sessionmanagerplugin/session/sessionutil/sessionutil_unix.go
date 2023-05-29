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

// Package sessionutil provides utility for sessions.
package sessionutil

import (
	"fmt"
	"io"
	"net"
	"os"

	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
)

type DisplayMode struct {
}

func (d *DisplayMode) InitDisplayMode(log log.T) {
}

// DisplayMessage function displays the output on the screen
func (d *DisplayMode) DisplayMessage(log log.T, message message.ClientMessage) {
	var out io.Writer = os.Stdout
	fmt.Fprint(out, string(message.Payload))
}

// NewListener starts a new socket listener on the address.
func NewListener(log log.T, address string) (net.Listener, error) {
	return net.Listen("unix", address)
}
