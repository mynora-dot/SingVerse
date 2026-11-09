package library

import (
	"context"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestBulkProjectionPreservesSongContract(t *testing.T) {
	s, r := newTestService(t)
	root := t.TempDir()
	for _, name := range []string{"周杰伦-晴天.mkv", "Unknown.mp3"} {
		if e := os.WriteFile(filepath.Join(root, name), []byte("audio"), 0600); e != nil {
			t.Fatal(e)
		}
	}
	src := localSource(t, r, root)
	requestJSON(t, r, "POST", "/api/v1/media-sources/"+src["id"].(string)+"/scan", nil, 200)
	songs, e := s.LibrarySongs(context.Background(), nil)
	if e != nil || len(songs) != 2 {
		t.Fatal(songs, e)
	}
	for _, song := range songs {
		single, e := s.Song(context.Background(), song["id"].(string))
		if e != nil {
			t.Fatal(e)
		}
		if !reflect.DeepEqual(single, song) {
			t.Fatalf("bulk diverged from Song contract\nsingle=%#v\nbulk=%#v", single, song)
		}
	}
}
