package main

import (
	"fmt"
	"os"

	"golang.org/x/sys/unix"
	"golang.org/x/term"
)

func readHiddenPassword(fd int, prompt string) (password []byte, err error) {
	previous, err := unix.IoctlGetTermios(fd, readTermios)
	if err != nil {
		return nil, err
	}
	hidden := *previous
	hidden.Lflag &^= unix.ECHO | unix.ECHONL
	if err = unix.IoctlSetTermios(fd, writeTermios, &hidden); err != nil {
		return nil, err
	}
	defer func() {
		if restoreErr := unix.IoctlSetTermios(fd, writeTermios, previous); restoreErr != nil && err == nil {
			clear(password)
			password, err = nil, restoreErr
		}
	}()
	// A fast typist or a PTY can reply as soon as the prompt is visible.
	// Disable echo before publishing it, including between the two prompts.
	if _, err = fmt.Fprint(os.Stderr, prompt); err != nil {
		return nil, err
	}
	password, err = term.ReadPassword(fd)
	fmt.Fprintln(os.Stderr)
	return password, err
}
