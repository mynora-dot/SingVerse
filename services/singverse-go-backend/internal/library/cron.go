package library

import (
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"

	"github.com/gin-gonic/gin"
)

type cronField map[int]bool

func parseCronField(raw string, min, max int, names map[string]int) (cronField, error) {
	out := cronField{}
	raw = strings.ToUpper(raw)
	for name, n := range names {
		raw = strings.ReplaceAll(raw, name, strconv.Itoa(n))
	}
	for _, part := range strings.Split(raw, ",") {
		step := 1
		pieces := strings.Split(part, "/")
		if len(pieces) > 2 {
			return nil, bad("cron 表达式无效")
		}
		if len(pieces) == 2 {
			n, e := strconv.Atoi(pieces[1])
			if e != nil || n < 1 || n > max-min+1 {
				return nil, bad("cron 步长无效")
			}
			step = n
		}
		a, b := min, max
		if pieces[0] != "*" {
			r := strings.Split(pieces[0], "-")
			n, e := strconv.Atoi(r[0])
			if e != nil {
				return nil, bad("cron 范围无效")
			}
			a = n
			b = n
			if len(r) == 2 {
				b, e = strconv.Atoi(r[1])
				if e != nil {
					return nil, bad("cron 范围无效")
				}
			} else if len(r) != 1 {
				return nil, bad("cron 范围无效")
			}
			if len(pieces) == 2 && len(r) == 1 {
				b = max
			}
		}
		if a < min || a > max || b < a || b > max {
			return nil, bad("cron 范围无效")
		}
		for n := a; n <= b; n += step {
			out[n] = true
		}
	}
	return out, nil
}
func nextCron(expr string, after time.Time) (time.Time, error) {
	fields := strings.Fields(expr)
	if len(fields) != 5 || len(expr) > 120 {
		return time.Time{}, bad("cron 必须为五段格式：分 时 日 月 周")
	}
	for _, r := range expr {
		if !(r >= '0' && r <= '9') && !(r >= 'a' && r <= 'z') && !(r >= 'A' && r <= 'Z') && !strings.ContainsRune("*/ ,-", r) {
			return time.Time{}, bad("cron 表达式包含不支持的字符")
		}
	}
	monthNames := map[string]int{"JAN": 1, "FEB": 2, "MAR": 3, "APR": 4, "MAY": 5, "JUN": 6, "JUL": 7, "AUG": 8, "SEP": 9, "OCT": 10, "NOV": 11, "DEC": 12}
	dayNames := map[string]int{"SUN": 0, "MON": 1, "TUE": 2, "WED": 3, "THU": 4, "FRI": 5, "SAT": 6}
	mins, e := parseCronField(fields[0], 0, 59, nil)
	if e != nil {
		return time.Time{}, e
	}
	hours, e := parseCronField(fields[1], 0, 23, nil)
	if e != nil {
		return time.Time{}, e
	}
	days, e := parseCronField(fields[2], 1, 31, nil)
	if e != nil {
		return time.Time{}, e
	}
	months, e := parseCronField(fields[3], 1, 12, monthNames)
	if e != nil {
		return time.Time{}, e
	}
	weeks, e := parseCronField(fields[4], 0, 7, dayNames)
	if e != nil {
		return time.Time{}, e
	}
	if weeks[7] {
		weeks[0] = true
	}
	zone, e := time.LoadLocation("Asia/Shanghai")
	if e != nil {
		return time.Time{}, e
	}
	local := after.In(zone)
	start := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, zone)
	end := start.AddDate(8, 0, 0)
	for day := start; day.Before(end); day = day.AddDate(0, 0, 1) {
		if !months[int(day.Month())] {
			continue
		}
		dateMatch, weekMatch := days[day.Day()], weeks[int(day.Weekday())]
		match := dateMatch && weekMatch
		if !strings.HasPrefix(fields[2], "*") && !strings.HasPrefix(fields[4], "*") {
			match = dateMatch || weekMatch
		}
		if !match {
			continue
		}
		for hour := 0; hour < 24; hour++ {
			if !hours[hour] {
				continue
			}
			for minute := 0; minute < 60; minute++ {
				if !mins[minute] {
					continue
				}
				candidate := time.Date(day.Year(), day.Month(), day.Day(), hour, minute, 0, 0, zone)
				if candidate.After(after) {
					return candidate.UTC(), nil
				}
			}
		}
	}
	return time.Time{}, bad("cron 表达式没有可执行日期")
}
func (s *Service) schedule() {
	defer s.wg.Done()
	timer := time.NewTicker(30 * time.Second)
	defer timer.Stop()
	prune := time.NewTicker(time.Hour)
	defer prune.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-timer.C:
			s.runDue()
		case <-prune.C:
			if _, e := s.db.ExecContext(s.ctx, s.q["scan-prune"], time.Now().AddDate(0, 0, -7).UTC().Format(time.RFC3339Nano)); e != nil && s.ctx.Err() == nil {
				s.log.Warn("scan history cleanup failed")
			}
		}
	}
}
func (s *Service) runDue() {
	type due struct{ id, cron, next, kind string }
	rows, e := s.db.QueryContext(s.ctx, s.q["source-due"], now())
	if e != nil {
		return
	}
	jobs := []due{}
	for rows.Next() {
		var v due
		if rows.Scan(&v.id, &v.cron, &v.next, &v.kind) == nil {
			jobs = append(jobs, v)
		}
	}
	rows.Close()
	for _, v := range jobs {
		if s.ctx.Err() != nil {
			return
		}
		next, e := nextCron(v.cron, time.Now())
		if e != nil {
			continue
		}
		r, e := s.db.ExecContext(s.ctx, s.q["source-claim"], next.Format(time.RFC3339Nano), v.id, v.next)
		if e != nil {
			continue
		}
		n, _ := r.RowsAffected()
		if n != 1 {
			continue
		}
		if v.kind == "WEBDAV" {
			if _, e = s.startTask(s.ctx, v.id); e != nil {
				_, _ = s.db.ExecContext(s.ctx, s.q["source-result"], now(), "SKIPPED", safeError(e), v.id)
			}
		} else {
			s.wg.Add(1)
			go func(id string) {
				defer s.wg.Done()
				_, e := s.scanLocal(s.ctx, id)
				if e != nil && s.ctx.Err() == nil {
					s.log.Warn("scheduled scan failed", "sourceId", id)
				}
			}(v.id)
		}
	}
}
func safeError(e error) string {
	if p, ok := e.(*problem); ok {
		return p.detail
	}
	return "扫描失败，请检查来源后重试"
}
func (s *Service) scanLocalHTTP(c *gin.Context) {
	result, e := s.scanLocal(c.Request.Context(), c.Param("id"))
	if e != nil {
		s.fail(c, e)
		return
	}
	c.JSON(200, result)
}
