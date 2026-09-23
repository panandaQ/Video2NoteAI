package com.example.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.server.entity.ContentAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 共享内容资产数据访问。
 *
 * <p>并发收敛点只有一个：{@code uk_content_asset_identity} 唯一约束。两个用户同时导入同一个新视频时，
 * 两端都会尝试插入同一身份，谁先成功谁建行，另一端按唯一键冲突回查复用——与媒体登记同一套路，
 * 不需要额外的分布式协调。
 */
@Mapper
public interface ContentAssetMapper extends BaseMapper<ContentAsset> {

    @Select("""
            SELECT * FROM content_assets
             WHERE platform = #{platform}
               AND resource_type = #{resourceType}
               AND external_resource_id = #{externalResourceId}
               AND external_unit_id = #{externalUnitId}
            """)
    ContentAsset findBySourceUnit(@Param("platform") String platform,
                                  @Param("resourceType") String resourceType,
                                  @Param("externalResourceId") String externalResourceId,
                                  @Param("externalUnitId") String externalUnitId);

    /**
     * 登记字节版本：只在还没有对象引用时写入（首个写入者胜出）。
     *
     * <p>{@code object_ref IS NULL} 是这条 CAS 的全部意义：资产的字节是**不可变**的，
     * 后到的下载者不能覆盖已经发布的内容——否则正在读取旧引用的用户会读到另一份字节。
     * 强制刷新（产生新版本）将来走单独的版本表，而不是更新这一行。
     *
     * @return 1 = 本次写入生效；0 = 已被别人抢先登记或不满足条件
     */
    @Update("""
            UPDATE content_assets
               SET content_hash = #{contentHash},
                   object_ref = #{objectRef},
                   cover_ref = #{coverRef},
                   file_size = #{fileSize},
                   status = 'READY'
             WHERE id = #{id} AND object_ref IS NULL
            """)
    int publishBytes(@Param("id") Long id,
                     @Param("contentHash") String contentHash,
                     @Param("objectRef") String objectRef,
                     @Param("coverRef") String coverRef,
                     @Param("fileSize") Long fileSize);

    /** 封面是展示增强：资产已就绪但封面晚到（或首次抓取失败）时单独补写，不覆盖已有值。 */
    @Update("""
            UPDATE content_assets
               SET cover_ref = #{coverRef}
             WHERE id = #{id} AND cover_ref IS NULL AND #{coverRef} IS NOT NULL
            """)
    int publishCover(@Param("id") Long id, @Param("coverRef") String coverRef);
}
