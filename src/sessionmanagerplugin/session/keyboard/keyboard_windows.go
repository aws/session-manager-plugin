//go:build windows
// +build windows

// Copyright 2024 Amazon.com, Inc. or its affiliates. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License").
// You may not use this file except in compliance with the License.
// A copy of the License is located at
//
//	http://www.apache.org/licenses/LICENSE-2.0
//
// or in the "license" file accompanying this file. This file is distributed
// on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
// express or implied. See the License for the specific language governing
// permissions and limitations under the License.
//
// Portions of the key constant definitions are inspired by and compatible with
// github.com/eiannone/keyboard (MIT License, Copyright (c) 2015 Emanuele Iannone),
// which itself derives from termbox-go (MIT License, Copyright (C) 2012 termbox-go authors).
// Key constants follow standard ASCII control codes (ANSI X3.4-1986) and are not copyrightable.
//
// Package keyboard provides Windows console keyboard input with enhanced support for
// international keyboards, advanced key combinations, and modifier detection.
//
// This implementation uses Windows Console API (ReadConsoleInputW) to capture keyboard
// events with proper handling of:
//   - International keyboard layouts (Spanish, French, German, etc.)
//   - AltGr combinations for special characters
//   - Ctrl+Alt modifier detection
//   - Extended Ctrl key combinations
//
// Key constant definitions are compatible with standard terminal control sequences
// as documented in ASCII control codes and VT100 terminal specifications.
package keyboard

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

// Key represents keyboard keys and control sequences.
// Values follow ASCII control code standards and VT100 terminal specifications.
type Key uint16

// Key constants for function keys, navigation, and special keys.
// These use high values (0xFFFF downward) to avoid conflicts with ASCII codes.
const (
	KeyF1 Key = 0xFFFF - iota
	KeyF2
	KeyF3
	KeyF4
	KeyF5
	KeyF6
	KeyF7
	KeyF8
	KeyF9
	KeyF10
	KeyF11
	KeyF12
	KeyInsert
	KeyDelete
	KeyHome
	KeyEnd
	KeyPgUp
	KeyPgDn
	KeyArrowUp
	KeyArrowDown
	KeyArrowLeft
	KeyArrowRight
	KeyCtrlSpace      Key = 0x00
	KeyCtrlTilde      Key = 0x00 // Alias for Ctrl+2
	KeyCtrlA          Key = 0x01
	KeyCtrlB          Key = 0x02
	KeyCtrlC          Key = 0x03
	KeyCtrlD          Key = 0x04
	KeyCtrlE          Key = 0x05
	KeyCtrlF          Key = 0x06
	KeyCtrlG          Key = 0x07
	KeyBackspace      Key = 0x08
	KeyCtrlH          Key = 0x08 // Alias for Backspace
	KeyTab            Key = 0x09
	KeyCtrlI          Key = 0x09 // Alias for Tab
	KeyCtrlJ          Key = 0x0A
	KeyCtrlK          Key = 0x0B
	KeyCtrlL          Key = 0x0C
	KeyEnter          Key = 0x0D
	KeyCtrlM          Key = 0x0D // Alias for Enter
	KeyCtrlN          Key = 0x0E
	KeyCtrlO          Key = 0x0F
	KeyCtrlP          Key = 0x10
	KeyCtrlQ          Key = 0x11
	KeyCtrlR          Key = 0x12
	KeyCtrlS          Key = 0x13
	KeyCtrlT          Key = 0x14
	KeyCtrlU          Key = 0x15
	KeyCtrlV          Key = 0x16
	KeyCtrlW          Key = 0x17
	KeyCtrlX          Key = 0x18
	KeyCtrlY          Key = 0x19
	KeyCtrlZ          Key = 0x1A
	KeyEsc            Key = 0x1B
	KeyCtrlLsqBracket Key = 0x1B // Alias for Esc/Ctrl+3
	KeyCtrlBackslash  Key = 0x1C
	KeyCtrlRsqBracket Key = 0x1D
	KeySpace          Key = 0x20
	KeyBackspace2     Key = 0x7F
	KeyCtrl2          Key = 0x00
	KeyCtrl3          Key = 0x1B
	KeyCtrl4          Key = 0x1C
	KeyCtrl5          Key = 0x1D
	KeyCtrl6          Key = 0x1E
	KeyCtrl7          Key = 0x1F
	KeyCtrlSlash      Key = 0x1F // Alias for Ctrl+7
	KeyCtrlUnderscore Key = 0x1F // Alias for Ctrl+7
	KeyCtrl8          Key = 0x7F
)

// Windows console modifier state flags as defined by Windows Console API.
// These correspond to the dwControlKeyState field in KEY_EVENT_RECORD.
// Reference: https://docs.microsoft.com/en-us/windows/console/key-event-record-str
const (
	rightAltPressed  = 0x01 // RIGHT_ALT_PRESSED
	leftAltPressed   = 0x02 // LEFT_ALT_PRESSED
	rightCtrlPressed = 0x04 // RIGHT_CTRL_PRESSED
	leftCtrlPressed  = 0x08 // LEFT_CTRL_PRESSED
	shiftPressed     = 0x10 // SHIFT_PRESSED
)

var (
	kernel32          = windows.NewLazySystemDLL("kernel32.dll")
	user32            = windows.NewLazySystemDLL("user32.dll")
	readInput         = kernel32.NewProc("ReadConsoleInputW")
	getCP             = kernel32.NewProc("GetConsoleCP")
	setCP             = kernel32.NewProc("SetConsoleCP")
	toUnicodeEx       = user32.NewProc("ToUnicodeEx")
	getKeyboardLayout = user32.NewProc("GetKeyboardLayout")
	consoleIn         windows.Handle
	eventQueue        = make(chan result, 10)
	done              = make(chan struct{})
	cancelKey         = make(chan struct{}, 1)
	active            bool
	isOpen            bool
	originalCP        uint32
)

// result represents a keyboard event result with character, key code, and modifier state.
type result struct {
	ch      rune
	k       Key
	err     error
	ctrlAlt bool // True when Ctrl+Alt combination (not AltGr)
}

// inputEvent maps to Windows INPUT_RECORD structure.
// Reference: https://docs.microsoft.com/en-us/windows/console/input-record-str
type inputEvent struct {
	typ uint16
	pad uint16
	evt [16]byte
}

// keyData maps to Windows KEY_EVENT_RECORD structure.
// Reference: https://docs.microsoft.com/en-us/windows/console/key-event-record-str
type keyData struct {
	down  int32  // bKeyDown
	count uint16 // wRepeatCount
	vk    uint16 // wVirtualKeyCode
	scan  uint16 // wVirtualScanCode
	char  uint16 // uChar.UnicodeChar
	state uint32 // dwControlKeyState
}

// Open initializes the keyboard input system by opening the Windows console input handle.
// Must be called before GetKey or GetKeyWithModifiers.
func Open() error {
	if isOpen {
		return nil
	}
	h, err := windows.CreateFile(
		windows.StringToUTF16Ptr("CONIN$"),
		windows.GENERIC_READ|windows.GENERIC_WRITE,
		windows.FILE_SHARE_READ,
		nil,
		windows.OPEN_EXISTING,
		0,
		0,
	)
	if err != nil {
		return err
	}
	consoleIn = h

	// Save original code page and set to UTF-8 (65001)
	ret, _, _ := getCP.Call(uintptr(consoleIn))
	originalCP = uint32(ret)
	setCP.Call(uintptr(consoleIn), uintptr(65001))

	active = true
	isOpen = true
	go pollEvents()
	return nil
}

// Close shuts down the keyboard input system and releases the console handle.
func Close() {
	if !isOpen {
		return
	}
	if !active {
		return
	}
	// Signal cancellation for any waiting GetKey calls
	select {
	case cancelKey <- struct{}{}:
	default:
	}
	close(done)

	// Restore original code page
	if originalCP != 0 {
		setCP.Call(uintptr(consoleIn), uintptr(originalCP))
	}

	windows.CloseHandle(consoleIn)
	active = false
	isOpen = false
}

// GetKey returns the next keyboard event, blocking until one is available.
// Returns the character (if printable), key code (for special keys), and any error.
// For modifier information, use GetKeyWithModifiers instead.
// Panics if called before Open().
func GetKey() (rune, Key, error) {
	if !isOpen {
		panic("GetKey() called before Open()")
	}
	select {
	case r := <-eventQueue:
		return r.ch, r.k, r.err
	case <-cancelKey:
		return 0, 0, nil
	}
}

// GetKeyWithModifiers returns the next keyboard event with modifier state information.
// Returns the character, key code, whether Ctrl+Alt was pressed (not AltGr), and any error.
// The ctrlAlt flag distinguishes Ctrl+Alt combinations from plain Ctrl or AltGr combinations.
// Panics if called before Open().
func GetKeyWithModifiers() (rune, Key, bool, error) {
	if !isOpen {
		panic("GetKeyWithModifiers() called before Open()")
	}
	select {
	case r := <-eventQueue:
		return r.ch, r.k, r.ctrlAlt, r.err
	case <-cancelKey:
		return 0, 0, false, nil
	}
}

// GetSingleKey is a convenience function that opens the keyboard, gets a single key, and closes it.
// Useful for simple one-time key reads without managing Open/Close lifecycle.
func GetSingleKey() (rune, Key, error) {
	err := Open()
	if err != nil {
		return 0, 0, err
	}
	defer Close()
	return GetKey()
}

// pollEvents continuously reads console input events from Windows and queues keyboard events.
// Runs in a separate goroutine started by Open().
func pollEvents() {
	var evt inputEvent
	var n uint32

	for {
		select {
		case <-done:
			return
		default:
		}

		ret, _, _ := readInput.Call(
			uintptr(consoleIn),
			uintptr(unsafe.Pointer(&evt)),
			1,
			uintptr(unsafe.Pointer(&n)),
		)

		if ret == 0 || n == 0 {
			continue
		}

		if evt.typ != 1 {
			continue
		}

		kd := (*keyData)(unsafe.Pointer(&evt.evt[0]))
		if kd.down == 0 {
			continue
		}

		r := translate(kd)
		if r.ch != 0 || r.k != 0 {
			for i := uint16(0); i < kd.count; i++ {
				eventQueue <- r
			}
		}
	}
}

// translate converts Windows KEY_EVENT_RECORD data into a keyboard event result.
// Handles international keyboard layouts, AltGr combinations, Ctrl+Alt detection,
// and proper character translation based on the active Windows keyboard layout.
func translate(kd *keyData) result {
	// Check for AltGr: right alt + left ctrl
	isAltGr := (kd.state&rightAltPressed != 0) && (kd.state&leftCtrlPressed != 0)
	isCtrl := (kd.state&(leftCtrlPressed|rightCtrlPressed) != 0) && !isAltGr
	isAlt := (kd.state&(leftAltPressed|rightAltPressed) != 0) && !isAltGr
	isCtrlAlt := isCtrl && isAlt

	// Function keys
	if kd.vk >= 0x70 && kd.vk <= 0x7B {
		return result{k: KeyF1 + Key(kd.vk-0x70)}
	}

	// Navigation and special keys (skip if AltGr is pressed to allow AltGr+key combinations)
	if !isAltGr {
		switch kd.vk {
		case 0x2D:
			return result{k: KeyInsert}
		case 0x2E:
			return result{k: KeyDelete}
		case 0x24:
			return result{k: KeyHome}
		case 0x23:
			return result{k: KeyEnd}
		case 0x21:
			return result{k: KeyPgUp}
		case 0x22:
			return result{k: KeyPgDn}
		case 0x26:
			return result{k: KeyArrowUp}
		case 0x28:
			return result{k: KeyArrowDown}
		case 0x25:
			return result{k: KeyArrowLeft}
		case 0x27:
			return result{k: KeyArrowRight}
		case 0x08:
			if isCtrl {
				return result{k: KeyBackspace2}
			}
			return result{k: KeyBackspace}
		case 0x09:
			return result{k: KeyTab}
		case 0x0D:
			return result{k: KeyEnter}
		case 0x1B:
			return result{k: KeyEsc}
		case 0x20:
			if isCtrl {
				return result{k: KeyCtrlSpace}
			}
			return result{k: KeySpace}
		}
	}

	// Regular character (including AltGr combinations for international keyboards)
	// Windows handles the translation based on active keyboard layout
	// Check this BEFORE Ctrl combinations to allow AltGr+key to work
	if kd.char != 0 {
		// Windows console provides characters in UTF-16, which Go's rune type handles correctly
		// Direct cast from uint16 to rune preserves the Unicode code point
		r := result{ch: rune(kd.char)}
		if isCtrlAlt {
			r.ctrlAlt = true
		}
		return r
	}

	// Special case: AltGr combinations where Windows doesn't provide char
	// Use ToUnicodeEx to get the character
	if isAltGr && kd.vk != 0 {
		var keyState [256]byte
		keyState[0x11] = 0x80 // VK_CONTROL
		keyState[0x12] = 0x80 // VK_MENU (Alt)
		if kd.state&shiftPressed != 0 {
			keyState[0x10] = 0x80 // VK_SHIFT
		}

		// Get current keyboard layout
		hkl, _, _ := getKeyboardLayout.Call(0)

		var buf [4]uint16
		ret, _, _ := toUnicodeEx.Call(
			uintptr(kd.vk),
			uintptr(kd.scan),
			uintptr(unsafe.Pointer(&keyState[0])),
			uintptr(unsafe.Pointer(&buf[0])),
			uintptr(len(buf)),
			0,
			hkl,
		)
		if ret > 0 && buf[0] != 0 {
			return result{ch: rune(buf[0])}
		}
	}

	// Ctrl combinations (only when no character was produced)
	if isCtrl {
		// Ctrl+letter (A-Z and extended)
		if Key(kd.char) >= KeyCtrlA && Key(kd.char) <= KeyCtrlRsqBracket {
			return result{k: Key(kd.char), ctrlAlt: isCtrlAlt}
		}

		// Ctrl+number combinations
		switch kd.vk {
		case 192, 50: // Ctrl+2 or Ctrl+`
			return result{k: KeyCtrl2, ctrlAlt: isCtrlAlt}
		case 51: // Ctrl+3
			return result{k: KeyCtrl3, ctrlAlt: isCtrlAlt}
		case 52: // Ctrl+4
			return result{k: KeyCtrl4, ctrlAlt: isCtrlAlt}
		case 53: // Ctrl+5
			return result{k: KeyCtrl5, ctrlAlt: isCtrlAlt}
		case 54: // Ctrl+6
			return result{k: KeyCtrl6, ctrlAlt: isCtrlAlt}
		case 189, 191, 55: // Ctrl+7
			return result{k: KeyCtrl7, ctrlAlt: isCtrlAlt}
		case 8, 56: // Ctrl+8
			return result{k: KeyCtrl8, ctrlAlt: isCtrlAlt}
		}
	}

	return result{}
}
