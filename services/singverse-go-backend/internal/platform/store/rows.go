package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"regexp"
	"strings"

	"github.com/jmoiron/sqlx"
)

var parameter = regexp.MustCompile(`@\{([a-zA-Z_][a-zA-Z_0-9.]*)\}`)

// Bind adapts the embedded SQL's @{name} markers to sqlx named value binding.
// Literal colons are escaped for sqlx, so URLs, timestamps and JSON stay intact.
// Identifiers and SQL fragments never come from the parameter map.
func Bind(statement string, values map[string]any) (string, []any, error) {
	var missing string
	named := parameter.ReplaceAllStringFunc(strings.ReplaceAll(statement, ":", "::"), func(match string) string {
		name := match[2 : len(match)-1]
		_, ok := values[name]
		if !ok {
			missing = name
		}
		return ":" + name
	})
	if missing != "" {
		return "", nil, fmt.Errorf("SQL parameter %s is missing", missing)
	}
	query, args, err := sqlx.Named(named, values)
	if err != nil {
		// sqlx errors may include the argument map; never propagate credentials.
		return "", nil, errors.New("SQL parameter binding failed")
	}
	return query, args, nil
}

// Maps normalizes driver text values without retaining rows or their connection.
func Maps(ctx context.Context, db DBTX, statement string, args ...any) ([]map[string]any, error) {
	rows, err := db.QueryxContext(ctx, statement, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	columns, err := rows.Columns()
	if err != nil {
		return nil, err
	}
	types, err := rows.ColumnTypes()
	if err != nil {
		return nil, err
	}
	items := make([]map[string]any, 0)
	for rows.Next() {
		item := make(map[string]any, len(columns))
		if err = rows.MapScan(item); err != nil {
			return nil, err
		}
		for i, name := range columns {
			if data, ok := item[name].([]byte); ok {
				kind := strings.ToUpper(types[i].DatabaseTypeName())
				if kind == "" || kind == "BIT" || strings.Contains(kind, "BLOB") || strings.Contains(kind, "BINARY") {
					// Binary columns must remain bytes (covers, hashes and images).
					// Copy before advancing the cursor; drivers may reuse buffers.
					item[name] = append([]byte{}, data...)
				} else {
					item[name] = string(data)
				}
			}
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func Map(ctx context.Context, db DBTX, statement string, args ...any) (map[string]any, error) {
	rows, err := Maps(ctx, db, statement, args...)
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, sql.ErrNoRows
	}
	if len(rows) != 1 {
		return nil, fmt.Errorf("query returned %d rows; expected one", len(rows))
	}
	return rows[0], nil
}
