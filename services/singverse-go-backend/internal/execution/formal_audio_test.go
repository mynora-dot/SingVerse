package execution

import (
	"testing"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastore"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func TestFormalSharedRolesRequireReceiverProbeAndLocalClearWins(t *testing.T) {
	f := executionFixture(t, "")
	ctx := t.Context()
	err := store.WithinTx(ctx, f.db, func(tx store.DBTX) error {
		_, _, err := f.library.Formal().Observe(ctx, tx, f.song, metastore.Evidence{Origin: metastandard.Origin{Kind: "SHARED", CollectionID: uuid()}, Revision: "1", Fields: map[string]any{"audioRoles": metastandard.AudioRoles("stream:2", "stream:1"), "durationMs": 120250, "observation": map[string]any{"durationMs": 120250, "streams": audioStreams(2)}}})
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	value, err := f.s.AudioStatus(ctx, f.song)
	if err != nil || value["state"] != "UNKNOWN" {
		t.Fatal("unverified shared role became playable", value, err)
	}
	f.s.testProbe(t, f.song, audioStreams(2))
	value, err = f.s.AudioStatus(ctx, f.song)
	if err != nil || value["state"] != "CONFIRMED" || value["originalId"] != "stream:2" {
		t.Fatal(value, err)
	}
	d, err := f.library.Formal().Read(ctx, f.db, f.song)
	if err != nil {
		t.Fatal(err)
	}
	err = store.WithinTx(ctx, f.db, func(tx store.DBTX) error {
		_, err := f.library.Formal().Update(ctx, tx, f.song, metastore.Update{ExpectedRevision: d.Revision, RequestID: uuid(), Operations: []metastore.Operation{{Field: "audioRoles", Op: "CLEAR"}}})
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	value, err = f.s.AudioStatus(ctx, f.song)
	if err != nil || value["state"] != "UNKNOWN" || value["originalId"] != nil {
		t.Fatal("legacy role cache bypassed formal clear", value, err)
	}
}
