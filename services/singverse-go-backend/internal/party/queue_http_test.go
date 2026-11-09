package party

import (
	"fmt"
	"reflect"
	"testing"
)

func TestQueueCommandsPreserveOrderingPermissionsAndPlayback(t *testing.T) {
	fixture := newPartyFixture(t)
	device, _ := fixture.device(t)
	session := want(t, fixture.request(t, "POST", "/api/v1/tv/sessions", nil, device, false, false), 200)
	sessionID := str(session, "id")
	base := "/api/v1/tv/sessions/" + sessionID
	invitation := want(t, fixture.request(t, "POST", base+"/invitations", nil, device, false, false), 200)
	join := func(name string) document {
		return want(t, fixture.request(t, "POST", "/api/v1/party/join", document{
			"invitation": invitation["invitation"], "name": name,
		}, "", false, false), 200)
	}
	firstGuest, secondGuest := join("小明"), join("小红")
	guestToken := str(firstGuest, "token")

	var revision int64
	requests := 0
	send := func(bearer, action string, fields document, status int) document {
		t.Helper()
		requests++
		input := document{"requestId": fmt.Sprintf("command-%d", requests), "expectedRevision": revision, "action": action}
		for key, value := range fields {
			input[key] = value
		}
		result := want(t, fixture.request(t, "POST", base+"/commands", input, bearer, false, false), status)
		if status == 200 {
			if number(result, "revision") != revision+1 {
				t.Fatalf("%s did not advance the queue revision once: %v", action, result)
			}
			revision = number(result, "revision")
		}
		return result
	}
	queueIDs := func(result document) []string {
		var ids []string
		for _, raw := range array(result, "queue") {
			ids = append(ids, str(raw.(map[string]any), "id"))
		}
		return ids
	}

	first := queueIDs(send(guestToken, "ADD", document{"songId": "song-a"}, 200))[0]
	second := queueIDs(send(str(secondGuest, "token"), "ADD", document{"songId": "song-b"}, 200))[1]
	third := queueIDs(send(device, "ADD", document{"songId": "song-a"}, 200))[2]
	send(guestToken, "REMOVE", document{"itemId": second}, 403)
	send(device, "ADD", document{"songId": "song-b", "expectedRevision": revision - 1}, 409)
	for _, step := range []struct {
		action, item string
		order        []string
	}{
		{"TOP", third, []string{third, first, second}},
		{"DOWN", first, []string{third, second, first}},
		{"UP", first, []string{third, first, second}},
		{"TOP", second, []string{second, third, first}},
	} {
		result := send(device, step.action, document{"itemId": step.item}, 200)
		if got := queueIDs(result); !reflect.DeepEqual(got, step.order) {
			t.Fatalf("%s order = %v, want %v", step.action, got, step.order)
		}
	}
	send(device, "LOCK", nil, 200)
	send(guestToken, "ADD", document{"songId": "song-a"}, 409)
	send(device, "UNLOCK", nil, 200)
	send(guestToken, "REMOVE", document{"itemId": first}, 200)
	send(device, "HOST", document{"participantId": firstGuest["id"]}, 200)
	send(guestToken, "NEXT", nil, 200)
	send(guestToken, "PAUSE", nil, 409)
	send(guestToken, "START", nil, 403)
	started := send(device, "START", nil, 200)
	if str(object(started, "playback"), "itemId") != second {
		t.Fatal("playback did not select the first waiting song", started)
	}
	send(guestToken, "PAUSE", nil, 200)
	send(guestToken, "RESUME", nil, 200)
	restarted := send(guestToken, "RESTART", nil, 200)
	if str(object(restarted, "playback"), "attemptId") == str(object(started, "playback"), "attemptId") {
		t.Fatal("restart retained the old playback attempt", restarted)
	}
	send(guestToken, "STOP", nil, 200)
	send(guestToken, "CLEAR", nil, 403)
	cleared := send(device, "CLEAR", nil, 200)
	for _, raw := range array(cleared, "queue") {
		if str(raw.(map[string]any), "status") == "WAITING" {
			t.Fatal("clear left a waiting song", cleared)
		}
	}
	send(device, "REVOKE", document{"participantId": secondGuest["id"]}, 200)
	want(t, fixture.request(t, "GET", base+"/snapshot", nil, str(secondGuest, "token"), false, false), 401)
	send(guestToken, "END", nil, 403)
	ended := send(device, "END", nil, 200)
	if str(ended, "status") != "ENDED" {
		t.Fatal("party remained active", ended)
	}
}
