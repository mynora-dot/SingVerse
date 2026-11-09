package com.mynora.singverse.media.download;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** Byte-only LX utilities. No Java object is exposed to guest code. */
final class LxTools {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final int LIMIT=8*1024*1024;
    private LxTools() {}
    static String call(String operation,String input) {
        try {
            if(input.length()>LIMIT)throw new IllegalArgumentException("工具输入过大");
            JsonNode data=JSON.readTree(input);
            Object result=switch(operation) {
                case "from" -> bytes(data.path("value"),data.path("encoding").asText("utf8"));
                case "string" -> encode(bytes(data.path("value"),"utf8"),data.path("encoding").asText("utf8"));
                case "md5","sha256" -> HexFormat.of().formatHex(MessageDigest.getInstance(operation.equals("md5")?"MD5":"SHA-256").digest(bytes(data.path("value"),"utf8")));
                case "random" -> { int size=data.path("size").asInt();if(size<0||size>1048576)throw new IllegalArgumentException();yield AuthRandom.bytes(size); }
                case "aes" -> aes(data);
                case "rsa" -> rsa(data);
                default -> throw new IllegalArgumentException("不支持的工具操作");
            };
            // Jackson serializes byte[] as base64; guest reconstructs Uint8Array explicitly.
            return JSON.writeValueAsString(result);
        } catch(Exception error) { throw new IllegalArgumentException("音源工具参数无效"); }
    }
    private static byte[] bytes(JsonNode value,String encoding) {
        if(value.isObject())return bytes(value.path("data"),value.path("encoding").asText(encoding));
        if(value.isArray()) {
            if(value.size()>LIMIT)throw new IllegalArgumentException();
            byte[] bytes=new byte[value.size()];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)value.get(i).asInt();return bytes;
        }
        String text=value.asText("");
        return switch(encoding.toLowerCase(Locale.ROOT).replace("-","")) {
            case "base64" -> Base64.getDecoder().decode(text);
            case "hex" -> HexFormat.of().parseHex(text);
            case "binary","latin1" -> text.getBytes(StandardCharsets.ISO_8859_1);
            default -> text.getBytes(StandardCharsets.UTF_8);
        };
    }
    private static String encode(byte[] value,String encoding) {
        return switch(encoding.toLowerCase(Locale.ROOT).replace("-","")) {
            case "base64" -> Base64.getEncoder().encodeToString(value);
            case "hex" -> HexFormat.of().formatHex(value);
            case "binary","latin1" -> new String(value,StandardCharsets.ISO_8859_1);
            default -> new String(value,StandardCharsets.UTF_8);
        };
    }
    private static byte[] aes(JsonNode data) throws Exception {
        String mode=data.path("mode").asText("aes-128-cbc").toLowerCase(Locale.ROOT);
        boolean cbc=mode.contains("cbc"),decrypt=data.path("decrypt").asBoolean();
        if(!data.path("legacy").asBoolean()&&!Set.of("aes-128-cbc","aes-128-ecb").contains(mode))throw new IllegalArgumentException();
        String padding=mode.contains("ecb")&&!data.path("legacy").asBoolean()?"NoPadding":"PKCS5Padding";
        byte[] key=bytes(data.path("key"),"utf8"),iv=bytes(data.path("iv"),"utf8");
        if(!Set.of(16,24,32).contains(key.length)||cbc&&iv.length!=16)throw new IllegalArgumentException();
        if(!data.path("legacy").asBoolean()&&key.length!=16)throw new IllegalArgumentException();
        Cipher cipher=Cipher.getInstance("AES/"+(cbc?"CBC/":"ECB/")+padding);
        var secret=new SecretKeySpec(key,"AES");int action=decrypt?Cipher.DECRYPT_MODE:Cipher.ENCRYPT_MODE;
        if(cbc)cipher.init(action,secret,new IvParameterSpec(iv));else cipher.init(action,secret);
        return cipher.doFinal(bytes(data.path("value"),decrypt?"base64":"utf8"));
    }
    private static byte[] rsa(JsonNode data) throws Exception {
        String pem=data.path("key").asText().replaceAll("-----[^-]+-----","").replaceAll("\\s","");
        RSAPublicKey key=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        boolean legacy=data.path("legacy").asBoolean();
        byte[] value=bytes(data.path("value"),"utf8");
        if(!legacy) { int size=(key.getModulus().bitLength()+7)/8;if(value.length>size)throw new IllegalArgumentException();byte[] padded=new byte[size];System.arraycopy(value,0,padded,size-value.length,value.length);value=padded; }
        Cipher cipher=Cipher.getInstance("RSA/ECB/"+(legacy?"PKCS1Padding":"NoPadding"));cipher.init(Cipher.ENCRYPT_MODE,key);return cipher.doFinal(value);
    }
    private static final class AuthRandom { static byte[] bytes(int size) { byte[] result=new byte[size];new SecureRandom().nextBytes(result);return result; } }
}
