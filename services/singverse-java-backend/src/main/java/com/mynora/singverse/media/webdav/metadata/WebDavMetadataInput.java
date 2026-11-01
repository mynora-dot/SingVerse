package com.mynora.singverse.media.webdav.metadata;

import static com.mynora.singverse.media.webdav.metadata.WebDavAccess.*;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import com.mynora.singverse.media.repository.MediaSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

/** Credentials and redirect URLs stay here. Budgets are enforced before exposing bytes to compute. */
@Service
public class WebDavMetadataInput {
    private final WebDavMetadataMapper mapper;
    private final WebDavAccess access;
    private final MediaSourceRepository sources;
    private final AuthCrypto crypto;
    private final ObjectMapper json;
    private final ScheduledExecutorService alarms=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"webdav-metadata-deadline");t.setDaemon(true);return t;});
    private final Map<String,Grant> grants=new ConcurrentHashMap<>();
    private static final Pattern RANGE=Pattern.compile("bytes=(\\d+)-(\\d+)");
    private static final Pattern CONTENT_RANGE=Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
    private static final class Grant {
        final Map<String,Object> task;
        final String token;
        final long limit,deadline;
        final int maxRequests;
        long bytes;int requests;String etag,etagResource;Long size;String modified;
        Grant(Map<String,Object> task,String token,boolean deep){this.task=Map.copyOf(withoutNulls(task));this.token=token;limit=(deep?128L:16L)*1024*1024;maxRequests=deep?64:16;deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(deep?180:60);size=task.get("file_size") instanceof Number n?n.longValue():null;}
        private static Map<String,Object> withoutNulls(Map<String,Object> input){var copy=new HashMap<>(input);copy.values().removeIf(Objects::isNull);return copy;}
    }
    public WebDavMetadataInput(WebDavMetadataMapper mapper,WebDavAccess access,MediaSourceRepository sources,AuthCrypto crypto,ObjectMapper json){this.mapper=mapper;this.access=access;this.sources=sources;this.crypto=crypto;this.json=json;}
    public void authorize(Map<String,Object> task,String token,boolean deep){grants.put(text(task,"id"),new Grant(task,token,deep));}
    public void revoke(String id){grants.remove(id);}
    public long bytes(String id){var g=grants.get(id);return g==null?0:g.bytes;}
    public int requests(String id){var g=grants.get(id);return g==null?0:g.requests;}
    public void read(String id,String authorization,String range,HttpServletResponse response)throws IOException {
        Grant g=grants.get(id);
        if(g==null||authorization==null||!java.security.MessageDigest.isEqual(authorization.getBytes(StandardCharsets.UTF_8),("Bearer "+g.token).getBytes(StandardCharsets.UTF_8)))throw failure(401,"媒体读取授权无效");
        if(range==null||range.length()>100)throw failure(400,"必须指定受限字节范围");
        var match=RANGE.matcher(range);if(!match.matches())throw failure(400,"仅支持有界单段字节范围");
        long start,end;try{start=Long.parseLong(match.group(1));end=Long.parseLong(match.group(2));if(end<start||end-start>=1024*1024)throw new IllegalArgumentException();}catch(Exception error){throw failure(400,"字节范围超限");}
        final long first=start,last=end;
        synchronized(g){
            validate(g);
            String sourceId=text(g.task,"source_id");
            access.request(sourceId,()->{
                try{transfer(g,first,last,response);return null;}
                catch(IOException error){throw failure(502,"远端媒体读取失败");}
            });
        }
    }
    private void validate(Grant g){
        if(grants.get(text(g.task,"id"))!=g)throw failure(410,"媒体读取授权已结束");
        if(System.nanoTime()>=g.deadline)throw failure(413,"媒体提取时间预算已用尽");
        var rows=mapper.resource(args("songId",g.task.get("song_id")));
        if(rows.isEmpty())throw failure(410,"媒体读取授权已结束");var current=rows.getFirst();
        if(number(current,"generation")!=number(g.task,"generation")||!text(current,"binding").equals(text(g.task,"binding"))||current.get("archived_at")!=null||(number(current,"enabled")!=1&&number(g.task,"manual")!=1)||number(current,"paused")==1||!text(current,"access_group").equals(text(g.task,"access_group"))||!text(current,"private_origins").equals(text(g.task,"private_origins"))||!binding(sources.get(text(g.task,"source_id"))).equals(text(g.task,"binding")))throw failure(410,"媒体读取授权已结束");
    }
    private void transfer(Grant g,long start,long end,HttpServletResponse output)throws IOException {
        String sourceId=text(g.task,"source_id"),group=text(g.task,"access_group");
        var source=sources.get(sourceId);
        String relative=text(g.task,"relative_path");
        if(relative.startsWith("/")||relative.contains("\\")||Arrays.stream(relative.split("/",-1)).anyMatch(p->p.isEmpty()||p.equals(".")||p.equals("..")))throw failure(400,"媒体路径无效");
        URI root=URI.create(source.location()),target;
        try{target=new URI(root.getScheme(),null,root.getHost(),root.getPort(),root.getPath()+relative,null,null);}catch(URISyntaxException error){throw failure(400,"媒体路径无效");}
        var stored=sources.find(sourceId);String encrypted=(String)stored.get("password_encrypted");
        String auth=source.username().isEmpty()?null:"Basic "+Base64.getEncoder().encodeToString((source.username()+":"+(encrypted==null?"":crypto.decrypt(encrypted,"singverse:webdav:v1"))).getBytes(StandardCharsets.UTF_8));
        List<String> privateOrigins=new ArrayList<>();
        try{for(var node:json.readTree(text(access.config(sourceId),"private_origins")))privateOrigins.add(node.asText());}catch(Exception ignored){}
        for(int redirects=0;;redirects++){
            if(redirects>0)access.redirect(sourceId);
            validate(g);
            if(g.requests>=g.maxRequests||g.bytes>=g.limit)throw failure(413,"单文件提取读取预算已用尽");
            long remaining=g.limit-g.bytes;
            if(end-start+1>remaining)throw failure(413,"媒体提取读取预算已用尽");
            g.requests++;
            HttpURLConnection connection=(HttpURLConnection)URI.create(target.toASCIIString()).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);
            connection.setReadTimeout((int)Math.max(1,Math.min(15000,TimeUnit.NANOSECONDS.toMillis(g.deadline-System.nanoTime()))));
            connection.setRequestProperty("Range","bytes="+start+"-"+end);connection.setRequestProperty("Accept-Encoding","identity");
            if(auth!=null)connection.setRequestProperty("Authorization",auth);
            if(g.etag!=null&&resource(target).equals(g.etagResource))connection.setRequestProperty("If-Match",g.etag);
            var alarm=alarms.schedule(connection::disconnect,Math.max(0,g.deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            try{
                int status=connection.getResponseCode();
                if(Set.of(301,302,303,307,308).contains(status)){
                    if(redirects>=3)throw failure(502,"WebDAV 下载跳转过多");
                    String location=connection.getHeaderField("Location");if(location==null)throw failure(502,"WebDAV 下载跳转无效");
                    URI next;try{next=target.resolve(location);}catch(Exception error){throw failure(502,"WebDAV 下载跳转无效");}
                    validateRedirect(target,next,root,privateOrigins);
                    // Credentials are never restored after a cross-origin hop, even when returning to the root.
                    if(!origin(target).equals(origin(next)))auth=null;
                    target=next;continue;
                }
                if(status==401||status==403){access.block(sourceId,true,Instant.now());throw failure(403,"WebDAV 认证或权限异常，已暂停提取");}
                if(status==429){access.block(sourceId,false,retryAfter(connection.getHeaderField("Retry-After")));throw failure(429,"WebDAV 已进入访问冷却");}
                if(Set.of(404,409,410,412,416).contains(status))throw failure(409,"远端媒体在读取期间发生变化");
                if(status==200)throw failure(422,"WebDAV 不支持受限范围读取");
                if(status!=206)throw failure(502,"WebDAV 范围读取失败");
                if(connection.getHeaderField("Content-Encoding")!=null&&!"identity".equalsIgnoreCase(connection.getHeaderField("Content-Encoding")))throw failure(409,"WebDAV 返回了编码后的范围");
                var m=CONTENT_RANGE.matcher(Objects.toString(connection.getHeaderField("Content-Range"),""));
                if(!m.matches())throw failure(409,"WebDAV 返回无效范围");
                long actualStart=Long.parseLong(m.group(1)),actualEnd=Long.parseLong(m.group(2)),size=Long.parseLong(m.group(3));
                if(size<=0||actualStart!=start||actualEnd!=Math.min(end,size-1)||g.size!=null&&g.size!=size)throw failure(409,"远端媒体长度发生变化");
                g.size=size;
                String etag=connection.getHeaderField("ETag"),modified=connection.getHeaderField("Last-Modified");
                if(g.etag!=null&&(!resource(target).equals(g.etagResource)||!Objects.equals(g.etag,etag))||g.modified!=null&&modified!=null&&!g.modified.equals(modified))throw failure(409,"远端媒体版本发生变化");
                if(g.etag==null&&etag!=null&&etag.length()<=512&&etag.matches("\"[!#-~]*\"")){g.etag=etag;g.etagResource=resource(target);}
                if(g.modified==null)g.modified=modified;
                long length=actualEnd-start+1;
                // Buffer only one bounded block, validating before publishing it to compute.
                byte[] data;
                try(var in=connection.getInputStream();var bytes=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[65536];long left=length;
                    try{
                        while(left>0){validate(g);int count=in.read(buffer,0,(int)Math.min(left,buffer.length));if(count<0)throw new EOFException();g.bytes+=count;mapper.bytes(args("group",group,"bytes",count));bytes.write(buffer,0,count);left-=count;}
                    }finally{data=bytes.toByteArray();}
                }
                output.setStatus(206);output.setHeader("Content-Range","bytes "+start+"-"+actualEnd+"/"+size);output.setHeader("Accept-Ranges","bytes");output.setHeader("Cache-Control","no-store");output.setContentType("application/octet-stream");output.setContentLength(data.length);output.getOutputStream().write(data);return;
            }finally{alarm.cancel(false);connection.disconnect();}
        }
    }
    @jakarta.annotation.PreDestroy public void close(){grants.clear();alarms.shutdownNow();}
    public static String checkedOrigin(String value){
        try{URI uri=URI.create(value);if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||uri.getPath()!=null&&!uri.getPath().isEmpty()&&!uri.getPath().equals("/"))throw new IllegalArgumentException();return origin(uri);}
        catch(Exception error){throw failure(400,"私有代理地址须为 HTTP(S) 来源地址，不能包含路径或凭据");}
    }
    private static String resource(URI uri){return origin(uri)+uri.getRawPath();}
    static String origin(URI uri){int port=uri.getPort();return uri.getScheme()+"://"+uri.getHost().toLowerCase(Locale.ROOT)+(port==-1||port==80&&uri.getScheme().equals("http")||port==443&&uri.getScheme().equals("https")?"":":"+port);}
    static void validateRedirect(URI from,URI to,URI root,List<String> allowed){
        if(!Set.of("http","https").contains(Objects.toString(to.getScheme(),""))||to.getHost()==null||to.getUserInfo()!=null||to.getFragment()!=null||"https".equals(from.getScheme())&&!"https".equals(to.getScheme()))throw failure(502,"WebDAV 下载跳转被拒绝");
        if(origin(to).equals(origin(root)))return;
        try{for(var address:InetAddress.getAllByName(to.getHost())){byte[] raw=address.getAddress();boolean local=address.isLoopbackAddress()||address.isSiteLocalAddress()||(raw.length==16&&(raw[0]&0xfe)==0xfc);if(address.isAnyLocalAddress()||address.isLinkLocalAddress()||address.isMulticastAddress()||local&&!allowed.contains(origin(to)))throw failure(502,"WebDAV 私有代理地址未获允许");}}
        catch(UnknownHostException error){throw failure(502,"WebDAV 下载目标不可解析");}
    }
    public static Instant retryAfter(String value){Instant minimum=Instant.now().plusSeconds(21600);try{Instant given=value.matches("[0-9]+")?Instant.now().plusSeconds(Long.parseLong(value)):ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();return given.isAfter(minimum)?given:minimum;}catch(Exception error){return minimum;}}
}
