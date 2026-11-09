(function () {
  'use strict';
  const emit = __lx_emit, tool = __lx_tool;
  const handlers = new Map(), requests = new Map(), timers = new Map();
  let sequence = 0, timerSequence = 0;
  const array = v => v instanceof ArrayBuffer ? Array.from(new Uint8Array(v))
    : ArrayBuffer.isView(v) ? Array.from(new Uint8Array(v.buffer, v.byteOffset, v.byteLength))
    : Array.isArray(v) ? Array.from(v) : v;
  const call = (op, data) => JSON.parse(tool(op, JSON.stringify(data)));
  const from = (value, encoding = 'utf8') => new Uint8Array(decode64(call('from', {value: array(value), encoding})));
  function decode64(text) {
    const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    let bits = 0, count = 0; const output = [];
    for (const ch of text) { const n = alphabet.indexOf(ch); if (n < 0) continue; bits = bits * 64 + n; count += 6; if (count >= 8) { count -= 8; output.push((bits >>> count) & 255); bits &= (1 << count) - 1; } }
    return output;
  }
  const buffer = {from, bufToString: (value, encoding = 'utf8') => call('string', {value: array(value), encoding}),
    toBase64: value => call('string', {value: array(value), encoding: 'base64'}), fromBase64: value => call('string', {value: array(from(value, 'base64')), encoding: 'utf8'})};
  const crypto = {
    md5: value => call('md5', {value: array(value)}), sha256: value => call('sha256', {value: array(value)}),
    randomBytes: size => {if(!Number.isInteger(size) || size < 0 || size > 1048576)throw new Error('randomBytes 参数无效');return from(call('random', {size}), 'base64');},
    aesEncrypt: (value, mode, key, iv) => from(call('aes', {value: array(value), mode, key: array(key), iv: array(iv)}), 'base64'),
    rsaEncrypt: (value, key) => from(call('rsa', {value: array(value), key}), 'base64'),
  };
  globalThis.Buffer = {from, isBuffer: value => value instanceof Uint8Array, concat: values => from(values.flatMap(array)), alloc: size => new Uint8Array(size)};
  globalThis.buffer = buffer;
  globalThis.atob = value => buffer.bufToString(from(value, 'base64'), 'latin1');
  globalThis.btoa = value => buffer.toBase64(from(value, 'latin1'));
  globalThis.console = Object.freeze({log(){},info(){},warn(){},error(){},debug(){}});
  function timer(fn, delay, repeat, args) {
    if (typeof fn !== 'function') throw new Error('定时器需要函数');
    if (timers.size >= 64) throw new Error('定时器数量超限');
    const id = ++timerSequence; timers.set(id, {fn, repeat, args});
    emit(JSON.stringify({kind: 'timer', id, delay: Math.max(1, Math.min(30000, Number(delay) || 1)), repeat})); return id;
  }
  globalThis.setTimeout = (fn, delay, ...args) => timer(fn, delay, false, args);
  globalThis.setInterval = (fn, delay, ...args) => timer(fn, delay, true, args);
  globalThis.clearTimeout = globalThis.clearInterval = id => {timers.delete(id); emit(JSON.stringify({kind: 'clearTimer', id}));};
  globalThis.lx = Object.freeze({version: '2.0.0', env: __lx_environment,
    currentScriptInfo: JSON.parse(__lx_scriptInfo),
    EVENT_NAMES: Object.freeze({inited:'inited',request:'request',updateAlert:'updateAlert'}), utils: {buffer, crypto},
    updateAlert: () => Promise.resolve(),
    on: (event, handler) => {if (typeof handler === 'function') handlers.set(event, handler);return Promise.resolve();},
    send: (event, data) => {if (event === 'inited') emit(JSON.stringify({kind:'inited',data}));return Promise.resolve();},
    request: (url, options, callback) => {
      if (typeof options === 'function') { callback = options; options = {}; } options ||= {};
      if (requests.size >= 32) throw new Error('请求数量超限');
      const id = ++sequence; requests.set(id, {callback, binary: options.encoding === null});
      const headers = {...options.headers}; let body = options.body ?? options.data;
      if (options.form) {body = Object.entries(options.form).map(([k,v]) => encodeURIComponent(k)+'='+encodeURIComponent(v)).join('&'); headers['Content-Type'] ||= 'application/x-www-form-urlencoded';}
      if (options.formData) throw new Error('暂不支持 multipart 音源请求');
      if (options.json && body != null && typeof body !== 'string') {body = JSON.stringify(body); headers['Content-Type'] ||= 'application/json';}
      const binary = body instanceof ArrayBuffer || ArrayBuffer.isView(body) || Array.isArray(body);
      emit(JSON.stringify({kind:'http',id,url:String(url),method:String(options.method || 'GET').toUpperCase(),headers,body:binary?buffer.toBase64(body):body == null?null:String(body),binary}));
      return () => {requests.delete(id); emit(JSON.stringify({kind:'cancelHttp',id}));};
    },
  });
  globalThis.cryptoBridge = {md5:crypto.md5,sha256:crypto.sha256,
    aesEncrypt: (value, key) => {const d=typeof key==='object'?key:{key};return call('aes',{...d,value,key:d.key || key,mode:d.mode || (d.iv?'aes-128-cbc':'aes-128-ecb'),legacy:true});},
    aesDecrypt: (value, key) => {const d=typeof key==='object'?key:{key};return buffer.bufToString(from(call('aes',{...d,value,key:d.key || key,mode:d.mode || (d.iv?'aes-128-cbc':'aes-128-ecb'),legacy:true,decrypt:true}),'base64'));},
    rsaEncrypt: (value, key) => call('rsa',{value,key,legacy:true}),randomBytes: size => buffer.toBase64(crypto.randomBytes(size))};
  return function dispatch(event) {
    if (event.kind === 'timer') {const t=timers.get(event.id);if(t){if(!t.repeat)timers.delete(event.id);t.fn(...t.args);}return;}
    if (event.kind === 'httpReply') {
      const request=requests.get(event.id); requests.delete(event.id); if(!request)return;
      if(event.error){request.callback(new Error(event.error),null,null);return;}
      let body = request.binary ? from(event.body,'base64') : buffer.bufToString(from(event.body,'base64'));
      if(!request.binary){try{body=JSON.parse(body);}catch{}}
      request.callback(null,{status:event.status,statusCode:event.status,ok:event.status>=200&&event.status<300,headers:event.headers,url:event.url,finalUrl:event.url,body},body);return;
    }
    if (event.kind === 'resolve') {
      const handler=handlers.get('request');
      if(!handler){emit(JSON.stringify({kind:'failure',message:'音源没有注册歌曲解析接口'}));return;}
      Promise.resolve().then(()=>handler({source:event.platform,action:'musicUrl',info:{type:event.quality,musicInfo:event.musicInfo}}))
        .then(value=>emit(JSON.stringify({kind:'result',url:typeof value==='string'?value:value?.url})))
        .catch(error=>emit(JSON.stringify({kind:'failure',message:String(error?.message || error)})));
    }
  };
})()
