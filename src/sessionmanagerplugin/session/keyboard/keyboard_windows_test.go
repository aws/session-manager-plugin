//go:build windows
// +build windows

package keyboard

import (
	"testing"
)

func TestTranslateBasicKeys(t *testing.T) {
	tests := []struct {
		name     string
		kd       keyData
		wantChar rune
		wantKey  Key
	}{
		{
			name:     "Regular character 'a'",
			kd:       keyData{down: 1, vk: 0x41, char: 'a', state: 0},
			wantChar: 'a',
			wantKey:  0,
		},
		{
			name:     "Regular character 'A' with shift",
			kd:       keyData{down: 1, vk: 0x41, char: 'A', state: shiftPressed},
			wantChar: 'A',
			wantKey:  0,
		},
		{
			name:     "Number '1'",
			kd:       keyData{down: 1, vk: 0x31, char: '1', state: 0},
			wantChar: '1',
			wantKey:  0,
		},
		{
			name:    "Backspace",
			kd:      keyData{down: 1, vk: 0x08, char: 0, state: 0},
			wantKey: KeyBackspace,
		},
		{
			name:    "Tab",
			kd:      keyData{down: 1, vk: 0x09, char: 0, state: 0},
			wantKey: KeyTab,
		},
		{
			name:    "Enter",
			kd:      keyData{down: 1, vk: 0x0D, char: 0, state: 0},
			wantKey: KeyEnter,
		},
		{
			name:    "Escape",
			kd:      keyData{down: 1, vk: 0x1B, char: 0, state: 0},
			wantKey: KeyEsc,
		},
		{
			name:    "Space",
			kd:      keyData{down: 1, vk: 0x20, char: ' ', state: 0},
			wantKey: KeySpace,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := translate(&tt.kd)
			if result.ch != tt.wantChar {
				t.Errorf("translate() char = %v, want %v", result.ch, tt.wantChar)
			}
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
		})
	}
}

func TestTranslateFunctionKeys(t *testing.T) {
	tests := []struct {
		name    string
		vk      uint16
		wantKey Key
	}{
		{"F1", 0x70, KeyF1},
		{"F2", 0x71, KeyF2},
		{"F5", 0x74, KeyF5},
		{"F10", 0x79, KeyF10},
		{"F12", 0x7B, KeyF12},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			kd := keyData{down: 1, vk: tt.vk, char: 0, state: 0}
			result := translate(&kd)
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
		})
	}
}

func TestTranslateNavigationKeys(t *testing.T) {
	tests := []struct {
		name    string
		vk      uint16
		wantKey Key
	}{
		{"Insert", 0x2D, KeyInsert},
		{"Delete", 0x2E, KeyDelete},
		{"Home", 0x24, KeyHome},
		{"End", 0x23, KeyEnd},
		{"PageUp", 0x21, KeyPgUp},
		{"PageDown", 0x22, KeyPgDn},
		{"ArrowUp", 0x26, KeyArrowUp},
		{"ArrowDown", 0x28, KeyArrowDown},
		{"ArrowLeft", 0x25, KeyArrowLeft},
		{"ArrowRight", 0x27, KeyArrowRight},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			kd := keyData{down: 1, vk: tt.vk, char: 0, state: 0}
			result := translate(&kd)
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
		})
	}
}

func TestTranslateCtrlCombinations(t *testing.T) {
	tests := []struct {
		name    string
		kd      keyData
		wantKey Key
	}{
		{
			name:    "Ctrl+A",
			kd:      keyData{down: 1, vk: 0x41, char: 0x01, state: leftCtrlPressed},
			wantKey: KeyCtrlA,
		},
		{
			name:    "Ctrl+C",
			kd:      keyData{down: 1, vk: 0x43, char: 0x03, state: leftCtrlPressed},
			wantKey: KeyCtrlC,
		},
		{
			name:    "Ctrl+Z",
			kd:      keyData{down: 1, vk: 0x5A, char: 0x1A, state: leftCtrlPressed},
			wantKey: KeyCtrlZ,
		},
		{
			name:    "Ctrl+Space",
			kd:      keyData{down: 1, vk: 0x20, char: 0, state: leftCtrlPressed},
			wantKey: KeyCtrlSpace,
		},
		{
			name:    "Ctrl+Backspace",
			kd:      keyData{down: 1, vk: 0x08, char: 0, state: leftCtrlPressed},
			wantKey: KeyBackspace2,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := translate(&tt.kd)
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
			if result.ctrlAlt {
				t.Errorf("translate() ctrlAlt = true, want false")
			}
		})
	}
}

func TestTranslateCtrlNumberCombinations(t *testing.T) {
	tests := []struct {
		name    string
		vk      uint16
		wantKey Key
	}{
		{"Ctrl+2", 50, KeyCtrl2},
		{"Ctrl+3", 51, KeyCtrl3},
		{"Ctrl+4", 52, KeyCtrl4},
		{"Ctrl+5", 53, KeyCtrl5},
		{"Ctrl+6", 54, KeyCtrl6},
		{"Ctrl+7", 55, KeyCtrl7},
		{"Ctrl+8", 56, KeyCtrl8},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			kd := keyData{down: 1, vk: tt.vk, char: 0, state: leftCtrlPressed}
			result := translate(&kd)
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
		})
	}
}

func TestTranslateAltGrCombinations(t *testing.T) {
	tests := []struct {
		name     string
		kd       keyData
		wantChar rune
	}{
		{
			name:     "AltGr+1 (Spanish keyboard produces |)",
			kd:       keyData{down: 1, vk: 0x31, char: '|', state: rightAltPressed | leftCtrlPressed},
			wantChar: '|',
		},
		{
			name:     "AltGr+2 (Spanish keyboard produces @)",
			kd:       keyData{down: 1, vk: 0x32, char: '@', state: rightAltPressed | leftCtrlPressed},
			wantChar: '@',
		},
		{
			name:     "AltGr+3 (Spanish keyboard produces #)",
			kd:       keyData{down: 1, vk: 0x33, char: '#', state: rightAltPressed | leftCtrlPressed},
			wantChar: '#',
		},
		{
			name:     "AltGr+E (French keyboard produces €)",
			kd:       keyData{down: 1, vk: 0x45, char: '€', state: rightAltPressed | leftCtrlPressed},
			wantChar: '€',
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := translate(&tt.kd)
			if result.ch != tt.wantChar {
				t.Errorf("translate() char = %v, want %v", result.ch, tt.wantChar)
			}
			if result.ctrlAlt {
				t.Errorf("translate() ctrlAlt = true, want false for AltGr")
			}
		})
	}
}

func TestTranslateInternationalCharacters(t *testing.T) {
	tests := []struct {
		name     string
		kd       keyData
		wantChar rune
	}{
		{
			name:     "UK Pound sign £",
			kd:       keyData{down: 1, vk: 0x33, char: 0x00A3, state: shiftPressed},
			wantChar: '£',
		},
		{
			name:     "UK Not sign ¬",
			kd:       keyData{down: 1, vk: 0xC0, char: 0x00AC, state: shiftPressed},
			wantChar: '¬',
		},
		{
			name:     "Spanish ñ",
			kd:       keyData{down: 1, vk: 0xBA, char: 'ñ', state: 0},
			wantChar: 'ñ',
		},
		{
			name:     "Spanish á",
			kd:       keyData{down: 1, vk: 0xDE, char: 'á', state: 0},
			wantChar: 'á',
		},
		{
			name:     "German ü",
			kd:       keyData{down: 1, vk: 0xBA, char: 'ü', state: 0},
			wantChar: 'ü',
		},
		{
			name:     "French é",
			kd:       keyData{down: 1, vk: 0x32, char: 'é', state: 0},
			wantChar: 'é',
		},
		{
			name:     "Spanish ¿",
			kd:       keyData{down: 1, vk: 0xBF, char: '¿', state: 0},
			wantChar: '¿',
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := translate(&tt.kd)
			if result.ch != tt.wantChar {
				t.Errorf("translate() char = %v (U+%04X), want %v (U+%04X)", result.ch, result.ch, tt.wantChar, tt.wantChar)
			}
		})
	}
}

func TestTranslateCtrlAltCombinations(t *testing.T) {
	tests := []struct {
		name        string
		kd          keyData
		wantKey     Key
		wantCtrlAlt bool
	}{
		{
			name:        "Ctrl+Alt+A",
			kd:          keyData{down: 1, vk: 0x41, char: 0x01, state: leftCtrlPressed | leftAltPressed},
			wantKey:     KeyCtrlA,
			wantCtrlAlt: true,
		},
		{
			name:        "Ctrl+Alt+Delete",
			kd:          keyData{down: 1, vk: 0x2E, char: 0, state: leftCtrlPressed | leftAltPressed},
			wantKey:     KeyDelete,
			wantCtrlAlt: false, // Navigation keys don't set ctrlAlt
		},
		{
			name:        "Ctrl+Alt+1",
			kd:          keyData{down: 1, vk: 0x31, char: 0, state: leftCtrlPressed | leftAltPressed},
			wantKey:     0,
			wantCtrlAlt: false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := translate(&tt.kd)
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
			if result.ctrlAlt != tt.wantCtrlAlt {
				t.Errorf("translate() ctrlAlt = %v, want %v", result.ctrlAlt, tt.wantCtrlAlt)
			}
		})
	}
}

func TestTranslateRightModifiers(t *testing.T) {
	tests := []struct {
		name    string
		kd      keyData
		wantKey Key
	}{
		{
			name:    "Right Ctrl+A",
			kd:      keyData{down: 1, vk: 0x41, char: 0x01, state: rightCtrlPressed},
			wantKey: KeyCtrlA,
		},
		{
			name:    "Right Ctrl+C",
			kd:      keyData{down: 1, vk: 0x43, char: 0x03, state: rightCtrlPressed},
			wantKey: KeyCtrlC,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := translate(&tt.kd)
			if result.k != tt.wantKey {
				t.Errorf("translate() key = %v, want %v", result.k, tt.wantKey)
			}
		})
	}
}

func TestTranslateKeyDown(t *testing.T) {
	// Key up events (down = 0) should be ignored by pollEvents
	kd := keyData{down: 0, vk: 0x41, char: 'a', state: 0}
	result := translate(&kd)

	// translate still processes it, but pollEvents filters it out
	if result.ch != 'a' {
		t.Errorf("translate() should still process key up events, got char = %v", result.ch)
	}
}

func TestTranslateRepeatCount(t *testing.T) {
	// Test that repeat count is handled (this is tested in pollEvents, not translate)
	kd := keyData{down: 1, vk: 0x41, char: 'a', count: 3, state: 0}
	result := translate(&kd)

	if result.ch != 'a' {
		t.Errorf("translate() char = %v, want 'a'", result.ch)
	}
	// Note: repeat count handling is in pollEvents, not translate
}

func TestModifierStateDetection(t *testing.T) {
	tests := []struct {
		name        string
		state       uint32
		wantAltGr   bool
		wantCtrl    bool
		wantCtrlAlt bool
	}{
		{
			name:      "No modifiers",
			state:     0,
			wantAltGr: false,
			wantCtrl:  false,
		},
		{
			name:      "Left Ctrl only",
			state:     leftCtrlPressed,
			wantAltGr: false,
			wantCtrl:  true,
		},
		{
			name:      "Right Alt + Left Ctrl (AltGr)",
			state:     rightAltPressed | leftCtrlPressed,
			wantAltGr: true,
			wantCtrl:  false,
		},
		{
			name:        "Left Ctrl + Left Alt",
			state:       leftCtrlPressed | leftAltPressed,
			wantAltGr:   false,
			wantCtrl:    true,
			wantCtrlAlt: true,
		},
		{
			name:      "Shift only",
			state:     shiftPressed,
			wantAltGr: false,
			wantCtrl:  false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			kd := keyData{down: 1, vk: 0x41, char: 'a', state: tt.state}
			result := translate(&kd)

			// For AltGr, character should be returned
			if tt.wantAltGr && result.ch == 0 {
				t.Errorf("AltGr combination should return character")
			}

			// For Ctrl+Alt, flag should be set
			if tt.wantCtrlAlt && !result.ctrlAlt {
				t.Errorf("Ctrl+Alt combination should set ctrlAlt flag")
			}
		})
	}
}

func TestGetKeyPanicsWhenNotOpen(t *testing.T) {
	// Ensure keyboard is closed
	Close()
	isOpen = false

	defer func() {
		if r := recover(); r == nil {
			t.Errorf("GetKey() should panic when called before Open()")
		}
	}()

	GetKey()
}

func TestGetKeyWithModifiersPanicsWhenNotOpen(t *testing.T) {
	// Ensure keyboard is closed
	Close()
	isOpen = false

	defer func() {
		if r := recover(); r == nil {
			t.Errorf("GetKeyWithModifiers() should panic when called before Open()")
		}
	}()

	GetKeyWithModifiers()
}
