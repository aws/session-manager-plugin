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

//go:build darwin || freebsd || netbsd || openbsd
// +build darwin freebsd netbsd openbsd

// Package shellsession starts shell session.
package shellsession

import "bytes"

// disableDelayedSuspend disables the DSUSP terminal special character (Ctrl+Y by
// default) on BSD-derived systems, including macOS.
//
// In cbreak mode the terminal driver still acts on DSUSP, so pressing Ctrl+Y
// triggers a delayed suspend that causes the Stdin read to fail with
// "read /dev/stdin: resource temporarily unavailable" and the session to
// terminate. Linux does not implement DSUSP, so this is a no-op there.
// See https://github.com/aws/session-manager-plugin/issues/29.
func (s *ShellSession) disableDelayedSuspend() {
	setState(bytes.NewBufferString("dsusp undef"))
}
