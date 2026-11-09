package background

type projectionTimes struct {
	created  any
	updated  any
	finished any
	anchor   int64
}

func sourceProjectionTimes(source doc, clock int64) projectionTimes {
	times := projectionTimes{
		created:  timestamp(source["created_at"]),
		updated:  timestamp(source["updated_at"]),
		finished: timestamp(source["finished_at"]),
		anchor:   clock,
	}
	for _, value := range []any{times.created, times.updated, times.finished} {
		if value != nil {
			times.anchor = stamp(value)
		}
	}
	return times
}

func (p *taskProjection) applyCounts(kind string, source doc, groups []doc) {
	var total, executing int64
	for _, group := range groups {
		p.counts[text(group, "status")] = number(group, "amount")
		total += number(group, "amount")
		executing += number(group, "executing")
	}
	if len(p.counts) == 0 {
		return
	}

	pending := number(p.counts, "PENDING") + number(p.counts, "QUEUED")
	var running int64
	for _, status := range []string{"PROCESSING", "PROBING", "RESOLVING", "DOWNLOADING", "PUBLISHING"} {
		running += number(p.counts, status)
	}
	skipped := number(p.counts, "SKIPPED_EXISTING") + number(p.counts, "SKIPPED")
	var legacySkipped int64
	if kind == "METADATA" {
		legacySkipped = max(int64(0), number(source, "skipped_existing")-skipped)
	}
	total += legacySkipped
	p.row["total"] = total
	p.row["completed"] = total - pending - running
	p.row["progress_unit"] = "SONGS"
	if kind == "AUDIO_BATCH" {
		// A processing item may still be waiting for a compute worker.
		pending += running - executing
		running = executing
	}
	p.statistics = doc{
		"counts": p.counts, "pending": pending, "processing": running,
		"review": number(p.counts, "REVIEW"), "failed": number(p.counts, "FAILED"),
		"skipped": skipped + legacySkipped,
		"applied": number(p.counts, "AUTO_APPLIED") + number(p.counts, "MANUAL_APPLIED") + number(p.counts, "APPLIED"),
	}
	p.row["items_available"] = 1
	rawStatus := text(source, "raw_status")
	if rawStatus == "COMPLETED" && number(p.counts, "FAILED") > 0 {
		p.row["status"] = "PARTIAL"
	}
	if kind == "AUDIO_BATCH" && rawStatus == "QUEUED" && running > 0 {
		p.row["status"] = "RUNNING"
	}
}

func (p *taskProjection) applyTimes(source, previous doc, times projectionTimes, clock int64, historical bool) {
	created, updated, finished := times.created, times.updated, times.finished
	if created == nil {
		created = number(previous, "created_at")
	}
	p.row["created_at"] = created
	p.row["updated_at"] = previous["updated_at"]
	if len(previous) == 0 {
		p.row["updated_at"] = clock
		if created != nil {
			p.row["updated_at"] = created
		}
		if updated != nil {
			p.row["updated_at"] = updated
		}
	}
	p.row["started_at"] = previous["started_at"]
	if source["started_at"] != nil {
		p.row["started_at"] = timestamp(source["started_at"])
	}
	terminal := !active(text(p.row, "status"))
	if !historical && p.row["started_at"] == nil && text(p.row, "status") == "RUNNING" {
		p.row["started_at"] = clock
	}
	if finished == nil {
		finished = timestamp(previous["finished_at"])
	}
	if terminal && finished == nil && !historical && (len(previous) == 0 || active(text(previous, "status"))) {
		finished = clock
	}
	p.row["finished_at"], p.row["expires_at"] = nil, nil
	if terminal {
		p.row["finished_at"] = finished
		expiryAnchor := times.anchor
		if finished != nil {
			expiryAnchor = stamp(finished)
		}
		p.row["expires_at"] = expiryAnchor + retention
	}
	p.row["time_incomplete"] = 0
	if (historical && (p.row["started_at"] == nil || (terminal && finished == nil))) || number(previous, "time_incomplete") != 0 {
		p.row["time_incomplete"] = 1
	}
}
