package config

import "testing"

func TestConfigurationValidation(t *testing.T) {
	for _, input := range []map[string]string{{"SINGVERSE_DB_DRIVER": "postgres"}, {"SQLITE_PATH": ":memory:"}, {"MYSQL_PORT": "-1"}, {"SINGVERSE_DB_DRIVER": "mysql"}, {"SINGVERSE_DIAGNOSTICS_ADDRESS": "0.0.0.0:9090"}, {"SINGVERSE_PUBLIC_ORIGIN": "https://example.com/path"}, {"SINGVERSE_TRUSTED_PROXIES": "*"}} {
		if _, err := Parse(func(k string) string { return input[k] }); err == nil {
			t.Errorf("accepted invalid configuration %v", input)
		}
	}
	c, err := Parse(func(string) string { return "" })
	if err != nil {
		t.Fatal(err)
	}
	if c.Driver != "sqlite" || c.Address != "127.0.0.1:8080" || c.DiagnosticsAddress != "" {
		t.Fatal("unsafe defaults")
	}
}
