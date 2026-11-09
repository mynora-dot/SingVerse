// Package lxruntime executes user-supplied LX 2.0 sources in disposable workers.
// Workers have no database or filesystem bridge; all HTTP is brokered here.
package lxruntime

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os"
	"os/exec"
	"sync"
	"time"
)

const (
	maxScript     = 9 * 1024 * 1024
	maxFrame      = 12 * 1024 * 1024
	maxInputFrame = 64 * 1024 * 1024
	maxBody       = 8 * 1024 * 1024
)

var (
	ErrBusy               = errors.New("音源正在处理其他请求，请稍后再试")
	ErrClosed             = errors.New("音源执行器已关闭")
	ErrInvalid            = errors.New("音源参数无效")
	ErrExecution          = errors.New("音源执行失败，请检查脚本兼容性")
	ErrQualityUnavailable = errors.New("当前音质不可用，请更换音质、歌曲版本或音源")
	ErrTimeout            = errors.New("音源响应超时，请重试或更换音源")
	ErrNetwork            = errors.New("音源网络请求失败，请检查音源和网络")
)

type frame struct {
	Kind        string            `json:"kind"`
	ID          int               `json:"id,omitempty"`
	Script      string            `json:"script,omitempty"`
	Environment string            `json:"environment,omitempty"`
	Data        json.RawMessage   `json:"data,omitempty"`
	Platform    string            `json:"platform,omitempty"`
	Quality     string            `json:"quality,omitempty"`
	MusicInfo   map[string]any    `json:"musicInfo,omitempty"`
	URL         string            `json:"url,omitempty"`
	Method      string            `json:"method,omitempty"`
	Headers     map[string]string `json:"headers,omitempty"`
	Body        *string           `json:"body"`
	Binary      bool              `json:"binary,omitempty"`
	Status      int               `json:"status,omitempty"`
	Error       string            `json:"error,omitempty"`
	Category    string            `json:"category,omitempty"`
	Delay       float64           `json:"delay,omitempty"`
	Repeat      bool              `json:"repeat,omitempty"`
}

// Runtime owns a maximum of three disposable worker processes.
type Runtime struct {
	executable string
	slots      chan struct{}
	ctx        context.Context
	cancel     context.CancelFunc
	mu         sync.Mutex
	closed     bool
	wg         sync.WaitGroup
	request    func(context.Context, frame) (frame, error)
}

type operationResult struct {
	capabilities map[string][]string
	url          string
}

// New uses executable to start --lx-worker. An empty path uses this executable.
func New(executable string) *Runtime {
	if executable == "" {
		executable, _ = os.Executable()
	}
	ctx, cancel := context.WithCancel(context.Background())
	n := newNetwork()
	return &Runtime{executable: executable, slots: make(chan struct{}, 3), ctx: ctx, cancel: cancel, request: n.request}
}

func (r *Runtime) Inspect(ctx context.Context, script, environment string) (map[string][]string, error) {
	result, err := r.operate(ctx, script, environment, nil)
	if err != nil {
		return nil, err
	}
	return result.capabilities, nil
}

func (r *Runtime) Resolve(ctx context.Context, script, environment, platform, quality string, musicInfo map[string]any) (string, error) {
	message := &frame{Kind: "resolve", Platform: platform, Quality: quality, MusicInfo: musicInfo}
	result, err := r.operate(ctx, script, environment, message)
	if err != nil {
		return "", err
	}
	return result.url, nil
}

// Close cancels running operations, kills their workers and waits for cleanup.
func (r *Runtime) Close() {
	r.mu.Lock()
	r.closed = true
	r.cancel()
	r.mu.Unlock()
	r.wg.Wait()
}

func (r *Runtime) operate(ctx context.Context, script, environment string, resolution *frame) (*operationResult, error) {
	if len(script) == 0 || len(script) > maxScript || (environment != "mobile" && environment != "desktop") {
		return nil, ErrInvalid
	}
	r.mu.Lock()
	if r.closed {
		r.mu.Unlock()
		return nil, ErrClosed
	}
	select {
	case r.slots <- struct{}{}:
	default:
		r.mu.Unlock()
		return nil, ErrBusy
	}
	r.wg.Add(1)
	r.mu.Unlock()
	defer func() { <-r.slots; r.wg.Done() }()
	opCtx, cancel := context.WithCancel(ctx)
	stop := context.AfterFunc(r.ctx, cancel)
	defer stop()
	defer cancel()
	child := exec.CommandContext(opCtx, r.executable, "--lx-worker")
	child.Env = []string{"LANG=C.UTF-8"}
	child.Dir = os.TempDir()
	child.Stderr = io.Discard
	child.WaitDelay = time.Second
	in, err := child.StdinPipe()
	if err != nil {
		return nil, ErrExecution
	}
	out, err := child.StdoutPipe()
	if err != nil {
		in.Close()
		return nil, ErrExecution
	}
	if err = child.Start(); err != nil {
		in.Close()
		out.Close()
		return nil, ErrExecution
	}
	var tasks sync.WaitGroup
	networkSlots := make(chan struct{}, 8)
	defer func() {
		cancel()
		in.Close()
		out.Close()
		_ = child.Process.Kill()
		_ = child.Wait()
		tasks.Wait()
	}()
	writer := &frameWriter{out: in, limit: maxInputFrame}
	if pipe, ok := in.(*os.File); ok {
		_ = pipe.SetWriteDeadline(time.Now().Add(10 * time.Second))
	}
	if err = writer.send(frame{Kind: "load", Script: script, Environment: environment}); err != nil {
		return nil, ErrExecution
	}
	if pipe, ok := in.(*os.File); ok {
		_ = pipe.SetWriteDeadline(time.Time{})
	}
	messages := make(chan frame, 16)
	go func() {
		defer close(messages)
		scanner := bufio.NewScanner(out)
		scanner.Buffer(make([]byte, 4096), maxFrame+1)
		for scanner.Scan() {
			var f frame
			if err := json.Unmarshal(scanner.Bytes(), &f); err != nil {
				return
			}
			select {
			case messages <- f:
			case <-opCtx.Done():
				return
			}
		}
	}()
	timer := time.NewTimer(10 * time.Second)
	defer timer.Stop()
	var initialized bool
	calls := map[int]context.CancelFunc{}
	defer func() {
		for _, cancelCall := range calls {
			cancelCall()
		}
	}()
	done := make(chan int, 32)
	for {
		select {
		case <-opCtx.Done():
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			return nil, ErrClosed
		case <-timer.C:
			return nil, ErrTimeout
		case id := <-done:
			delete(calls, id)
		case f, ok := <-messages:
			if !ok {
				return nil, ErrExecution
			}
			switch f.Kind {
			case "inited":
				if initialized {
					return nil, ErrExecution
				}
				capabilities, err := parseCapabilities(f.Data)
				if err != nil {
					return nil, err
				}
				if resolution == nil {
					return &operationResult{capabilities: capabilities}, nil
				}
				if !contains(capabilities[resolution.Platform], resolution.Quality) {
					return nil, ErrInvalid
				}
				initialized = true
				if !timer.Stop() {
					select {
					case <-timer.C:
					default:
					}
				}
				timer.Reset(20 * time.Second)
				if err := writer.send(*resolution); err != nil {
					return nil, ErrExecution
				}
			case "result":
				if !initialized || resolution == nil {
					return nil, ErrExecution
				}
				if _, err := checkedURL(f.URL, false); err != nil {
					return nil, ErrNetwork
				}
				return &operationResult{url: f.URL}, nil
			case "failure":
				if initialized && f.Category == "quality-unavailable" {
					return nil, ErrQualityUnavailable
				}
				return nil, ErrExecution
			case "cancelHttp":
				if cancelCall := calls[f.ID]; cancelCall != nil {
					cancelCall()
				}
			case "http":
				if f.ID < 1 || len(calls) >= 32 || calls[f.ID] != nil {
					return nil, ErrExecution
				}
				callCtx, cancelCall := context.WithTimeout(opCtx, 15*time.Second)
				calls[f.ID] = cancelCall
				tasks.Add(1)
				go func(request frame) {
					defer tasks.Done()
					defer cancelCall()
					var reply frame
					var callErr error
					select {
					case networkSlots <- struct{}{}:
						reply, callErr = r.request(callCtx, request)
						<-networkSlots
					case <-callCtx.Done():
						callErr = ErrNetwork
					}
					if callErr != nil {
						reply = frame{Kind: "httpReply", ID: request.ID, Error: ErrNetwork.Error()}
					}
					_ = writer.send(reply)
					select {
					case done <- request.ID:
					case <-opCtx.Done():
					}
				}(f)
			default:
				return nil, ErrExecution
			}
		}
	}
}

type frameWriter struct {
	mu    sync.Mutex
	out   io.Writer
	limit int
}

func (w *frameWriter) send(f frame) error {
	b, err := json.Marshal(f)
	if err != nil || len(b) > w.limit {
		return ErrInvalid
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	n, err := w.out.Write(append(b, '\n'))
	if err == nil && n != len(b)+1 {
		return io.ErrShortWrite
	}
	return err
}

func parseCapabilities(data json.RawMessage) (map[string][]string, error) {
	var info struct {
		Sources map[string]struct {
			Actions   []string `json:"actions"`
			Qualities []string `json:"qualitys"`
		} `json:"sources"`
	}
	if json.Unmarshal(data, &info) != nil {
		return nil, ErrInvalid
	}
	result := map[string][]string{}
	for _, platform := range []string{"wy", "tx", "kg", "kw", "mg"} {
		source := info.Sources[platform]
		if !contains(source.Actions, "musicUrl") {
			continue
		}
		for _, quality := range []string{"flac24bit", "flac", "320k", "128k"} {
			if contains(source.Qualities, quality) {
				result[platform] = append(result[platform], quality)
			}
		}
	}
	if len(result) == 0 {
		return nil, ErrInvalid
	}
	return result, nil
}
func contains(values []string, value string) bool {
	for _, v := range values {
		if v == value {
			return true
		}
	}
	return false
}
