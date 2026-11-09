package party

import (
	"math"
	"sync/atomic"
	"time"
)

type Prefetch interface {
	PrefetchOriginal(song, owner string, active func() bool) error
	CancelOriginalPrefetch(owner string)
}
type prefetchTarget struct{ attempt, item, song string }
type prefetchTicket struct {
	active    atomic.Bool
	heartbeat atomic.Int64
}
type prefetchSlot struct {
	revision          int64
	updated, progress time.Time
	reported          bool
	attempt, blocked  string
	target            prefetchTarget
	attempted         map[prefetchTarget]bool
	ticket            *prefetchTicket
}
type prefetchUpdate struct {
	session, attempt        string
	revision                int64
	target                  prefetchTarget
	trigger, stop, progress bool
}

func (s *Service) SetPrefetch(p Prefetch) { s.prefetchMu.Lock(); s.prefetch = p; s.prefetchMu.Unlock() }
func (s *Service) prefetchChanged(session document, stop, progress bool) {
	p := object(session, "playback")
	attempt := str(p, "attemptId")
	current := false
	var next document
	for _, raw := range array(session, "queue") {
		v := raw.(map[string]any)
		if str(v, "id") == str(p, "itemId") && str(v, "status") == "PLAYING" {
			current = true
		}
		if next == nil && str(v, "status") == "WAITING" {
			next = v
		}
	}
	state := str(p, "state")
	eligible := str(session, "status") == "ACTIVE" && boolean(p, "confirmed") && attempt != "" && current && (state == "PLAYING" || state == "PAUSED" || state == "BUFFERING")
	target := prefetchTarget{}
	if eligible && next != nil {
		target = prefetchTarget{attempt, str(next, "id"), str(next, "songId")}
	}
	duration, position := number(p, "durationMs"), number(p, "positionMs")
	trigger := state == "PLAYING" && duration > 0 && duration <= 86400000 && position > 0 && position < duration && duration-position <= 30000
	update := prefetchUpdate{str(session, "id"), attempt, number(session, "revision"), target, trigger, stop, progress}
	s.postCommit = append(s.postCommit, func() { s.applyPrefetch(update) })
}
func (s *Service) cancelPrefetch(sid string, slot *prefetchSlot) {
	if slot.ticket == nil {
		return
	}
	slot.ticket.active.Store(false)
	slot.ticket = nil
	if s.prefetch != nil {
		s.prefetch.CancelOriginalPrefetch("tv:" + sid)
	}
}
func (s *Service) applyPrefetch(u prefetchUpdate) {
	s.prefetchMu.Lock()
	defer s.prefetchMu.Unlock()
	if s.closed {
		return
	}
	slot := s.slots[u.session]
	if slot == nil {
		slot = &prefetchSlot{revision: -1, attempted: map[prefetchTarget]bool{}}
		s.slots[u.session] = slot
	}
	if u.revision <= slot.revision {
		return
	}
	slot.revision = u.revision
	slot.updated = time.Now()
	if slot.attempt != u.attempt {
		slot.attempt = u.attempt
		slot.attempted = map[prefetchTarget]bool{}
		slot.reported = false
	}
	if u.progress {
		slot.progress = slot.updated
		slot.reported = true
	}
	if u.stop {
		slot.blocked = u.attempt
	}
	target := u.target
	if u.attempt == slot.blocked {
		target = prefetchTarget{}
	}
	if slot.target != target {
		s.cancelPrefetch(u.session, slot)
		slot.target = target
	}
	if target.attempt == "" {
		return
	}
	if slot.ticket != nil {
		slot.ticket.heartbeat.Store(slot.progress.UnixNano())
		return
	}
	if !u.trigger || !slot.reported || slot.updated.Sub(slot.progress) > 30*time.Second || slot.attempted[target] || s.prefetch == nil {
		return
	}
	slot.attempted[target] = true
	ticket := &prefetchTicket{}
	ticket.active.Store(true)
	ticket.heartbeat.Store(slot.progress.UnixNano())
	slot.ticket = ticket
	if err := s.prefetch.PrefetchOriginal(target.song, "tv:"+u.session, func() bool {
		return ticket.active.Load() && time.Since(time.Unix(0, ticket.heartbeat.Load())) <= 30*time.Second
	}); err != nil {
		s.cancelPrefetch(u.session, slot)
	}
}
func (s *Service) revokePrefetch(sid string) {
	s.applyPrefetch(prefetchUpdate{session: sid, revision: math.MaxInt64, stop: true})
}
func (s *Service) expirePrefetch() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-s.stop:
			return
		case <-ticker.C:
			s.prefetchMu.Lock()
			for key, slot := range s.slots {
				if slot.ticket != nil && time.Since(time.Unix(0, slot.ticket.heartbeat.Load())) > 30*time.Second {
					s.cancelPrefetch(key, slot)
				}
				if time.Since(slot.updated) > 24*time.Hour {
					delete(s.slots, key)
				}
			}
			s.prefetchMu.Unlock()
		}
	}
}
func (s *Service) Close() error {
	s.closeOnce.Do(func() {
		close(s.stop)
		s.prefetchMu.Lock()
		s.closed = true
		for key, slot := range s.slots {
			s.cancelPrefetch(key, slot)
		}
		s.slots = map[string]*prefetchSlot{}
		s.prefetchMu.Unlock()
		s.closeDiscovery()
	})
	return nil
}
