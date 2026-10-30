package com.mynora.singverse.compute;

import static com.mynora.singverse.compute.ComputeNodeDtos.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ComputeNodeSettings {
    public static final String PURPOSE = "singverse:compute-node:v1";
    private final ComputeNodeMapper mapper;
    private final ObjectMapper json;
    private final AuthCrypto crypto;
    private final String automaticEndpoint, automaticTokenFile;
    public ComputeNodeSettings(ComputeNodeMapper mapper, ObjectMapper json, AuthCrypto crypto,
            @Value("${singverse.compute.base-url:}") String endpoint, @Value("${singverse.compute.token-file:}") String tokenFile) {
        this.mapper=mapper; this.json=json; this.crypto=crypto;
        this.automaticEndpoint=endpoint.isBlank()?"":origin(endpoint).toString(); this.automaticTokenFile=tokenFile;
    }
    public Settings view() {
        var row=mapper.settings();
        return new Settings(text(row,"mode"),text(row,"endpoint"),row.get("secret_encrypted")!=null,mappings(row),
                automaticEndpoint,!automaticToken().isBlank(),number(row));
    }
    public Connection current() { return connection(mapper.settings(),null); }
    public Connection draft(Save input) {
        var row=mapper.settings();
        validate(input);
        if (number(row)!=input.revision()) throw failure(409,"配置已被修改，请刷新后重试");
        return connection(row,input);
    }
    @Transactional
    public Settings save(Save input) {
        validate(input);
        var row=mapper.settings();
        if (number(row)!=input.revision()) throw failure(409,"配置已被修改，请刷新后重试");
        String endpoint=text(row,"endpoint"), secret=(String)row.get("secret_encrypted");
        List<PathMapping> maps=mappings(row);
        if (input.mode().equals("MANUAL")) {
            endpoint=origin(input.endpoint()).toString(); maps=validatedMappings(input.mappings());
            secret=credential(row,input,true);
        }
        if (mapper.save(input.mode(),endpoint,secret,encode(maps),input.revision())!=1) throw failure(409,"配置已被修改，请刷新后重试");
        return view();
    }
    private Connection connection(Map<String,Object> row, Save input) {
        String mode=input==null?text(row,"mode"):input.mode();
        if (mode.equals("DISABLED")) return null;
        if (mode.equals("AUTO")) return automaticEndpoint.isBlank()?null:new Connection(origin(automaticEndpoint),automaticToken(),List.of());
        String endpoint=input==null?text(row,"endpoint"):origin(input.endpoint()).toString();
        String secret=input==null?(String)row.get("secret_encrypted"):credential(row,input,true);
        return new Connection(origin(endpoint),secret==null?"":crypto.decrypt(secret,PURPOSE),input==null?mappings(row):validatedMappings(input.mappings()));
    }
    private String credential(Map<String,Object> row, Save input, boolean require) {
        String endpoint=origin(input.endpoint()).toString(), value=input.token()==null?"":input.token().strip();
        if (input.clearToken() && !value.isEmpty()) throw failure(400,"重新填写和清除凭据不能同时选择");
        if (!value.isEmpty()) {
            if (!validToken(value)) throw failure(400,"认证凭据应为32～256个可见ASCII字符");
            return crypto.encrypt(value,PURPOSE);
        }
        String prior=endpoint.equals(text(row,"endpoint"))?(String)row.get("secret_encrypted"):null;
        if (input.clearToken()) return null;
        if (require && prior==null) throw failure(400,"请填写认证凭据；修改节点地址后需要重新填写");
        return prior;
    }
    private String automaticToken() {
        if (automaticTokenFile.isBlank()) return "";
        try (var reader=Files.newBufferedReader(Path.of(automaticTokenFile))) {
            char[] buffer=new char[257]; int length=reader.read(buffer);
            String value=length<0?"":new String(buffer,0,length).strip();
            return validToken(value)?value:"";
        } catch (Exception error) { return ""; }
    }
    private void validate(Save value) {
        if (value==null || !Set.of("AUTO","MANUAL","DISABLED").contains(Objects.toString(value.mode(),""))) throw failure(400,"请选择节点连接方式");
        if (value.mode().equals("MANUAL")) { origin(value.endpoint()); validatedMappings(value.mappings()); }
    }
    public static URI origin(String value) {
        try {
            if (value==null || value.length()>512) throw new IllegalArgumentException();
            var uri=URI.create(value.strip());
            if (!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                    || uri.getQuery()!=null || uri.getFragment()!=null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort()==0 || uri.getPort()>65535) throw new IllegalArgumentException();
            return URI.create(uri.getScheme()+"://"+uri.getRawAuthority());
        } catch (Exception error) { throw failure(400,"节点地址应为HTTP(S)服务根地址，不包含账号、路径或查询参数"); }
    }
    public static void checkNetwork(URI uri) throws java.net.UnknownHostException {
        for (var address:java.net.InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) throw failure(400,"不支持该节点网络地址");
            byte[] bytes=address.getAddress(); boolean privateV6=bytes.length==16 && (bytes[0]&0xfe)==0xfc;
            if (uri.getScheme().equals("http") && !address.isLoopbackAddress() && !address.isSiteLocalAddress() && !privateV6)
                throw failure(400,"公网节点必须使用HTTPS，HTTP仅用于本机或受限内网");
        }
    }
    private static List<PathMapping> validatedMappings(List<PathMapping> values) {
        if (values==null) return List.of();
        if (values.size()>16) throw failure(400,"最多配置16条媒体路径映射");
        var roots=new HashSet<String>(); var result=new ArrayList<PathMapping>();
        for (var value:values) {
            if (value==null || value.localRoot()==null || value.remoteRoot()==null || value.localRoot().length()>2048 || value.remoteRoot().length()>2048
                    || !Path.of(value.localRoot()).isAbsolute() || !(value.remoteRoot().startsWith("/") || value.remoteRoot().matches("^[A-Za-z]:[\\\\/].*")) || value.localRoot().chars().anyMatch(Character::isISOControl) || value.remoteRoot().chars().anyMatch(Character::isISOControl))
                throw failure(400,"路径映射必须使用有效的绝对路径");
            String local=Path.of(value.localRoot()).normalize().toString(), remote=value.remoteRoot().replace('\\','/');
            if(java.util.Arrays.stream(remote.split("/",-1)).anyMatch(part->part.equals(".")||part.equals("..")))throw failure(400,"远程路径映射不能包含相对跳转");
            if(!remote.matches("^[A-Za-z]:/.*"))remote=Path.of(remote).normalize().toString();
            if (!roots.add(local)) throw failure(400,"本机媒体根目录不能重复");
            result.add(new PathMapping(local,remote));
        }
        result.sort(Comparator.comparingInt((PathMapping m)->m.localRoot().length()).reversed());
        return List.copyOf(result);
    }
    private List<PathMapping> mappings(Map<String,Object> row) {
        try { return json.readValue(text(row,"mappings_json"),new TypeReference<List<PathMapping>>() {}); }
        catch (Exception error) { throw new IllegalStateException("Invalid stored compute mappings",error); }
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static boolean validToken(String token) {return token.length()>=32 && token.length()<=256 && token.chars().allMatch(c->c>=33&&c<=126);}
    private static String text(Map<String,Object> row,String key) {return Objects.toString(row.get(key),"");}
    private static long number(Map<String,Object> row) {return ((Number)row.get("revision")).longValue();}
    private static ResponseStatusException failure(int code,String text) {return new ResponseStatusException(HttpStatus.valueOf(code),text);}
}
