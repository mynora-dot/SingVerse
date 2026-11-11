package overview

import (
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
)

// TestMySQLOverview runs the same aggregation assertions as the SQLite suite
// against a real isolated MySQL database. It skips unless the dedicated MySQL
// gate (make go-mysql-check) supplies SINGVERSE_TEST_MYSQL_PORT.
func TestMySQLOverview(t *testing.T) {
	db, cfg := testutil.MySQLDatabase(t)
	exerciseOverview(t, db, "mysql", cfg)
}
