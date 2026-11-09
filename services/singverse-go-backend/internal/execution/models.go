package execution

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync/atomic"
	"time"
)

type boundedModel struct {
	reader    io.Reader
	remaining int64
	exceeded  atomic.Bool
}

func (b *boundedModel) Read(p []byte) (int, error) {
	if int64(len(p)) > b.remaining+1 {
		p = p[:b.remaining+1]
	}
	n, err := b.reader.Read(p)
	b.remaining -= int64(n)
	if b.remaining < 0 {
		b.exceeded.Store(true)
		return 0, fail(413, "模型文件超过该模型清单的大小限制")
	}
	return n, err
}

func (s *Service) uploadModel(ctx context.Context, request *http.Request, model string) (map[string]any, error) {
	maximum, err := modelMaximum(model)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(strings.Split(request.Header.Get("Content-Type"), ";")[0]) != "application/octet-stream" {
		return nil, fail(415, "模型导入需要 application/octet-stream")
	}
	if request.ContentLength > maximum {
		return nil, fail(413, "模型文件超过该模型清单的大小限制")
	}
	connection, err := s.connection(ctx, "")
	if err != nil {
		return nil, err
	}
	body := &boundedModel{reader: request.Body, remaining: maximum}
	response, err := s.remote(ctx, connection, "/internal/v1/audio-roles/models/import?model="+model, "POST", body, request.ContentLength, "application/octet-stream", "", 15*time.Minute)
	if body.exceeded.Load() {
		if response != nil {
			response.Body.Close()
		}
		return nil, fail(413, "模型文件超过该模型清单的大小限制")
	}
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		status := 502
		if oneOfInt(response.StatusCode, 413, 422) {
			status = response.StatusCode
		}
		return nil, fail(status, "模型导入失败，请检查摘要、模型目录及节点状态")
	}
	payload, err := io.ReadAll(io.LimitReader(response.Body, (1<<20)+1))
	if err != nil || len(payload) > 1<<20 {
		return nil, fail(502, "模型导入响应超过限制")
	}
	var value map[string]any
	if json.Unmarshal(payload, &value) != nil || value == nil {
		return nil, fail(502, "模型导入响应无效")
	}
	return scrub(value, connection.Token).(map[string]any), nil
}
