package ai

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// Endpoint permits HTTPS providers and private HTTP services, with no embedded credentials.
// HTTP DNS is checked again at dial time to prevent rebinding to a public destination.
func Endpoint(ctx context.Context, value string) (*url.URL, error) {
	bad := func() (*url.URL, error) {
		return nil, failure(400, "服务地址无效；远程使用 HTTPS，HTTP 仅允许本机或局域网，地址不能包含凭据、查询或片段")
	}
	if len(value) > 2048 || hasControl(value) {
		return bad()
	}
	u, e := url.Parse(value)
	if e != nil || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.ForceQuery || u.Fragment != "" || !contains(u.Scheme, "http", "https") {
		return bad()
	}
	if u.Scheme == "http" {
		ips, e := net.DefaultResolver.LookupIPAddr(ctx, u.Hostname())
		if e != nil || len(ips) == 0 {
			return bad()
		}
		for _, a := range ips {
			if !a.IP.IsLoopback() && !a.IP.IsPrivate() {
				return bad()
			}
		}
	}
	return u, nil
}
func transport() *http.Transport {
	return &http.Transport{Proxy: nil, MaxIdleConns: 32, MaxIdleConnsPerHost: 4, IdleConnTimeout: 90 * time.Second, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 180 * time.Second, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		h, p, e := net.SplitHostPort(address)
		if e != nil {
			return nil, e
		}
		ips, e := net.DefaultResolver.LookupIPAddr(ctx, h)
		if e != nil {
			return nil, e
		}
		if len(ips) == 0 {
			return nil, errors.New("empty DNS")
		}
		private, _ := ctx.Value(privateKey{}).(bool)
		for _, ip := range ips {
			if private && !ip.IP.IsLoopback() && !ip.IP.IsPrivate() {
				return nil, errors.New("HTTP address changed")
			}
		}
		var last error
		for _, ip := range ips {
			conn, e := (&net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}).DialContext(ctx, network, net.JoinHostPort(ip.IP.String(), p))
			if e == nil {
				return conn, nil
			}
			last = e
		}
		return nil, last
	}}
}

type privateKey struct{}

var client = &http.Client{Transport: transport(), CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}

func send(ctx context.Context, endpoint string, query url.Values, headers map[string]string, body any, timeout int) (map[string]any, error) {
	u, e := Endpoint(ctx, endpoint)
	if e != nil {
		return nil, e
	}
	if query != nil {
		u.RawQuery = query.Encode()
	}
	if timeout < 1 || timeout > 180 {
		return nil, failure(400, "服务超时配置无效")
	}
	ctx, cancel := context.WithTimeout(ctx, time.Duration(timeout)*time.Second)
	defer cancel()
	if u.Scheme == "http" {
		ctx = context.WithValue(ctx, privateKey{}, true)
	}
	method := http.MethodGet
	var reader io.Reader
	if body != nil {
		b, e := json.Marshal(body)
		if e != nil {
			return nil, e
		}
		reader = bytes.NewReader(b)
		method = http.MethodPost
	}
	request, e := http.NewRequestWithContext(ctx, method, u.String(), reader)
	if e != nil {
		return nil, e
	}
	request.Header.Set("Accept", "application/json")
	for k, v := range headers {
		request.Header.Set(k, v)
	}
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, e := client.Do(request)
	if e != nil {
		if ctx.Err() == context.DeadlineExceeded {
			return nil, failure(504, "服务请求超时，请稍后重试")
		}
		return nil, failure(502, "服务连接或响应格式无效，请检查网络、协议及响应大小")
	}
	defer response.Body.Close()
	status := response.StatusCode
	if status == 401 || status == 403 {
		return nil, failure(status, "服务鉴权失败（HTTP "+format(status)+"），请检查密钥及权限")
	}
	if status == 429 {
		return nil, failure(429, "服务限流（HTTP 429），至少冷却 60 秒后重试")
	}
	if status < 200 || status >= 300 {
		return nil, failure(status, "服务请求失败（HTTP "+format(status)+"），请检查协议、地址及 JSON Schema / JSON 模式支持")
	}
	b, e := io.ReadAll(io.LimitReader(response.Body, 128001))
	if e != nil || len(b) > 128000 {
		return nil, failure(502, "服务连接或响应格式无效，请检查网络、协议及响应大小")
	}
	var result map[string]any
	dec := json.NewDecoder(bytes.NewReader(b))
	dec.UseNumber()
	if e = dec.Decode(&result); e != nil || result == nil {
		return nil, failure(502, "服务连接或响应格式无效，请检查网络、协议及响应大小")
	}
	var extra any
	if dec.Decode(&extra) != io.EOF {
		return nil, failure(502, "服务响应包含多余内容")
	}
	return result, nil
}
func bearer(headers map[string]string, key string) {
	if key != "" {
		headers["Authorization"] = "Bearer " + key
	}
}
func incomplete() error {
	return failure(502, "模型拒绝回答、返回空内容或输出未完成，未应用任何分类")
}
func (s *Service) Generate(ctx context.Context, c Snapshot, instructions, input string, schema any) (string, error) {
	key, e := s.Key(c)
	if e != nil {
		return "", e
	}
	var body map[string]any
	headers := map[string]string{}
	structured := schema != nil
	strict := c.OutputMode == "JSON_SCHEMA"
	endpoint := c.Endpoint
	if structured && !strict {
		b, e := json.Marshal(schema)
		if e != nil {
			return "", e
		}
		instructions += "\n返回一个 JSON 对象，不要 Markdown；必须满足以下 JSON Schema：" + string(b)
	}
	switch c.Protocol {
	case "CHAT_COMPLETIONS":
		body = map[string]any{"model": c.Model, "messages": []any{map[string]any{"role": "system", "content": instructions}, map[string]any{"role": "user", "content": input}}, "max_tokens": 1800}
		if structured {
			rf := map[string]any{"type": "json_object"}
			if strict {
				rf = map[string]any{"type": "json_schema", "json_schema": map[string]any{"name": "song_classification", "strict": true, "schema": schema}}
			}
			body["response_format"] = rf
		}
		bearer(headers, key)
	case "RESPONSES":
		body = map[string]any{"model": c.Model, "instructions": instructions, "input": input, "max_output_tokens": 1800}
		if structured {
			rf := map[string]any{"type": "json_object"}
			if strict {
				rf = map[string]any{"type": "json_schema", "name": "song_classification", "strict": true, "schema": schema}
			}
			body["text"] = map[string]any{"format": rf}
		}
		bearer(headers, key)
	case "ANTHROPIC":
		body = map[string]any{"model": c.Model, "system": instructions, "messages": []any{map[string]any{"role": "user", "content": input}}, "max_tokens": 1800}
		if structured {
			b, e := json.Marshal(schema)
			if e != nil {
				return "", e
			}
			var normalized any
			if e = json.Unmarshal(b, &normalized); e != nil {
				return "", e
			}
			body["output_config"] = map[string]any{"format": map[string]any{"type": "json_schema", "schema": anthropicSchema(normalized)}}
		}
		headers["anthropic-version"] = "2023-06-01"
		if key != "" {
			headers["x-api-key"] = key
		}
	case "GEMINI":
		endpoint = strings.TrimRight(endpoint, "/") + "/models/" + url.PathEscape(c.Model) + ":generateContent"
		g := map[string]any{"maxOutputTokens": 1800}
		if structured {
			g["responseMimeType"] = "application/json"
			if strict {
				g["responseJsonSchema"] = schema
			}
		}
		body = map[string]any{"systemInstruction": map[string]any{"parts": []any{map[string]any{"text": instructions}}}, "contents": []any{map[string]any{"role": "user", "parts": []any{map[string]any{"text": input}}}}, "generationConfig": g}
		if key != "" {
			headers["x-goog-api-key"] = key
		}
	default:
		return "", failure(400, "不支持的模型协议")
	}
	root, e := send(ctx, endpoint, nil, headers, body, c.TimeoutSeconds)
	if e != nil {
		return "", e
	}
	var out strings.Builder
	switch c.Protocol {
	case "CHAT_COMPLETIONS":
		choices := array(root["choices"])
		if len(choices) == 0 {
			return "", incomplete()
		}
		choice := object(choices[0])
		m := object(choice["message"])
		if text(choice["finish_reason"]) != "stop" || text(m["refusal"]) != "" {
			return "", incomplete()
		}
		out.WriteString(text(m["content"]))
	case "RESPONSES":
		if text(root["status"]) != "completed" {
			return "", incomplete()
		}
		for _, o := range array(root["output"]) {
			for _, p := range array(object(o)["content"]) {
				m := object(p)
				if text(m["type"]) == "refusal" {
					return "", incomplete()
				}
				if text(m["type"]) == "output_text" {
					out.WriteString(text(m["text"]))
				}
			}
		}
	case "ANTHROPIC":
		if text(root["stop_reason"]) != "end_turn" {
			return "", incomplete()
		}
		for _, p := range array(root["content"]) {
			m := object(p)
			if text(m["type"]) == "text" {
				out.WriteString(text(m["text"]))
			}
		}
	case "GEMINI":
		a := array(root["candidates"])
		if len(a) == 0 || text(object(a[0])["finishReason"]) != "STOP" {
			return "", incomplete()
		}
		for _, p := range array(path(a[0], "content", "parts")) {
			m := object(p)
			if m["thought"] != true {
				out.WriteString(text(m["text"]))
			}
		}
	}
	if strings.TrimSpace(out.String()) == "" {
		return "", incomplete()
	}
	return out.String(), nil
}
func anthropicSchema(v any) any {
	if a, ok := v.([]any); ok {
		r := make([]any, len(a))
		for i, p := range a {
			r[i] = anthropicSchema(p)
		}
		return r
	}
	if m, ok := v.(map[string]any); ok {
		r := map[string]any{}
		for k, p := range m {
			if k != "maxItems" {
				r[k] = anthropicSchema(p)
			}
		}
		if max, ok := m["maxItems"]; ok {
			r["description"] = text(m["description"]) + " 最多 " + format(max) + " 项；结果由应用再次校验。"
		}
		return r
	}
	return v
}
func limit(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}
func (s *Service) Search(ctx context.Context, c Snapshot, query string) ([]SearchHit, error) {
	key, e := s.Key(c)
	if e != nil {
		return nil, e
	}
	h := map[string]string{}
	var body any
	var params url.Values
	switch c.Protocol {
	case "TAVILY":
		bearer(h, key)
		body = map[string]any{"query": query, "max_results": c.MaxResults, "include_answer": false, "include_raw_content": false}
	case "EXA":
		if key != "" {
			h["x-api-key"] = key
		}
		body = map[string]any{"query": query, "numResults": c.MaxResults, "contents": map[string]any{"highlights": true}}
	case "BRAVE":
		if key != "" {
			h["X-Subscription-Token"] = key
		}
		params = url.Values{"q": {query}, "count": {format(c.MaxResults)}}
	case "BOCHA":
		bearer(h, key)
		body = map[string]any{"query": query, "count": c.MaxResults, "summary": false}
	case "SEARXNG":
		bearer(h, key)
		params = url.Values{"q": {query}, "format": {"json"}}
	case "CUSTOM-SEARCH":
		bearer(h, key)
		body = map[string]any{"query": query, "maxResults": c.MaxResults}
	default:
		return nil, failure(400, "不支持的搜索协议")
	}
	root, e := send(ctx, c.Endpoint, params, h, body, c.TimeoutSeconds)
	if e != nil {
		return nil, e
	}
	var raw any = root["results"]
	if c.Protocol == "BRAVE" {
		raw = path(root, "web", "results")
	}
	if c.Protocol == "BOCHA" {
		raw = path(root, "data", "webPages", "value")
	}
	items, ok := raw.([]any)
	if !ok {
		return nil, failure(502, "搜索响应结构无效，请检查服务协议；SearXNG 需启用 JSON 输出")
	}
	out := []SearchHit{}
	for _, item := range items {
		m := object(item)
		u, e := url.Parse(text(m["url"]))
		if e != nil || u.Hostname() == "" || u.User != nil || !contains(u.Scheme, "http", "https") {
			continue
		}
		title := text(m["title"])
		if title == "" {
			title = text(m["name"])
		}
		if title == "" {
			title = "无标题"
		}
		snippet := text(m["snippet"])
		if snippet == "" {
			snippet = text(m["description"])
		}
		if snippet == "" {
			snippet = text(m["content"])
		}
		if a, ok := m["highlights"].([]any); ok && c.Protocol == "EXA" {
			parts := []string{}
			for _, p := range a {
				parts = append(parts, text(p))
			}
			snippet = strings.Join(parts, " ")
		}
		out = append(out, SearchHit{limit(title, 300), limit(u.String(), 2048), limit(snippet, 2000)})
		if len(out) >= c.MaxResults {
			break
		}
	}
	return out, nil
}
