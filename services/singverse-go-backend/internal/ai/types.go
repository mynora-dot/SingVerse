// Package ai provides persisted provider settings and bounded protocol adaptation.
package ai

import "fmt"

type Failure struct {
	Status int
	Detail string
}

func (e *Failure) Error() string              { return e.Detail }
func (e *Failure) StatusCode() int            { return e.Status }
func failure(status int, detail string) error { return &Failure{status, detail} }

type Cipher interface {
	Encrypt(string, string) (string, error)
	Decrypt(string, string) (string, error)
}
type Provider struct {
	ID         string `json:"id"`
	Name       string `json:"name"`
	Kind       string `json:"kind"`
	Protocol   string `json:"protocol"`
	Endpoint   string `json:"endpoint"`
	OutputMode string `json:"outputMode"`
}
type ServiceInput struct {
	Name           string  `json:"name"`
	Provider       string  `json:"provider"`
	Protocol       string  `json:"protocol"`
	Endpoint       string  `json:"endpoint"`
	Model          string  `json:"model"`
	OutputMode     string  `json:"outputMode"`
	APIKey         *string `json:"apiKey"`
	ClearKey       bool    `json:"clearKey"`
	Enabled        bool    `json:"enabled"`
	TimeoutSeconds int     `json:"timeoutSeconds"`
	MaxResults     int     `json:"maxResults"`
	Revision       int64   `json:"revision"`
}
type ServiceView struct {
	ID             string `json:"id"`
	Kind           string `json:"kind"`
	Name           string `json:"name"`
	Provider       string `json:"provider"`
	Protocol       string `json:"protocol"`
	Endpoint       string `json:"endpoint"`
	Model          string `json:"model"`
	OutputMode     string `json:"outputMode"`
	HasKey         bool   `json:"hasKey"`
	Enabled        bool   `json:"enabled"`
	TimeoutSeconds int    `json:"timeoutSeconds"`
	MaxResults     int    `json:"maxResults"`
	Revision       int64  `json:"revision"`
}
type Snapshot struct {
	ID, Protocol, Endpoint, Model, OutputMode string
	EncryptedKey                              *string
	CredentialPurpose                         string
	Enabled                                   bool
	TimeoutSeconds, MaxResults                int
}
type Feature struct {
	ID              string  `json:"id"`
	ModelServiceID  *string `json:"modelServiceId"`
	SearchServiceID *string `json:"searchServiceId"`
	Enabled         bool    `json:"enabled"`
	IntervalSeconds int     `json:"intervalSeconds"`
	Revision        int64   `json:"revision"`
	DisplayMode     string  `json:"displayMode"`
	PetID           string  `json:"petId"`
}
type SearchHit struct {
	Title   string `json:"title"`
	URL     string `json:"url"`
	Snippet string `json:"snippet"`
}
type TestInput struct {
	Mode  string `json:"mode"`
	Query string `json:"query"`
}
type TestResult struct {
	Success   bool        `json:"success"`
	Message   string      `json:"message"`
	ElapsedMS int64       `json:"elapsedMs"`
	Results   []SearchHit `json:"results"`
}

var Providers = []Provider{
	{"openai", "OpenAI", "MODEL", "RESPONSES", "https://api.openai.com/v1/responses", "JSON_SCHEMA"},
	{"anthropic", "Anthropic", "MODEL", "ANTHROPIC", "https://api.anthropic.com/v1/messages", "JSON_SCHEMA"},
	{"gemini", "Google Gemini", "MODEL", "GEMINI", "https://generativelanguage.googleapis.com/v1beta", "JSON_SCHEMA"},
	{"deepseek", "DeepSeek", "MODEL", "CHAT_COMPLETIONS", "https://api.deepseek.com/chat/completions", "JSON_OBJECT"},
	{"qwen", "通义千问 / 百炼", "MODEL", "CHAT_COMPLETIONS", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "JSON_OBJECT"},
	{"doubao", "豆包 / 火山方舟", "MODEL", "CHAT_COMPLETIONS", "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "JSON_OBJECT"},
	{"zhipu", "智谱", "MODEL", "CHAT_COMPLETIONS", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "JSON_OBJECT"},
	{"moonshot", "月之暗面", "MODEL", "CHAT_COMPLETIONS", "https://api.moonshot.cn/v1/chat/completions", "JSON_OBJECT"},
	{"siliconflow", "硅基流动", "MODEL", "CHAT_COMPLETIONS", "https://api.siliconflow.cn/v1/chat/completions", "JSON_OBJECT"},
	{"openrouter", "OpenRouter", "MODEL", "CHAT_COMPLETIONS", "https://openrouter.ai/api/v1/chat/completions", "JSON_SCHEMA"},
	{"ollama", "Ollama", "MODEL", "CHAT_COMPLETIONS", "http://127.0.0.1:11434/v1/chat/completions", "JSON_SCHEMA"},
	{"lmstudio", "LM Studio", "MODEL", "CHAT_COMPLETIONS", "http://127.0.0.1:1234/v1/chat/completions", "JSON_SCHEMA"},
	{"custom", "通用兼容模型", "MODEL", "CHAT_COMPLETIONS", "", "JSON_SCHEMA"},
	{"tavily", "Tavily", "SEARCH", "TAVILY", "https://api.tavily.com/search", ""},
	{"exa", "Exa", "SEARCH", "EXA", "https://api.exa.ai/search", ""},
	{"brave", "Brave Search", "SEARCH", "BRAVE", "https://api.search.brave.com/res/v1/web/search", ""},
	{"bocha", "博查", "SEARCH", "BOCHA", "https://api.bocha.cn/v1/web-search", ""},
	{"searxng", "SearXNG", "SEARCH", "SEARXNG", "", ""}, {"custom-search", "通用搜索接口", "SEARCH", "CUSTOM-SEARCH", "", ""},
}

func object(v any) map[string]any { m, _ := v.(map[string]any); return m }
func array(v any) []any           { a, _ := v.([]any); return a }
func text(v any) string           { s, _ := v.(string); return s }
func path(v any, keys ...string) any {
	for _, k := range keys {
		v = object(v)[k]
	}
	return v
}
func format(v any) string { return fmt.Sprint(v) }
