//go:build !linux && !darwin

package downloads

import "errors"

func renameNoReplace(from, to string) error {
	return errors.New("atomic no-replace rename is unavailable")
}
