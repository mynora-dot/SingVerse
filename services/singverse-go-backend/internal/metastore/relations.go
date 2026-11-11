package metastore

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/jmoiron/sqlx"
	"strings"

	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/metastandard"
	"github.com/mynora-dot/SingVerse/services/singverse-go-backend/internal/platform/store"
)

func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}

// ReferenceIDs includes the expected type, never guesses identity from names.
func ReferenceIDs(d metastandard.Document) map[string]string {
	out := map[string]string{}
	if d.ParentID != "" {
		if d.Type == "MEDIA" {
			out[d.ParentID] = "VARIANT"
		} else {
			out[d.ParentID] = "SONG"
		}
	}
	for name, kind := range map[string]string{"workId": "WORK", "releaseId": "RELEASE"} {
		if id, ok := d.Fields[name].(string); ok {
			out[id] = kind
		}
	}
	b, _ := json.Marshal(d.Fields)
	var fields struct {
		TagIDs  []string              `json:"tagIds"`
		Credits []metastandard.Credit `json:"credits"`
	}
	_ = json.Unmarshal(b, &fields)
	for _, id := range fields.TagIDs {
		out[id] = "TAG"
	}
	for _, credit := range fields.Credits {
		out[credit.ArtistID] = "ARTIST"
	}
	return out
}

func (s *Store) validateRelations(ctx context.Context, db store.DBTX, d metastandard.Document) error {
	if e := metastandard.Validate(d); e != nil {
		return &Problem{400, e.Error()}
	}
	for id, kind := range ReferenceIDs(d) {
		var actual string
		if e := db.QueryRowContext(ctx, s.q["document-type"], id).Scan(&actual); e != nil {
			return &Problem{400, fmt.Sprintf("关联的 %s 资料不存在", kind)}
		}
		if actual != kind || id == d.ID {
			return &Problem{400, "资料关联类型无效"}
		}
	}
	for _, field := range []string{"cover", "portrait", "lyricsAsset"} {
		b, _ := json.Marshal(d.Fields[field])
		var ref metastandard.AssetRef
		_ = json.Unmarshal(b, &ref)
		if ref.AssetID == "" {
			continue
		}
		var asset Asset
		if e := sqlx.GetContext(ctx, db, &asset, s.q["asset-read"], ref.AssetID); e != nil {
			return &Problem{400, "正式资料引用的资源尚未保存"}
		}
		if (field == "cover" || field == "portrait") != strings.HasPrefix(asset.MIME, "image/") {
			return &Problem{400, "资料资源的类型与字段不匹配"}
		}
		if field == "lyricsAsset" {
			_, data, e := s.ReadAsset(ctx, db, ref.AssetID)
			if e != nil {
				return e
			}
			if _, e = metastandard.LyricsAsset(asset.MIME, data); e != nil {
				return &Problem{400, e.Error()}
			}
		}
	}
	return nil
}
