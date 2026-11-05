package com.mynora.singverse.compute;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.stereotype.Component;

/** Server-only identity. Private keys stay encrypted with the existing compute credential purpose. */
@Component
public class ComputePairingIdentity {
    static final String PROTOCOL = "singverse-compute-lan-v1";
    static final PSSParameterSpec PSS = new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
    private final ComputePairingIdentityMapper mapper;
    private final AuthCrypto crypto;
    private KeyPair keys;
    public ComputePairingIdentity(ComputePairingIdentityMapper mapper, AuthCrypto crypto) { this.mapper = mapper; this.crypto = crypto; }
    private synchronized KeyPair keys() {
        if (keys != null) return keys;
        try {
            var row = mapper.find();
            if (row == null) {
                var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(3072);
                var generated = generator.generateKeyPair();
                mapper.insert(Base64.getEncoder().encodeToString(generated.getPublic().getEncoded()),
                        crypto.encrypt(Base64.getEncoder().encodeToString(generated.getPrivate().getEncoded()), ComputeNodeSettings.PURPOSE));
                row = mapper.find();
            }
            var factory = KeyFactory.getInstance("RSA");
            var publicKey = factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(row.get("public_key").toString())));
            var privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(crypto.decrypt(row.get("private_key_encrypted").toString(), ComputeNodeSettings.PURPOSE))));
            if (((RSAPublicKey) publicKey).getModulus().bitLength() != 3072
                    || !((RSAPublicKey) publicKey).getModulus().equals(((RSAPrivateKey) privateKey).getModulus())) throw new GeneralSecurityException();
            keys = new KeyPair(publicKey, privateKey);
            return keys;
        } catch (Exception error) { throw new IllegalStateException("Compute pairing identity cannot be initialized; restore the matching encrypted identity and master key", error); }
    }
    public String clientId() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(keys().getPublic().getEncoded())); }
        catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    record Proof(String publicKey, String signature) {}
    Proof proof(String nodeId, String challenge) {
        try {
            var signature = Signature.getInstance("RSASSA-PSS"); signature.setParameter(PSS); signature.initSign(keys().getPrivate());
            signature.update((PROTOCOL + "\n" + nodeId + "\n" + challenge + "\n" + clientId()).getBytes(StandardCharsets.UTF_8));
            return new Proof(Base64.getEncoder().encodeToString(keys().getPublic().getEncoded()), Base64.getEncoder().encodeToString(signature.sign()));
        } catch (GeneralSecurityException error) { throw new IllegalStateException("Compute pairing signature failed", error); }
    }
    String decrypt(String nodeId, String challenge, String encrypted) {
        try {
            byte[] ciphertext = Base64.getDecoder().decode(encrypted);
            if (ciphertext.length != 384) throw new GeneralSecurityException();
            var cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.DECRYPT_MODE, keys().getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
                    new PSource.PSpecified((PROTOCOL + ":" + nodeId + ":" + challenge).getBytes(StandardCharsets.UTF_8))));
            String token = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            if (token.length() < 32 || token.length() > 256 || !token.chars().allMatch(c -> c >= 33 && c <= 126)) throw new GeneralSecurityException();
            return token;
        } catch (GeneralSecurityException | IllegalArgumentException error) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "节点配对凭据校验失败，请重新扫描", error); }
    }
}
