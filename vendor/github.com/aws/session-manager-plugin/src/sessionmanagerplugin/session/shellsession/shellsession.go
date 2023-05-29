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
