package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.UserBilibiliCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserBilibiliCredentialMapper extends BaseMapper<UserBilibiliCredential> {

    /**
     * 覆盖 Cookie 时只更新凭证和更新时间，避免把查询出来的旧 updated_at 回写，
     * 从而绕过 MySQL 的 ON UPDATE CURRENT_TIMESTAMP 机制。
     */
    @Update("""
            UPDATE user_bilibili_credentials
               SET cookie_encrypted = #{cookieEncrypted},
                   updated_at = CURRENT_TIMESTAMP(3)
             WHERE user_id = #{userId}
            """)
    int updateCookie(@Param("userId") Long userId,
                     @Param("cookieEncrypted") String cookieEncrypted);
}
