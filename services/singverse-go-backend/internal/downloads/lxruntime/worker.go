package lxruntime

import (
	"bufio"
	"context"
	_ "embed"
	"encoding/json"
	"io"
	"math"
	"regexp"
	"strings"
	"time"

	"modernc.org/quickjs"
)

//go:embed preload.js
var preload string

type workerTimer struct {
	at     time.Time
	delay  time.Duration
	repeat bool
}

// Worker runs the private stdin/stdout protocol before application startup.
// The caller must dispatch --lx-worker before opening databases or listeners.
func Worker(ctx context.Context, input io.Reader, output io.Writer) (err error) {
	defer func() {
		if recover() != nil {
			err = ErrExecution
		}
	}()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	inbox := make(chan frame, 64)
	go func() {
		defer close(inbox)
		scanner := bufio.NewScanner(input)
		scanner.Buffer(make([]byte, 4096), maxInputFrame+1)
		for scanner.Scan() {
			var f frame
			if json.Unmarshal(scanner.Bytes(), &f) != nil {
				break
			}
			select {
			case inbox <- f:
			case <-ctx.Done():
				return
			}
		}
	}()
	var load frame
	select {
	case received, ok := <-inbox:
		if !ok {
			return ErrExecution
		}
		load = received
	case <-ctx.Done():
		return ctx.Err()
	}
	if load.Kind != "load" || len(load.Script) == 0 || len(load.Script) > maxScript || (load.Environment != "mobile" && load.Environment != "desktop") {
		return ErrInvalid
	}
	vm, err := quickjs.NewVM()
	if err != nil {
		return ErrExecution
	}
	defer vm.Close()
	vm.SetMemoryLimit(256 << 20)
	vm.SetMaxStackSize(512)
	vm.SetCanBlock(false)
	_ = vm.SetEvalTimeout(10 * time.Second)
	interrupt := context.AfterFunc(ctx, vm.Interrupt)
	defer interrupt()
	writer := &frameWriter{out: output, limit: maxFrame}
	timers := map[int]workerTimer{}
	if err = vm.RegisterHostFunc("__lx_tool", func(args []any) (any, error) {
		if len(args) != 2 {
			return nil, ErrInvalid
		}
		operation, ok := args[0].(string)
		if !ok {
			return nil, ErrInvalid
		}
		payload, ok := args[1].(string)
		if !ok {
			return nil, ErrInvalid
		}
		return tool(operation, payload)
	}); err != nil {
		return ErrExecution
	}
	if err = vm.RegisterHostFunc("__lx_emit", func(args []any) (any, error) {
		if len(args) != 1 {
			return nil, ErrInvalid
		}
		value, ok := args[0].(string)
		if !ok || len(value) > maxFrame {
			return nil, ErrInvalid
		}
		var f frame
		if json.Unmarshal([]byte(value), &f) != nil {
			return nil, ErrInvalid
		}
		switch f.Kind {
		case "timer":
			if f.ID < 1 || len(timers) >= 64 || math.IsNaN(f.Delay) || math.IsInf(f.Delay, 0) {
				return nil, ErrInvalid
			}
			delay := time.Duration(math.Max(1, math.Min(30000, f.Delay))) * time.Millisecond
			timers[f.ID] = workerTimer{time.Now().Add(delay), delay, f.Repeat}
		case "clearTimer":
			delete(timers, f.ID)
		case "failure":
			// Inspect the guest message only inside this disposable process. The
			// parent receives one fixed category and never the script's error text.
			var failure struct {
				Message string `json:"message"`
			}
			if json.Unmarshal([]byte(value), &failure) != nil {
				return nil, ErrInvalid
			}
			category := "execution"
			if len(failure.Message) <= 4096 && qualityUnavailable.MatchString(failure.Message) {
				category = "quality-unavailable"
			}
			if err := writer.send(frame{Kind: "failure", Category: category}); err != nil {
				return nil, ErrExecution
			}
		case "inited", "result", "http", "cancelHttp":
			if err := writer.send(f); err != nil {
				return nil, ErrExecution
			}
		default:
			return nil, ErrInvalid
		}
		return nil, nil
	}); err != nil {
		return ErrExecution
	}
	info := map[string]string{}
	for _, key := range []string{"name", "version", "author", "description"} {
		pattern := regexp.MustCompile("(?m)^\\s*\\*?\\s*@" + key + "\\s+([^\\r\\n]{1,200})")
		if match := pattern.FindStringSubmatch(load.Script); len(match) > 1 {
			info[key] = strings.TrimSpace(strings.ReplaceAll(match[1], "*/", ""))
		}
	}
	infoJSON, _ := json.Marshal(info)
	initial, _ := json.Marshal(load.Environment)
	metadata, _ := json.Marshal(string(infoJSON))
	setup := "globalThis.__lx_environment=" + string(initial) + ";globalThis.__lx_scriptInfo=" + string(metadata) + ";"
	if _, err = vm.Eval(setup, quickjs.EvalGlobal); err != nil {
		return ErrExecution
	}
	dispatch, err := vm.EvalValue(preload, quickjs.EvalGlobal)
	if err != nil {
		return ErrExecution
	}
	defer dispatch.Free()
	if _, err = vm.Eval("delete globalThis.__lx_emit;delete globalThis.__lx_tool;delete globalThis.__lx_environment;delete globalThis.__lx_scriptInfo;", quickjs.EvalGlobal); err != nil {
		return ErrExecution
	}
	value, err := vm.EvalValue(load.Script, quickjs.EvalGlobal)
	if err != nil {
		return ErrExecution
	}
	value.Free()
	if _, err = vm.ExecutePendingJobs(); err != nil {
		return ErrExecution
	}
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	deliver := func(f frame) error {
		if f.Kind != "resolve" && f.Kind != "httpReply" && f.Kind != "timer" {
			return ErrInvalid
		}
		payload, e := json.Marshal(f)
		if e != nil {
			return ErrInvalid
		}
		// Only JSON text enters the realm; native Go objects are never exposed.
		event, e := vm.EvalValue("("+string(payload)+")", quickjs.EvalGlobal)
		if e != nil {
			return ErrExecution
		}
		defer event.Free()
		result, e := dispatch.CallValue(quickjs.UndefinedValue, event)
		if e != nil {
			return ErrExecution
		}
		result.Free()
		if _, e = vm.ExecutePendingJobs(); e != nil {
			return ErrExecution
		}
		return nil
	}
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case f, ok := <-inbox:
			if !ok {
				return nil
			}
			if f.Kind == "stop" {
				return nil
			}
			if f.Kind == "resolve" {
				_ = vm.SetEvalTimeout(20 * time.Second)
			}
			if err = deliver(f); err != nil {
				return err
			}
		case now := <-ticker.C:
			// Callbacks may add or delete timers; iterate over a frozen ID list.
			ids := make([]int, 0, len(timers))
			for id := range timers {
				ids = append(ids, id)
			}
			for _, id := range ids {
				t, ok := timers[id]
				if !ok || now.Before(t.at) {
					continue
				}
				if t.repeat {
					t.at = now.Add(t.delay)
					timers[id] = t
				} else {
					delete(timers, id)
				}
				if err = deliver(frame{Kind: "timer", ID: id}); err != nil {
					return err
				}
			}
		}
	}
}

var qualityUnavailable = regexp.MustCompile(`(?i)(音质|quality|not.?found|不支持|不存在|无损|未找到|没有找到)`)
