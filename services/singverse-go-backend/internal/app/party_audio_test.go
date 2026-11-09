package app

import (
	"net/http"
	"os"
	"path/filepath"
	"testing"
)

func TestPartyAudioRolesPreserveBearerAndPlaybackBoundaries(t *testing.T) {
	a := application(t)
	admin := newBusinessClient(t, a)
	admin.call("POST", "/api/v1/auth/login", map[string]any{"username": "admin", "password": "admin"}, "", 200)
	directory, err := filepath.EvalSymlinks(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(filepath.Join(directory, "歌手 - 音轨验收.mp4"), []byte("isolated source"), 0600); err != nil {
		t.Fatal(err)
	}
	source := admin.call("POST", "/api/v1/media-sources", map[string]any{"name": "音轨验收", "type": "LOCAL", "location": directory}, "", 201)
	admin.call("POST", "/api/v1/media-sources/"+stringField(source, "id")+"/scan", map[string]any{}, "", 200)
	songs := admin.call("GET", "/api/v1/media-sources/"+stringField(source, "id")+"/songs", nil, "", 200)
	items, _ := songs["items"].([]any)
	if len(items) != 1 {
		t.Fatal(songs)
	}
	songID := stringField(object(items[0]), "id")
	authorization := admin.call("POST", "/api/v1/device-authorizations", map[string]any{"name": "音轨验收电视"}, "", 200)
	authorizationID := stringField(authorization, "id")
	admin.call("POST", "/api/v1/admin/device-authorizations/"+authorizationID+"/approve", map[string]any{"code": authorization["code"]}, "", 200)
	grant := admin.call("POST", "/api/v1/device-authorizations/"+authorizationID+"/poll", map[string]any{"pollSecret": authorization["pollSecret"]}, "", 200)
	deviceToken := stringField(grant, "token")
	session := admin.call("POST", "/api/v1/tv/sessions", map[string]any{}, deviceToken, 200)
	invite := admin.call("POST", "/api/v1/tv/sessions/"+stringField(session, "id")+"/invitations", map[string]any{}, deviceToken, 200)
	joined := admin.call("POST", "/api/v1/party/join", map[string]any{"invitation": invite["invitation"], "name": "音轨订正亲友"}, "", 200)
	guestToken := stringField(joined, "token")
	// H5 sends credentials: omit. Its explicit participant Bearer must work
	// independently of the administrator Cookie used to approve the television.
	guest := &businessClient{t: t, origin: admin.origin, client: &http.Client{}}
	partyPath := "/api/v1/party/audio-roles/songs/" + songID
	tvPath := "/api/v1/tv/audio-roles/songs/" + songID
	initial := guest.call("GET", partyPath, nil, guestToken, 200)
	if initial["state"] != "UNKNOWN" || initial["revision"] != float64(0) {
		t.Fatal(initial)
	}
	// The compute observation is an isolated fixture. Role reads, revision checks,
	// correction and persistence below all run through the real application HTTP.
	probeID := requestID()
	if _, err = a.db.Exec(`INSERT INTO media_audio_job(id,song_id,kind,state,fingerprint,node_origin,result_json,error,created_at,updated_at) VALUES(?,?,'PROBE','SUCCEEDED',?,'','{"streams":[{"id":"stream:0","kind":"audio"},{"id":"stream:1","kind":"audio"}],"duration":180}','','0','0')`, probeID, songID, initial["fingerprint"]); err != nil {
		t.Fatal(err)
	}
	observed := guest.call("GET", partyPath, nil, guestToken, 200)
	if streams, _ := observed["streams"].([]any); len(streams) != 2 {
		t.Fatal(observed)
	}
	correction := map[string]any{"originalId": "stream:0", "backingId": "stream:1", "revision": 0}
	saved := guest.call("PUT", partyPath, correction, guestToken, 200)
	if saved["revision"] != float64(1) || saved["origin"] != "MANUAL" || saved["originalId"] != "stream:0" || saved["backingId"] != "stream:1" {
		t.Fatal(saved)
	}
	guest.call("PUT", partyPath, correction, guestToken, 409)
	// Java's TV alias also authenticates either participant or device Bearers.
	alias := guest.call("GET", tvPath, nil, guestToken, 200)
	if alias["revision"] != float64(1) || alias["backingId"] != "stream:1" {
		t.Fatal(alias)
	}
	reversed := map[string]any{"originalId": "stream:1", "backingId": "stream:0", "revision": 1}
	guest.call("PUT", tvPath, reversed, guestToken, 200)
	for _, token := range []string{guestToken, deviceToken} {
		current := guest.call("GET", partyPath, nil, token, 200)
		if current["revision"] != float64(2) || current["originalId"] != "stream:1" || current["backingId"] != "stream:0" {
			t.Fatal(current)
		}
	}
	var original, backing string
	var revision int64
	if err = a.db.QueryRow("SELECT original_id,backing_id,revision FROM media_audio_role WHERE song_id=?", songID).Scan(&original, &backing, &revision); err != nil || original != "stream:1" || backing != "stream:0" || revision != 2 {
		t.Fatal("HTTP correction was not persisted", original, backing, revision, err)
	}
	// Shared role authorization does not grant the participant television media
	// access, offline versions, or another actor's transcode lease.
	transcodeID := requestID()
	if _, err = a.db.Exec(`INSERT INTO media_transcode(id,execution_id,song_id,owner_id,purpose,state,fingerprint,node_origin,input_json,target_json,result_json,error,renewed_at,created_at,updated_at) VALUES(?,?,?,?,'REALTIME','FAILED','fixture','','{}','{}','{}','',0,0,0)`, transcodeID, transcodeID, songID, "tv:"+stringField(grant, "deviceId")); err != nil {
		t.Fatal(err)
	}
	guest.call("GET", "/api/v1/tv/transcoding/"+transcodeID, nil, guestToken, 404)
	guest.call("POST", "/api/v1/tv/transcoding/"+transcodeID+"/cancel", map[string]any{}, guestToken, 404)
	guest.call("GET", "/api/v1/tv/transcoding/"+transcodeID+"/artifacts/master.m3u8", nil, guestToken, 404)
	for _, method := range []string{"GET", "HEAD"} {
		guest.call(method, "/api/v1/tv/transcoding/versions/"+transcodeID+"/"+songID, nil, guestToken, 403)
		// Accompaniment has the shared Bearer boundary, then validates its job.
		guest.call(method, tvPath+"/accompaniment/"+probeID, nil, guestToken, 404)
	}
	guest.call("GET", "/api/v1/tv/media/"+songID, nil, guestToken, 403)
	guest.call("POST", "/api/v1/tv/playback-resources", map[string]any{"songId": songID}, guestToken, 403)
	var state string
	if err = a.db.QueryRow("SELECT state FROM media_transcode WHERE id=?", transcodeID).Scan(&state); err != nil || state != "FAILED" {
		t.Fatal("foreign transcode was changed", state, err)
	}
	for _, path := range []string{partyPath, tvPath} {
		admin.call("GET", path, nil, "", 401)
	}
	admin.call("DELETE", "/api/v1/admin/tv-devices/"+stringField(grant, "deviceId"), nil, "", 200)
	for _, path := range []string{partyPath, tvPath} {
		for _, token := range []string{guestToken, deviceToken} {
			guest.call("GET", path, nil, token, 401)
			guest.call("PUT", path, reversed, token, 401)
		}
	}
}
