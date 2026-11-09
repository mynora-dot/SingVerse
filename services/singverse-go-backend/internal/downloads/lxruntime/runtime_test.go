package lxruntime

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	for _, argument := range os.Args[1:] {
		if argument == "--lx-worker" {
			if Worker(context.Background(), os.Stdin, os.Stdout) != nil {
				os.Exit(1)
			}
			os.Exit(0)
		}
	}
	os.Exit(m.Run())
}

const initScript = "lx.send('inited',{sources:{wy:{actions:['musicUrl'],qualitys:['128k','flac']}}});"

func TestWorkerCryptoBuffersTimersAndAsyncHTTP(t *testing.T) {
	r := New("")
	defer r.Close()
	r.request = func(ctx context.Context, f frame) (frame, error) {
		if f.URL != "https://example.com/resolve" || f.Method != "POST" || f.Body == nil || !strings.Contains(*f.Body, "provider-id") {
			return frame{}, ErrNetwork
		}
		encoded := base64.StdEncoding.EncodeToString([]byte(`{"url":"https://example.com/audio.flac"}`))
		return frame{Kind: "httpReply", ID: f.ID, Status: 200, Headers: map[string]string{"content-type": "application/json"}, Body: &encoded, URL: f.URL}, nil
	}
	script := `
	if (typeof process !== 'undefined' || typeof load === 'function' || typeof require !== 'undefined') throw Error('host access');
	if (typeof __lx_emit !== 'undefined' || typeof __lx_tool !== 'undefined') throw Error('private protocol');
	if (lx.env !== 'mobile' || lx.version !== '2.0.0') throw Error('environment');
	const {buffer,crypto}=lx.utils;
	if(crypto.md5('abc')!=='900150983cd24fb0d6963f7d28e17f72')throw Error('digest');
	if(crypto.sha256('abc')!=='ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad')throw Error('sha256');
	const bytes=buffer.from('中文');if(buffer.bufToString(bytes)!=='中文')throw Error('buffer');
	const padded=new Uint8Array(bytes.length+2);padded.set(bytes,1);
	if(buffer.bufToString(new DataView(padded.buffer,1,bytes.length))!=='中文')throw Error('view');
	if(crypto.randomBytes(8).length!==8)throw Error('random');
	const cipher=crypto.aesEncrypt('hello','aes-128-cbc',buffer.from('0123456789abcdef'),buffer.from('0123456789abcdef'));
	if(buffer.bufToString(cipher,'base64')!=='MOfLtxzZ0YgS4+5cPylFYw==')throw Error('cbc');
	const ecb=crypto.aesEncrypt(buffer.from('00112233445566778899aabbccddeeff','hex'),'aes-128-ecb',buffer.from('000102030405060708090a0b0c0d0e0f','hex'),new Uint8Array(0));
	if(buffer.bufToString(ecb,'hex')!=='69c4e0d86a7b0430d8cdb78070b4c55a')throw Error('ecb');
	const legacy=cryptoBridge.aesEncrypt('hello',{key:'0123456789abcdef',iv:'0123456789abcdef'});
	if(cryptoBridge.aesDecrypt(legacy,{key:'0123456789abcdef',iv:'0123456789abcdef'})!=='hello')throw Error('legacy');
	setTimeout(()=>lx.send('inited',{sources:{wy:{actions:['musicUrl'],qualitys:['flac','128k']}}}),5);
	lx.on('request',({source,action,info})=>new Promise((resolve,reject)=>{
	  if(source!=='wy'||action!=='musicUrl'||info.type!=='flac')throw Error('request');
	  lx.request('https://example.com/resolve',{method:'POST',json:true,body:{id:info.musicInfo.meta.songId}},(error,response,body)=>{
	    if(error)reject(error);else if(response.statusCode!==200||!response.ok||response.finalUrl!==response.url)reject(Error('status'));else resolve(body.url);
	  });
	}));
	`
	capabilities, err := r.Inspect(t.Context(), initScript, "desktop")
	if err != nil || strings.Join(capabilities["wy"], ",") != "flac,128k" {
		t.Fatalf("capabilities: %v %v", capabilities, err)
	}
	url, err := r.Resolve(t.Context(), script, "mobile", "wy", "flac", map[string]any{"meta": map[string]any{"songId": "provider-id"}})
	if err != nil || url != "https://example.com/audio.flac" {
		t.Fatalf("resolve: %q %v", url, err)
	}
}

func TestWorkerPrivateHTTPNeverReachesServer(t *testing.T) {
	var requests atomic.Int64
	private := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		w.WriteHeader(200)
	}))
	defer private.Close()
	r := New("")
	defer r.Close()
	script := fmt.Sprintf(`lx.request(%q,{method:'GET'},error=>{if(!error)throw Error('private network allowed');%s});`, private.URL+"/credential", initScript)
	capabilities, err := r.Inspect(t.Context(), script, "mobile")
	if err != nil || len(capabilities["wy"]) != 2 || requests.Load() != 0 {
		t.Fatalf("worker bypassed the parent public-network boundary: calls=%d, error=%v", requests.Load(), err)
	}
}

func TestErrorsAreScrubbedAndPrivateResultsRejected(t *testing.T) {
	r := New("")
	defer r.Close()
	for _, script := range []string{
		initScript + "lx.on('request',()=>{throw Error('unlabelled-private-credential')})",
		initScript + "lx.on('request',()=> 'http://127.0.0.1/secret?token=credential')",
		initScript + "lx.on('request',()=> 'file:///etc/passwd')",
	} {
		_, err := r.Resolve(t.Context(), script, "mobile", "wy", "flac", nil)
		if err == nil || strings.Contains(err.Error(), "credential") || strings.Contains(err.Error(), "secret") {
			t.Fatalf("unsafe error: %v", err)
		}
	}
	if _, err := r.Resolve(t.Context(), initScript, "mobile", "wy", "320k", nil); !errors.Is(err, ErrInvalid) {
		t.Fatalf("quality: %v", err)
	}
	if _, err := r.Inspect(t.Context(), initScript, "unknown"); !errors.Is(err, ErrInvalid) {
		t.Fatalf("environment: %v", err)
	}
}

func TestModuleLoadingIsUnavailable(t *testing.T) {
	r := New("")
	defer r.Close()
	script := "import('file:///etc/passwd').then(()=>{throw Error('file access')},()=>{" + initScript + "});"
	if _, err := r.Inspect(t.Context(), script, "mobile"); err != nil {
		t.Fatalf("module rejection: %v", err)
	}
}

func TestQualityFailureCategoryAndScrubbing(t *testing.T) {
	r := New("")
	defer r.Close()
	for _, message := range []string{"quality missing", "NOT_FOUND credential", "当前音质不存在", "不支持", "未找到", "无损不可用"} {
		encoded, _ := json.Marshal(message)
		script := initScript + "lx.on('request',()=>{throw Error(" + string(encoded) + ")})"
		_, err := r.Resolve(t.Context(), script, "mobile", "wy", "flac", nil)
		if !errors.Is(err, ErrQualityUnavailable) || strings.Contains(err.Error(), "credential") {
			t.Fatalf("quality category: %v", err)
		}
	}
	_, err := r.Resolve(t.Context(), initScript+"lx.on('request',()=>{throw Error('private credential')})", "mobile", "wy", "flac", nil)
	if !errors.Is(err, ErrExecution) {
		t.Fatalf("execution category: %v", err)
	}
}

func TestCallerCancellationKillsRunawayWorkerAndNextCallWorks(t *testing.T) {
	r := New("")
	defer r.Close()
	ctx, cancel := context.WithTimeout(t.Context(), 200*time.Millisecond)
	defer cancel()
	if _, err := r.Inspect(ctx, "while(true){}", "mobile"); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("cancellation: %v", err)
	}
	if _, err := r.Inspect(t.Context(), initScript, "mobile"); err != nil {
		t.Fatalf("next call: %v", err)
	}
}

func TestWorkerMemoryStackAndTimerBounds(t *testing.T) {
	r := New("")
	defer r.Close()
	for _, script := range []string{
		"new Uint8Array(257*1024*1024);" + initScript,
		"function recurse(){return recurse()}recurse();" + initScript,
		"for(let i=0;i<65;i++)setTimeout(()=>{},30000);" + initScript,
	} {
		if _, err := r.Inspect(t.Context(), script, "mobile"); !errors.Is(err, ErrExecution) {
			t.Fatalf("worker exceeded its bound: %v", err)
		}
	}
	if _, err := r.Inspect(t.Context(), initScript, "mobile"); err != nil {
		t.Fatalf("bounded failures affected next operation: %v", err)
	}
}

func TestInitializationDeadline(t *testing.T) {
	if testing.Short() {
		t.Skip("exercises real ten-second worker deadline")
	}
	r := New("")
	defer r.Close()
	start := time.Now()
	if _, err := r.Inspect(t.Context(), "while(true){}", "mobile"); !errors.Is(err, ErrTimeout) {
		t.Fatalf("deadline: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 13*time.Second {
		t.Fatalf("worker exceeded deadline: %v", elapsed)
	}
	if _, err := r.Inspect(t.Context(), initScript, "mobile"); err != nil {
		t.Fatalf("next call: %v", err)
	}
}

func TestThreeWorkerLimitAndClose(t *testing.T) {
	r := New("")
	var wg sync.WaitGroup
	for range 3 {
		wg.Add(1)
		go func() { defer wg.Done(); _, _ = r.Inspect(t.Context(), "while(true){}", "mobile") }()
	}
	deadline := time.Now().Add(time.Second)
	for len(r.slots) != 3 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if len(r.slots) != 3 {
		t.Fatal("workers did not acquire slots")
	}
	if _, err := r.Inspect(t.Context(), initScript, "mobile"); !errors.Is(err, ErrBusy) {
		t.Fatalf("limit: %v", err)
	}
	r.Close()
	wg.Wait()
	if _, err := r.Inspect(t.Context(), initScript, "mobile"); !errors.Is(err, ErrClosed) {
		t.Fatalf("closed: %v", err)
	}
}

func TestCapabilitiesWhitelist(t *testing.T) {
	var source json.RawMessage = []byte(`{"sources":{"wy":{"actions":["musicUrl"],"qualitys":["unknown","128k","flac","flac"]},"evil":{"actions":["musicUrl"],"qualitys":["flac"]}}}`)
	got, err := parseCapabilities(source)
	if err != nil || len(got) != 1 || strings.Join(got["wy"], ",") != "flac,128k" {
		t.Fatalf("%v %v", got, err)
	}
	if _, err := parseCapabilities([]byte(`{"sources":{"wy":{"actions":["download"],"qualitys":["128k"]}}}`)); err == nil {
		t.Fatal("accepted nonofficial action")
	}
}
