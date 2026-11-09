package library

import (
	"bufio"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"

	"github.com/gin-gonic/gin"
)

type DirectoryEntry struct {
	Name string `json:"name"`
	Path string `json:"path"`
}
type Location struct {
	Name     string `json:"name"`
	Path     string `json:"path"`
	Mounted  bool   `json:"mounted"`
	Readable bool   `json:"readable"`
	Writable bool   `json:"writable"`
}
type DirectoryPage struct {
	Path          string           `json:"path"`
	Parent        *string          `json:"parent"`
	Items         []DirectoryEntry `json:"items"`
	HasMore       bool             `json:"hasMore"`
	Locations     []Location       `json:"locations"`
	Containerized bool             `json:"containerized"`
}

func (s *Service) directories(c *gin.Context) {
	location := c.Query("path")
	page, e := strconv.Atoi(c.DefaultQuery("page", "0"))
	filter := strings.ToLower(strings.TrimSpace(c.Query("q")))
	if e != nil || page < 0 || page > 10000 || len([]rune(filter)) > 100 || controls(filter) || len([]rune(location)) > 2048 || controls(location) {
		s.fail(c, bad("目录路径、页码或筛选条件无效"))
		return
	}
	out := DirectoryPage{Path: location, Items: []DirectoryEntry{}, Locations: []Location{}, Containerized: exists("/.dockerenv") || exists("/run/.containerenv")}
	if strings.TrimSpace(location) == "" {
		out.Path = ""
		out.Items = append(out.Items, DirectoryEntry{"/", "/"})
		out.Locations = locations()
		c.JSON(200, out)
		return
	}
	if !filepath.IsAbs(location) {
		s.fail(c, bad("请选择服务端绝对目录"))
		return
	}
	real, e := filepath.EvalSymlinks(location)
	if e != nil {
		s.fail(c, bad("目录不存在或无法读取，请检查挂载和权限"))
		return
	}
	real = filepath.Clean(real)
	entries, e := os.ReadDir(real)
	if e != nil {
		s.fail(c, bad("目录不存在或无法读取，请检查挂载和权限"))
		return
	}
	items := []DirectoryEntry{}
	for _, entry := range entries {
		if entry.Type()&os.ModeSymlink != 0 || !entry.IsDir() || !strings.Contains(strings.ToLower(entry.Name()), filter) {
			continue
		}
		dir, e := os.Open(filepath.Join(real, entry.Name()))
		if e != nil {
			continue
		}
		dir.Close()
		items = append(items, DirectoryEntry{entry.Name(), filepath.Join(real, entry.Name())})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].Name < items[j].Name })
	offset := page * 100
	if offset < len(items) {
		end := offset + 100
		if end > len(items) {
			end = len(items)
		}
		out.Items = items[offset:end]
		out.HasMore = end < len(items)
	}
	out.Path = real
	parent := filepath.Dir(real)
	if parent == real {
		parent = ""
	}
	out.Parent = &parent
	c.JSON(200, out)
}
func exists(path string) bool { _, e := os.Stat(path); return e == nil }
func locations() []Location {
	mounts := map[string]bool{}
	order := []string{"/media", "/downloads"}
	f, e := os.Open("/proc/self/mountinfo")
	if e == nil {
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			line := scanner.Text()
			fields := strings.Fields(line)
			if len(fields) < 7 || !strings.Contains(line, " - ") {
				continue
			}
			path := strings.NewReplacer(`\040`, " ", `\011`, "\t", `\012`, "\n", `\134`, `\`).Replace(fields[4])
			path = filepath.Clean(path)
			if !filepath.IsAbs(path) || path == "/" || controls(path) {
				continue
			}
			internal := false
			for _, root := range []string{"/proc", "/sys", "/dev", "/run", "/etc", "/secrets", "/data", "/opt/singverse"} {
				if path == root || strings.HasPrefix(path, root+"/") {
					internal = true
				}
			}
			if internal {
				continue
			}
			if _, ok := mounts[path]; !ok {
				order = append(order, path)
			}
			mounts[path] = strings.Contains(","+fields[5]+",", ",ro,")
		}
		f.Close()
	} else {
		order = append(order, "/Volumes")
	}
	out := []Location{}
	seen := map[string]bool{}
	for _, path := range order {
		if seen[path] {
			continue
		}
		seen[path] = true
		info, e := os.Lstat(path)
		if e != nil || !info.IsDir() {
			continue
		}
		name := filepath.Base(path)
		switch path {
		case "/media":
			name = "媒体目录"
		case "/downloads":
			name = "下载目录"
		case "/Volumes":
			name = "外部磁盘"
		}
		dir, e := os.Open(path)
		readable := e == nil
		if dir != nil {
			dir.Close()
		}
		ro, mounted := mounts[path]
		out = append(out, Location{name, path, mounted, readable, !ro && info.Mode().Perm()&0222 != 0})
	}
	return out
}
