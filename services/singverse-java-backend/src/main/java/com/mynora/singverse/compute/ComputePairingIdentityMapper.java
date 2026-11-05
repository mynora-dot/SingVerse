package com.mynora.singverse.compute;

import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface ComputePairingIdentityMapper {
    Map<String, Object> find();
    int insert(@Param("publicKey") String publicKey, @Param("privateKey") String privateKey);
}
