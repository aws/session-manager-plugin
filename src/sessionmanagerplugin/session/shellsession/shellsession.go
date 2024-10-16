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

// Package shellsession starts shell session.
package shellsession

import (
	"bytes"
	"encoding/json"
	"os"
	"os/signal"
	"time"

	"github.com/aws/session-manager-plugin/src/config"
	"github.com/aws/session-manager-plugin/src/log"
	"github.com/aws/session-manager-plugin/src/message"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session"
	"github.com/aws/session-manager-plugin/src/sessionmanagerplugin/session/sessionutil"
	"golang.org/x/crypto/ssh/terminal"
)

const (
	ResizeSleepInterval = time.Millisecond * 500
	StdinBufferLimit    = 1024
)

type ShellSession struct {
	session.Session

	// SizeData is used to store size data at session level to compare with new size.
	SizeData          message.SizeData
	originalSttyState bytes.Buffer
	escapeTracking    ShellEscapeSequenceTracking
}

var GetTerminalSizeCall = func(fd int) (width int, height int, err error) {
	return terminal.GetSize(fd)
}

func init() {
	session.Register(&ShellSession{})
}

// Name is the session name used in the plugin
func (ShellSession) Name() string {
	return config.ShellPluginName
}

func (s *ShellSession) Initialize(log log.T, sessionVar *session.Session) {
	s.Session = *sessionVar
	s.DataChannel.RegisterOutputStreamHandler(s.ProcessStreamMessagePayload, true)
	s.DataChannel.GetWsChannel().SetOnMessage(
		func(input []byte) {
			s.DataChannel.OutputMessageHandler(log, s.Stop, s.SessionId, input)
		})
	s.escapeTracking = ShellEscapeSequenceTracking{
		enabled: true,
		newline: false,
		escaped: false,
	}
}

// StartSession takes input and write it to data channel
func (s *ShellSession) SetSessionHandlers(log log.T) (err error) {

	// handle re-size
	s.handleTerminalResize(log)

	// handle control signals
	s.handleControlSignals(log)

	//handles keyboard input
	err = s.handleKeyboardInput(log)

	return
}

// handleControlSignals handles control signals when given by user
func (s *ShellSession) handleControlSignals(log log.T) {
	go func() {
		signals := make(chan os.Signal, 1)
		signal.Notify(signals, sessionutil.ControlSignals...)
		for {
			sig := <-signals
			if b, ok := sessionutil.SignalsByteMap[sig]; ok {
				if err := s.DataChannel.SendInputDataMessage(log, message.Output, []byte{b}); err != nil {
					log.Errorf("Failed to send control signals: %v", err)
				}
			}
		}
	}()
}

// handleTerminalResize checks size of terminal every 500ms and sends size data.
func (s *ShellSession) handleTerminalResize(log log.T) {
	var (
		width         int
		height        int
		inputSizeData []byte
		err           error
	)
	go func() {
		for {
			// If running from IDE GetTerminalSizeCall will not work. Supply a fixed width and height value.
			if width, height, err = GetTerminalSizeCall(int(os.Stdout.Fd())); err != nil {
				width = 300
				height = 100
				log.Errorf("Could not get size of the terminal: %s, using width %d height %d", err, width, height)
			}

			if s.SizeData.Rows != uint32(height) || s.SizeData.Cols != uint32(width) {
				sizeData := message.SizeData{
					Cols: uint32(width),
					Rows: uint32(height),
				}
				s.SizeData = sizeData

				if inputSizeData, err = json.Marshal(sizeData); err != nil {
					log.Errorf("Cannot marshall size data: %v", err)
				}
				log.Debugf("Sending input size data: %s", inputSizeData)
				if err = s.DataChannel.SendInputDataMessage(log, message.Size, inputSizeData); err != nil {
					log.Errorf("Failed to Send size data: %v", err)
				}
			}
			// repeating this loop for every 500ms
			time.Sleep(ResizeSleepInterval)
		}
	}()
}

// ProcessStreamMessagePayload prints payload received on datachannel to console
func (s ShellSession) ProcessStreamMessagePayload(log log.T, outputMessage message.ClientMessage) (isHandlerReady bool, err error) {
	s.DisplayMode.DisplayMessage(log, outputMessage)
	return true, nil
}

// Shell Session Escape Sequence Tracking Flags
type ShellEscapeSequenceTracking struct {
	enabled bool // whether the shell session should check for escape sequences
	newline bool // whether the last character was a newline (the first half of the trigger)
	escaped bool // whether the shell session is escaped (the second half of the trigger)
}

// Reset Escape Sequence due to finishing an escape sequence, or invalid escape sequence.
func (s *ShellEscapeSequenceTracking) Reset() {
	s.escaped = false
	s.newline = false
}

// Disable checking for Escape Sequences for the rest of the connected Session.
func (s *ShellEscapeSequenceTracking) Disable() {
	s.enabled = false
}

// First half of trigger (newline) detected
func (s *ShellEscapeSequenceTracking) HalfTrigger() {
	s.newline = true
}

// Second half of trigger (~) detected
func (s *ShellEscapeSequenceTracking) Trigger() {
	if s.newline {
		s.escaped = true
	} else {
		panic("Unexpected trigger, when prior newline missing")
	}
}

// handleEscapeSequence process key presses looking for the escape sequence
func (s *ShellSession) handleEscapeSequence(log log.T, stdinBytes []byte, stdinBytesLen int) (skipMessage bool, err error) {
	const (
		escape_help = "\nSupported escape sequence commands:\n" +
			"~?  - this help message\n" +
			"~~  - send the ~ character to the remote target\n" +
			"~-  - disable escape sequences for the rest of this session\n" +
			"~.  - disconnect and terminate session\n" +
			"(Note that escapes are only recognized immediately after newline.)"
	)

	if s.escapeTracking.enabled {
		if s.escapeTracking.newline && stdinBytesLen == 1 {
			if s.escapeTracking.escaped {
				switch stdinBytes[0] {
				case '?': // help
					println(escape_help)
					s.escapeTracking.Reset()
					return true, nil
				case '.': // disconnect and terminate
					if err := s.Session.TerminateSession(log); err != nil {
						return true, err
					}
					return true, nil
				case '-': // disable
					s.escapeTracking.Disable()
					return true, nil
				case '~': // send explicit ~ character
					s.escapeTracking.Reset()
					return false, nil
				}
				s.escapeTracking.Reset()
			} else if stdinBytes[0] == '~' {
				s.escapeTracking.Trigger()
				return true, nil
			} else {
				s.escapeTracking.Reset()
			}
		}

		// If last sent bytes ends with newline, mark as possible escape sequence
		if stdinBytes[stdinBytesLen-1] == '\n' || stdinBytes[stdinBytesLen-1] == '\r' {
			s.escapeTracking.HalfTrigger()
		}
	}
	return false, nil
}
