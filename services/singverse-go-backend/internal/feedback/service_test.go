package feedback

import (
	"context"
	"errors"
	"github.com/jmoiron/sqlx"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/testutil"
	"sync"
	"testing"
)

func ptr(s string) *string { return &s }
func fixture(t *testing.T) *Service {
	t.Helper()
	return fixtureDB(t, testutil.Database(t))
}
func fixtureDB(t *testing.T, db *sqlx.DB) *Service {
	t.Helper()
	s, err := New(db, func(ctx context.Context, tx store.DBTX, id string) (Song, error) {
		if id == "existing" {
			return Song{"已补齐歌曲", []string{"歌手"}}, nil
		}
		return Song{}, errors.New("missing")
	})
	if err != nil {
		t.Fatal(err)
	}
	return s
}
func input() Create {
	return Create{RequestID: "12345678-1234-1234-1234-123456789abc", Title: ptr("中国"), Artist: ptr("歌手"), VersionLabel: ptr(""), Description: ptr("需要这首歌"), SubmittedBy: ptr("家人")}
}
func TestIdempotentConcurrentSubmission(t *testing.T) {
	testIdempotentConcurrentSubmission(t, fixture(t))
}
func TestMySQLIdempotentConcurrentSubmission(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testIdempotentConcurrentSubmission(t, fixtureDB(t, db))
}
func testIdempotentConcurrentSubmission(t *testing.T, s *Service) {
	t.Helper()
	ctx := context.Background()
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			v, err := s.Create(ctx, input())
			if err != nil || v.Revision != 1 {
				t.Errorf("create %v %v", v, err)
			}
		}()
	}
	wg.Wait()
	page, err := s.List(ctx, "zhongguo", "ALL", 0, 20)
	if err != nil || page.TotalElements != 1 || len(page.Items) != 1 {
		t.Fatal(page, err)
	}
	different := input()
	different.Title = ptr("不同歌曲")
	_, err = s.Create(ctx, different)
	var problem *failure
	if !errors.As(err, &problem) || problem.status != 409 {
		t.Fatal(err)
	}
}
func TestRevisionAndResolution(t *testing.T) {
	testRevisionAndResolution(t, fixture(t))
}
func TestMySQLRevisionAndResolution(t *testing.T) {
	db, _ := testutil.MySQLDatabase(t)
	testRevisionAndResolution(t, fixtureDB(t, db))
}
func testRevisionAndResolution(t *testing.T, s *Service) {
	t.Helper()
	ctx := context.Background()
	v, err := s.Create(ctx, input())
	if err != nil {
		t.Fatal(err)
	}
	v, err = s.Update(ctx, v.ID, Update{Revision: 1, Status: "RESOLVED", HandlingNote: ptr("处理完成"), ResolvedSongID: ptr("existing")})
	if err != nil || v.Revision != 2 || v.ResolvedAt == nil || v.ResolvedSongTitle == nil || *v.ResolvedSongTitle != "已补齐歌曲" {
		t.Fatal(v, err)
	}
	_, err = s.Update(ctx, v.ID, Update{Revision: 1, Status: "CLOSED", HandlingNote: ptr("")})
	var problem *failure
	if !errors.As(err, &problem) || problem.status != 409 {
		t.Fatal(err)
	}
	v, err = s.Update(ctx, v.ID, Update{Revision: 2, Status: "PENDING", HandlingNote: ptr("")})
	if err != nil || v.ResolvedAt != nil || v.ResolvedSongID != nil || len(v.ResolvedSongArtists) != 0 {
		t.Fatal(v, err)
	}
}
func TestSubmissionRequiresAllRawFields(t *testing.T) {
	s := fixture(t)
	v := input()
	v.Artist = nil
	if _, err := s.Create(context.Background(), v); err == nil {
		t.Fatal("missing field accepted")
	}
	if _, err := s.List(context.Background(), "", "ALL", -1, 20); err == nil {
		t.Fatal("negative page accepted")
	}
}
