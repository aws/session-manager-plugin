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

// Package shellsession starts shell session.
package shellsession

import (
	"os"
	"unsafe"

	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"golang.org/x/sys/windows"
)

var (
	handle           windows.Handle
	modeSave         uint32
	kernel32         = windows.NewLazyDLL("kernel32.dll")
	procReadConsoleA = kernel32.NewProc("ReadConsoleA")
)

// stop restores the terminal settings and exits
func (s *ShellSession) Stop() {
	windows.SetConsoleMode(handle, modeSave)
	os.Exit(0)
}

func readConsoleA(hConsoleInput windows.Handle, lpBuffer unsafe.Pointer, nNumberOfCharsToRead int, lpNumberOfCharsRead *uint32, pInputControl unsafe.Pointer) (err error) {
	r1, _, e1 := procReadConsoleA.Call(
		uintptr(hConsoleInput),
		uintptr(lpBuffer),
		uintptr(nNumberOfCharsToRead),
		uintptr(unsafe.Pointer(lpNumberOfCharsRead)),
		uintptr(pInputControl),
	)
	if r1 == 0 {
		err = e1
	}
	return
}

// handleKeyboardInput handles input entered by customer on terminal
func (s *ShellSession) handleKeyboardInput(log log.T) (err error) {
	handle = windows.Handle(os.Stdin.Fd())

	err = windows.GetConsoleMode(handle, &modeSave)
	if err != nil {
		log.Errorf("Failed to get console mode: %v", err)
		return
	}

	err = windows.SetConsoleMode(handle, windows.ENABLE_VIRTUAL_TERMINAL_INPUT)
	if err != nil {
		log.Errorf("Failed to set console to virtual terminal: %v", err)
		return
	}

	buf := make([]byte, 1024)
	var read uint32
	for {
		readConsoleA(handle, unsafe.Pointer(&buf[0]), len(buf), &read, nil)

		if err = s.Session.DataChannel.SendInputDataMessage(log, message.Output, buf[:read]); err != nil {
			log.Errorf("Failed to send UTF8 char: %v", err)
			break
		}
	}

	return
}
