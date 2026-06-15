// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package shellsession

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func newTestShellSession() *ShellSession {
	s := &ShellSession{}
	s.escapeTracking = shellEscapeSequenceTracking{enabled: true}
	return s
}

func TestEscapeSequence_NormalTypingNotConsumed(t *testing.T) {
	s := newTestShellSession()
	toSend, err := s.handleEscapeSequence(logger, []byte("hello"), 5)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
}

func TestEscapeSequence_TildeWithoutNewlineNotConsumed(t *testing.T) {
	s := newTestShellSession()
	toSend, err := s.handleEscapeSequence(logger, []byte("~"), 1)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
}

func TestEscapeSequence_NewlineThenTildeIsConsumed(t *testing.T) {
	s := newTestShellSession()

	// Send newline
	toSend, err := s.handleEscapeSequence(logger, []byte("\n"), 1)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)

	// Send ~
	toSend, err = s.handleEscapeSequence(logger, []byte("~"), 1)
	assert.Equal(t, 0, len(toSend)) // consumed, waiting for command
	assert.Nil(t, err)
	assert.True(t, s.escapeTracking.escaped)
}

func TestEscapeSequence_HelpCommand(t *testing.T) {
	s := newTestShellSession()

	s.handleEscapeSequence(logger, []byte("\n"), 1)
	s.handleEscapeSequence(logger, []byte("~"), 1)
	toSend, err := s.handleEscapeSequence(logger, []byte("?"), 1)
	assert.Equal(t, 0, len(toSend))
	assert.Nil(t, err)
	// State should be reset after help
	assert.False(t, s.escapeTracking.escaped)
	assert.False(t, s.escapeTracking.newline)
}

func TestEscapeSequence_DisableCommand(t *testing.T) {
	s := newTestShellSession()

	s.handleEscapeSequence(logger, []byte("\n"), 1)
	s.handleEscapeSequence(logger, []byte("~"), 1)
	toSend, err := s.handleEscapeSequence(logger, []byte("-"), 1)
	assert.Equal(t, 0, len(toSend))
	assert.Nil(t, err)
	assert.False(t, s.escapeTracking.enabled)

	// After disable, tilde after newline should pass through
	s.handleEscapeSequence(logger, []byte("\n"), 1)
	toSend, err = s.handleEscapeSequence(logger, []byte("~"), 1)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
}

func TestEscapeSequence_DoubleTildeSendsLiteral(t *testing.T) {
	s := newTestShellSession()

	s.handleEscapeSequence(logger, []byte("\n"), 1)
	s.handleEscapeSequence(logger, []byte("~"), 1)
	// ~~ should NOT skip — sends literal ~
	toSend, err := s.handleEscapeSequence(logger, []byte("~"), 1)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
}

func TestEscapeSequence_UnknownCommandResets(t *testing.T) {
	s := newTestShellSession()

	s.handleEscapeSequence(logger, []byte("\n"), 1)
	s.handleEscapeSequence(logger, []byte("~"), 1)
	// Unknown command 'x' — should not skip, resets state
	toSend, err := s.handleEscapeSequence(logger, []byte("x"), 1)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
	assert.False(t, s.escapeTracking.escaped)
}

func TestEscapeSequence_MultiByteBufferWithFullSequence(t *testing.T) {
	s := newTestShellSession()

	// "\n~?" in one buffer: \n is forwarded, ~? is consumed locally.
	// Only the \n should be in toSend.
	toSend, err := s.handleEscapeSequence(logger, []byte("\n~?"), 3)
	assert.Equal(t, []byte("\n"), toSend)
	assert.Nil(t, err)
}

func TestEscapeSequence_CarriageReturnAlsoTriggers(t *testing.T) {
	s := newTestShellSession()

	// CR should also count as newline
	toSend, err := s.handleEscapeSequence(logger, []byte("\r"), 1)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)

	toSend, err = s.handleEscapeSequence(logger, []byte("~"), 1)
	assert.Equal(t, 0, len(toSend))
	assert.Nil(t, err)
	assert.True(t, s.escapeTracking.escaped)
}

func TestEscapeSequence_NewlineInMiddleOfBuffer(t *testing.T) {
	s := newTestShellSession()

	// "abc\n" — should set newline flag from last byte
	toSend, err := s.handleEscapeSequence(logger, []byte("abc\n"), 4)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
	assert.True(t, s.escapeTracking.newline)
}

func TestEscapeSequence_NonNewlineAfterNewlineResetsFlag(t *testing.T) {
	s := newTestShellSession()

	s.handleEscapeSequence(logger, []byte("\n"), 1)
	assert.True(t, s.escapeTracking.newline)

	// Typing a normal char (not ~) should reset
	s.handleEscapeSequence(logger, []byte("a"), 1)
	assert.False(t, s.escapeTracking.newline)
}

func TestEscapeSequence_DoubleTildeInMultiByteBuffer(t *testing.T) {
	s := newTestShellSession()

	// "\n~~" in one buffer: processes \n (sets newline), first ~ (sets escaped),
	// second ~ (literal tilde case, returns false). Buffer passes through.
	toSend, err := s.handleEscapeSequence(logger, []byte("\n~~"), 3)
	assert.True(t, len(toSend) > 0)
	assert.Nil(t, err)
}
