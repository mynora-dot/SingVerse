// Package config reads explicit process configuration; it never loads .env.
package config

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

type Config struct {
	Driver                    string
	SQLitePath                string
	MySQLHost                 string
	MySQLPort                 int
	MySQLDatabase             string
	MySQLUser                 string
	MySQLPassword             string
	Address                   string
	AuthKeyFile               string
	PublicOrigin              string
	TrustedProxies            []string
	DBMateBin                 string
	MigrationsDir             string
	LockFile                  string
	DiagnosticsAddress        string
	MetadataPath              string
	MetadataCachePath         string
	TranscodePath             string
	SongCachePath             string
	SongCacheBytes            int64
	SongCacheMinimumFreeBytes int64
	AudioDerivedPath          string
	ComputeURL                string
	ComputeTokenFile          string
	CallbackOrigin            string
	AutoCallbackOrigin        string
	TVReleaseDirectory        string
	TVDiscoveryAddress        string
	TVDiscoveryPort           int
}

func Load() (Config, error) { return Parse(os.Getenv) }
func Parse(get func(string) string) (Config, error) {
	value := func(key, fallback string) string {
		if s := get(key); s != "" {
			return s
		}
		return fallback
	}
	root, err := findRoot()
	if err != nil {
		return Config{}, err
	}
	c := Config{Driver: value("SINGVERSE_DB_DRIVER", "sqlite"), SQLitePath: value("SQLITE_PATH", "./data/singverse.db"), MySQLHost: value("MYSQL_HOST", "127.0.0.1"), MySQLDatabase: value("MYSQL_DATABASE", "singverse"), MySQLUser: value("MYSQL_USER", "singverse"), MySQLPassword: get("MYSQL_PASSWORD"), PublicOrigin: get("SINGVERSE_PUBLIC_ORIGIN"), DBMateBin: value("DBMATE_BIN", filepath.Join(root, ".tools/dbmate")), MigrationsDir: value("SINGVERSE_MIGRATIONS_DIR", filepath.Join(root, "database/migrations")), DiagnosticsAddress: get("SINGVERSE_DIAGNOSTICS_ADDRESS"), MetadataPath: value("WEBDAV_METADATA_PATH", "./data/metadata/webdav"), MetadataCachePath: value("METADATA_CACHE_PATH", "./data/metadata/covers"), TranscodePath: value("TRANSCODE_VERSION_PATH", "./data/transcoding"), AudioDerivedPath: value("AUDIO_DERIVED_PATH", "./data/audio-roles"), ComputeURL: get("COMPUTE_NODE_URL"), ComputeTokenFile: get("COMPUTE_AUTH_TOKEN_FILE"), CallbackOrigin: get("SINGVERSE_CALLBACK_ORIGIN"), AutoCallbackOrigin: get("SINGVERSE_COMPUTE_AUTO_CALLBACK_ORIGIN"), TVReleaseDirectory: get("SINGVERSE_TV_RELEASE_DIRECTORY"), TVDiscoveryAddress: get("SINGVERSE_TV_DISCOVERY_ADDRESS")}
	if c.Driver != "sqlite" && c.Driver != "mysql" {
		return c, errors.New("SINGVERSE_DB_DRIVER must be sqlite or mysql")
	}
	port, err := strconv.Atoi(value("SERVER_PORT", "8080"))
	if err != nil || port < 0 || port > 65535 {
		return c, errors.New("invalid SERVER_PORT")
	}
	c.Address = net.JoinHostPort(value("SINGVERSE_BIND_ADDRESS", "127.0.0.1"), strconv.Itoa(port))
	c.MySQLPort, err = strconv.Atoi(value("MYSQL_PORT", "3306"))
	if err != nil || c.MySQLPort < 1 || c.MySQLPort > 65535 {
		return c, errors.New("invalid MYSQL_PORT")
	}
	c.TVDiscoveryPort, err = strconv.Atoi(value("SINGVERSE_TV_DISCOVERY_PORT", "18080"))
	if err != nil || c.TVDiscoveryPort < 1 || c.TVDiscoveryPort > 65535 {
		return c, errors.New("invalid TV discovery port")
	}
	if c.Driver == "mysql" && c.MySQLPassword == "" {
		return c, errors.New("MYSQL_PASSWORD is required")
	}
	if strings.Contains(c.SQLitePath, "\x00") || strings.HasPrefix(c.SQLitePath, "file:") || c.SQLitePath == ":memory:" {
		return c, errors.New("SQLITE_PATH must be a file path")
	}
	c.SQLitePath, err = filepath.Abs(c.SQLitePath)
	if err != nil {
		return c, err
	}
	c.LockFile = c.SQLitePath + ".auth.lock"
	keyDefault := filepath.Join(filepath.Dir(c.SQLitePath), "auth/master.key")
	if c.Driver == "mysql" {
		c.LockFile = filepath.Join(root, "data/auth/mysql.auth.lock")
		keyDefault = filepath.Join(root, "data/auth/master.key")
	}
	c.AuthKeyFile = value("SINGVERSE_AUTH_KEY_FILE", keyDefault)
	// dbmate runs in a private SQLite alias directory; configuration paths keep
	// their meaning relative to the service working directory.
	c.DBMateBin, err = filepath.Abs(c.DBMateBin)
	if err != nil {
		return c, err
	}
	c.MigrationsDir, err = filepath.Abs(c.MigrationsDir)
	if err != nil {
		return c, err
	}
	for _, s := range strings.Split(get("SINGVERSE_TRUSTED_PROXIES"), ",") {
		if s = strings.TrimSpace(s); s != "" {
			if net.ParseIP(s) == nil {
				_, block, err := net.ParseCIDR(s)
				if err != nil {
					return c, errors.New("invalid trusted proxy")
				}
				if ones, _ := block.Mask.Size(); ones == 0 {
					return c, errors.New("wildcard proxy trust is forbidden")
				}
			}
			c.TrustedProxies = append(c.TrustedProxies, s)
		}
	}
	if c.PublicOrigin != "" {
		u, e := url.Parse(c.PublicOrigin)
		if e != nil || u.Host == "" || u.Scheme != "https" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || u.Path != "" || u.RawPath != "" {
			return c, errors.New("invalid SINGVERSE_PUBLIC_ORIGIN")
		}
	}
	if c.DiagnosticsAddress != "" {
		host, _, e := net.SplitHostPort(c.DiagnosticsAddress)
		ip := net.ParseIP(host)
		if e != nil || ip == nil || !ip.IsLoopback() {
			return c, errors.New("diagnostics must bind an explicit loopback IP")
		}
	}
	c.SongCachePath = value("SINGVERSE_SONG_CACHE_PATH", filepath.Join(c.TranscodePath, "cache"))
	c.SongCacheBytes, err = strconv.ParseInt(value("SINGVERSE_SONG_CACHE_BYTES", "10737418240"), 10, 64)
	if err != nil || c.SongCacheBytes < 0 {
		return c, errors.New("invalid cache size")
	}
	c.SongCacheMinimumFreeBytes, err = strconv.ParseInt(value("SINGVERSE_SONG_CACHE_MINIMUM_FREE_BYTES", "2147483648"), 10, 64)
	if err != nil || c.SongCacheMinimumFreeBytes < 0 {
		return c, errors.New("invalid minimum free space")
	}
	return c, nil
}

func findRoot() (string, error) {
	var starts []string
	if cwd, e := os.Getwd(); e == nil {
		starts = append(starts, cwd)
	}
	if exe, e := os.Executable(); e == nil {
		starts = append(starts, filepath.Dir(exe))
	}
	for _, start := range starts {
		for p := start; ; p = filepath.Dir(p) {
			if st, e := os.Stat(filepath.Join(p, "database/migrations")); e == nil && st.IsDir() {
				return p, nil
			}
			if p == filepath.Dir(p) {
				break
			}
		}
	}
	return "", fmt.Errorf("database/migrations directory missing; run from the deployment directory")
}
