package com.mynora.singverse.media.artist;

import com.mynora.singverse.auth.crypto.AuthCrypto;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArtistArtworkSettings {
    private static final String PURPOSE = "singverse:fanart-personal:v1";
    private final ArtistMapper mapper;
    private final AuthCrypto crypto;

    public ArtistArtworkSettings(ArtistMapper mapper, AuthCrypto crypto) {
        this.mapper = mapper;
        this.crypto = crypto;
    }

    public Map<String, Boolean> view() {
        return Map.of("fanartConfigured", mapper.fanartKey() != null);
    }

    public String key() {
        String encrypted = mapper.fanartKey();
        return encrypted == null ? null : crypto.decrypt(encrypted, PURPOSE);
    }

    @Transactional
    public Map<String, Boolean> save(String key, boolean clear) {
        if (key != null && (key.length() > 256 || key.chars().anyMatch(Character::isISOControl)))
            throw ArtistService.fail(400, "Fanart.tv 密钥无效");
        if (clear && key != null && !key.isBlank()) throw ArtistService.fail(400, "不能同时设置和清除密钥");
        if (clear) mapper.saveFanartKey(null);
        else if (key != null && !key.isBlank()) {
            mapper.saveFanartKey(crypto.encrypt(key.strip(), PURPOSE));
            mapper.requeueMissingBackgrounds();
        }
        return view();
    }
}
