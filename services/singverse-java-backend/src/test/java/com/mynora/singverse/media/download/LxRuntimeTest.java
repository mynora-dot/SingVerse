package com.mynora.singverse.media.download;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class LxRuntimeTest {
    static final String INIT="lx.send('inited',{sources:{wy:{actions:['musicUrl'],qualitys:['128k','flac']}}});";
    final LxNetwork network=new LxNetwork(){
        @Override public Reply request(String url,String method,Map<String,String> headers,byte[] body,int limit,boolean secure,String owner){
            assertThat(url).isEqualTo("https://example.com/resolve");assertThat(method).isEqualTo("POST");
            assertThat(new String(body,StandardCharsets.UTF_8)).contains("provider-id");
            return new Reply(200,Map.of("content-type","text/plain"),"{\"url\":\"https://example.com/audio.flac\"}".getBytes(StandardCharsets.UTF_8),url);
        }
    };
    @Test void javaWorkerInitializesTimersCryptoAndAsyncHttpWithoutHostAccess()throws Exception {
        try(var runtime=runtime()) {
            String script="""
                if(typeof Java !== 'undefined' && typeof Java.type === 'function') {
                  try {Java.type('java.io.File');throw new Error('host access allowed')} catch(e) {if(e.message === 'host access allowed')throw e;}
                }
                if(typeof process !== 'undefined' || typeof load === 'function')throw new Error('host IO exposed');
                if(typeof __lx_emit !== 'undefined' || typeof __lx_tool !== 'undefined')throw new Error('private protocol exposed');
                if(lx.env !== 'mobile' || lx.version !== '2.0.0')throw new Error('environment mismatch');
                if(lx.utils.crypto.md5('abc') !== '900150983cd24fb0d6963f7d28e17f72')throw new Error('digest mismatch');
                const bytes=lx.utils.buffer.from('中文');
                if(lx.utils.buffer.bufToString(bytes) !== '中文')throw new Error('buffer mismatch');
                if(lx.utils.buffer.bufToString(bytes.buffer) !== '中文')throw new Error('array buffer mismatch');
                const padded=new Uint8Array(bytes.length+2);padded.set(bytes,1);
                if(lx.utils.buffer.bufToString(new DataView(padded.buffer,1,bytes.length)) !== '中文')throw new Error('view offset mismatch');
                if(lx.utils.crypto.randomBytes(8).length !== 8)throw new Error('random mismatch');
                const cipher=lx.utils.crypto.aesEncrypt('hello','aes-128-cbc',lx.utils.buffer.from('0123456789abcdef'),lx.utils.buffer.from('0123456789abcdef'));
                if(lx.utils.buffer.bufToString(cipher,'base64') !== 'MOfLtxzZ0YgS4+5cPylFYw==')throw new Error('cipher mismatch');
                setTimeout(()=>lx.send('inited',{sources:{wy:{actions:['musicUrl'],qualitys:['flac','128k']}}}),5);
                lx.on('request',({source,action,info}) => new Promise((resolve,reject)=>{
                  if(source!=='wy'||action!=='musicUrl'||info.type!=='flac')throw new Error('request mismatch');
                  lx.request('https://example.com/resolve',{method:'POST',json:true,body:{id:info.musicInfo.meta.songId}},(error,response,body)=>{
                    if(error)reject(error);else if(response.statusCode!==200||!response.ok||response.finalUrl!==response.url)reject(new Error('status'));else resolve(body.url);
                  });
                }));
                """;
            assertThat(runtime.inspect(INIT,"desktop")).containsEntry("wy",List.of("flac","128k"));
            var track=new LxTypes.Track("key","wy","provider-id","歌曲",List.of("歌手"),"专辑",1000L,"",new ObjectMapper().readTree("{\"meta\":{\"songId\":\"provider-id\"}}"));
            assertThat(runtime.resolve(script,"mobile",track,"flac")).isEqualTo("https://example.com/audio.flac");
        }
    }
    @Test void untrustedInitializationIsKilledAtDeadlineAndNextOperationWorks() {
        try(var runtime=runtime()) {
            long start=System.nanoTime();assertThatThrownBy(()->runtime.inspect("while(true){}","mobile")).hasMessageContaining("超时");
            assertThat((System.nanoTime()-start)/1e9).isLessThan(15);
            assertThat(runtime.inspect(INIT,"mobile")).containsKey("wy");
        }
    }
    @Test void requiresOfficialActionAndDeclaredQualityAndRejectsPrivateResolvedUrls() throws Exception {
        try(var runtime=runtime()) {
            assertThatThrownBy(()->runtime.inspect("lx.send('inited',{sources:{wy:{actions:['download'],qualitys:['128k']}}})","mobile")).hasMessageContaining("声明");
            var track=new LxTypes.Track("key","wy","1","歌曲",List.of("歌手"),"",null,"",new ObjectMapper().createObjectNode());
            assertThatThrownBy(()->runtime.resolve(INIT+"lx.on('request',()=> 'http://127.0.0.1/secret')","mobile",track,"flac")).hasMessageContaining("公网");
            assertThatThrownBy(()->runtime.resolve(INIT,"mobile",track,"320k")).hasMessageContaining("音质");
        }
    }
    @Test void ecbRsaLegacyBufferAndSourceErrorsStayInsideTheWorker()throws Exception {
        var generator=java.security.KeyPairGenerator.getInstance("RSA");generator.initialize(1024);var key=generator.generateKeyPair();
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(key.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----";
        var cipher=javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding");cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key.getPublic());String expected=HexFormat.of().formatHex(cipher.doFinal("hello".getBytes(StandardCharsets.UTF_8)));
        var json=new ObjectMapper();String script="""
            const {buffer,crypto}=lx.utils;
            if(buffer.fromBase64(buffer.toBase64('中文'))!=='中文')throw new Error('utf8');
            const encrypted=crypto.aesEncrypt(buffer.from('00112233445566778899aabbccddeeff','hex'),'aes-128-ecb',buffer.from('000102030405060708090a0b0c0d0e0f','hex'),new Uint8Array(0));
            if(buffer.bufToString(encrypted,'hex')!=='69c4e0d86a7b0430d8cdb78070b4c55a')throw new Error('ecb');
            const legacy=cryptoBridge.aesEncrypt('hello',{key:'0123456789abcdef',iv:'0123456789abcdef'});
            if(cryptoBridge.aesDecrypt(legacy,{key:'0123456789abcdef',iv:'0123456789abcdef'})!=='hello')throw new Error('legacy');
            """+"if(buffer.bufToString(crypto.rsaEncrypt('hello',"+json.writeValueAsString(pem)+"),'hex')!=="+json.writeValueAsString(expected)+")throw new Error('rsa');"+INIT;
        try(var runtime=runtime()) {
            assertThat(runtime.inspect(script,"mobile")).containsKey("wy");
            var track=new LxTypes.Track("key","wy","1","歌曲",List.of("歌手"),"",null,"",json.createObjectNode());
            assertThatThrownBy(()->runtime.resolve(INIT+"lx.on('request',()=>{throw new Error('unlabelled-private-credential')})","mobile",track,"128k")).hasMessageContaining("音源解析失败").hasMessageNotContaining("private-credential");
        }
    }
    private AutoRuntime runtime(){return new AutoRuntime(network);}
    private static class AutoRuntime extends LxRuntime implements AutoCloseable{AutoRuntime(LxNetwork network){super(network);}}
}
