//go:build !windows
// +build !windows

package keyboard

// Key represents special keyboard keys
type Key uint16

// Stub for non-Windows platforms
func Open() error {
	return nil
}

func Close() {
}

func GetKey() (rune, Key, error) {
	return 0, 0, nil
}

func GetKeyWithModifiers() (rune, Key, bool, error) {
	return 0, 0, false, nil
}

func GetSingleKey() (rune, Key, error) {
	return 0, 0, nil
}
