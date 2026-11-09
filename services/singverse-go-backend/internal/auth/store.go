package auth

import (
	"context"
	"database/sql"
	"embed"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

//go:embed sql/*.sql
var sqlFiles embed.FS

var statements = func() map[string]string {
	data, err := sqlFiles.ReadFile("sql/queries.sql")
	if err != nil {
		panic(err)
	}
	result := map[string]string{}
	for _, part := range strings.Split(string(data), "-- name: ")[1:] {
		name, body, ok := strings.Cut(part, "\n")
		if !ok {
			panic("invalid auth SQL statement")
		}
		result[strings.TrimSpace(name)] = strings.TrimSpace(body)
	}
	return result
}()

type dbtx = store.DBTX

type account struct {
	Username        string         `db:"username"`
	PasswordHash    string         `db:"password_hash"`
	DefaultPassword int            `db:"default_password"`
	Secret          sql.NullString `db:"mfa_secret"`
	Pending         sql.NullString `db:"pending_secret"`
	PendingAttempts int            `db:"pending_attempts"`
	PendingExpires  int64          `db:"pending_expires"`
	LastStep        int64          `db:"last_totp_step"`
	Revision        int64          `db:"revision"`
}

func readAccount(ctx context.Context, q dbtx) (account, error) {
	var a account
	err := sqlx.GetContext(ctx, q, &a, statements["account"])
	return a, err
}

func exec(ctx context.Context, q dbtx, name string, args ...any) (int64, error) {
	statement, ok := statements[name]
	if !ok {
		return 0, fmt.Errorf("unknown auth statement %s", name)
	}
	r, err := q.ExecContext(ctx, statement, args...)
	if err != nil {
		return 0, err
	}
	return r.RowsAffected()
}

type sealedCredential struct{ value, purpose string }

func encryptedCredentials(ctx context.Context, q dbtx) ([]sealedCredential, error) {
	var stored []struct {
		Value   string `db:"credential_value"`
		Purpose string `db:"credential_purpose"`
	}
	if err := sqlx.SelectContext(ctx, q, &stored, statements["secrets"]); err != nil {
		return nil, err
	}
	var credentials []sealedCredential
	for _, v := range stored {
		credentials = append(credentials, sealedCredential{v.Value, v.Purpose})
	}
	rows, err := q.QueryxContext(ctx, statements["task_secrets"])
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var task struct {
			ConfigJSON string `db:"config_json"`
		}
		if err := rows.StructScan(&task); err != nil {
			return nil, err
		}
		var config struct {
			EncryptedKey      string `json:"encryptedKey"`
			CredentialPurpose string `json:"credentialPurpose"`
		}
		if err := json.Unmarshal([]byte(task.ConfigJSON), &config); err != nil {
			return nil, errors.New("invalid stored classification credentials")
		}
		if config.EncryptedKey != "" {
			if config.CredentialPurpose == "" {
				config.CredentialPurpose = "singverse:classification:v1"
			}
			credentials = append(credentials, sealedCredential{config.EncryptedKey, config.CredentialPurpose})
		}
	}
	return credentials, rows.Err()
}

func recordEvent(ctx context.Context, q dbtx, kind string, now int64) error {
	b, err := randomBytes(16)
	if err != nil {
		return err
	}
	// RFC 4122 UUID, compatible with the existing VARCHAR(36) audit identifier.
	b[6] = (b[6] & 15) | 64
	b[8] = (b[8] & 63) | 128
	id := fmt.Sprintf("%x-%x-%x-%x-%x", b[:4], b[4:6], b[6:8], b[8:10], b[10:])
	if _, err = exec(ctx, q, "event", id, kind, now); err != nil {
		return err
	}
	_, err = exec(ctx, q, "prune_events", now-90*86400000)
	return err
}

// ResetAdministrator is a local-only operation. The CLI must acquire the same
// deployment lock as the HTTP server and require an interactive TTY beforehand.
// It deliberately does not read or replace the credential master key.
func ResetAdministrator(ctx context.Context, db *sqlx.DB, password string) error {
	if err := ValidatePassword(password); err != nil {
		return err
	}
	hash, err := encodePassword(password)
	if err != nil {
		return err
	}
	tx, err := db.BeginTxx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	changed, err := exec(ctx, tx, "reset", hash)
	if err != nil {
		return err
	}
	if changed != 1 {
		return errors.New("administrator account missing")
	}
	for _, name := range []string{"revoke_all", "clear_recovery", "clear_all_limits"} {
		if _, err = exec(ctx, tx, name); err != nil {
			return err
		}
	}
	if err = recordEvent(ctx, tx, "local_account_recovery", timeNow()); err != nil {
		return err
	}
	return tx.Commit()
}
