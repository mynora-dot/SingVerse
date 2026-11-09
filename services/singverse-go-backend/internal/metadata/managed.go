package metadata

import (
	"os"
	"path/filepath"
	"strings"
)

// managedDirectory permits a configured root symlink but rejects links below that root.
func managedDirectory(root string, segments ...string) (string, error) {
	if e := os.MkdirAll(root, 0700); e != nil {
		return "", e
	}
	target, e := filepath.EvalSymlinks(root)
	if e != nil {
		return "", e
	}
	target, e = filepath.Abs(target)
	if e != nil {
		return "", e
	}
	for _, segment := range segments {
		if segment == "" || segment == "." || segment == ".." || strings.ContainsAny(segment, "/\\") {
			return "", bad("元数据衍生目录无效")
		}
		target = filepath.Join(target, segment)
		if e = os.Mkdir(target, 0700); e != nil && !os.IsExist(e) {
			return "", e
		}
		st, e := os.Lstat(target)
		if e != nil {
			return "", e
		}
		if !st.IsDir() || st.Mode()&os.ModeSymlink != 0 {
			return "", bad("元数据衍生目录无效")
		}
	}
	return target, nil
}
