// Adapted from ktv-home (MIT); see docs/third-party/ktv-home-LICENSE.txt.
package com.mynora.singverse.media.metadata;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class NeteaseCrypto {
    private static final byte[] EAPI_KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PRESET_KEY = "0CoJUm6Qyw8W8jud".getBytes(StandardCharsets.UTF_8);
    private static final byte[] IV = "0102030405060708".getBytes(StandardCharsets.UTF_8);
    private static final String BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";

    private NeteaseCrypto() {}

    static String eapi(String path, String json) {
        try {
            String digest = hex(MessageDigest.getInstance("MD5").digest(("nobody" + path + "use" + json + "md5forencrypt").getBytes(StandardCharsets.UTF_8)), false);
            String data = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(EAPI_KEY, "AES"));
            return hex(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)), true);
        } catch (Exception ex) {
            throw new IllegalStateException("网易云 EAPI 加密失败", ex);
        }
    }

    static WeapiPayload weapi(String json) { return weapi(json, randomSecret()); }

    static WeapiPayload weapi(String json, String secret) {
        try {
            String once = aesCbc(json, PRESET_KEY);
            String params = aesCbc(once, secret.getBytes(StandardCharsets.UTF_8));
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
            rsa.init(Cipher.ENCRYPT_MODE, key);
            byte[] reversed = new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.UTF_8);
            byte[] padded = new byte[128];
            System.arraycopy(reversed, 0, padded, padded.length - reversed.length, reversed.length);
            return new WeapiPayload(params, hex(rsa.doFinal(padded), false));
        } catch (Exception ex) {
            throw new IllegalStateException("网易云 WEAPI 加密失败", ex);
        }
    }

    private static String aesCbc(String text, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(IV));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String randomSecret() {
        SecureRandom random = new SecureRandom();
        StringBuilder out = new StringBuilder(16);
        for (int i = 0; i < 16; i++) out.append(BASE62.charAt(random.nextInt(BASE62.length())));
        return out.toString();
    }

    private static String hex(byte[] bytes, boolean uppercase) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(uppercase ? "%02X" : "%02x", value & 0xff));
        return out.toString();
    }

    record WeapiPayload(String params, String encSecKey) {}
}
