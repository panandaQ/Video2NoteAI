package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.server.dto.ContentAssetStatus;
import com.example.server.source.VideoPlatform;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跨用户共享的内容资产（D-068）。
 *
 * <p>身份是四段来源（平台 + 资源类型 + 资源 ID + 单元 ID），与用户无关；{@code object_ref} 指向
 * 内容寻址的受管对象。用户条目（{@link MediaFile}）通过 {@code content_asset_id} 引用它，
 * 因此"同一视频被 N 个用户导入"只对应一行资产与一份字节。
 *
 * <p>本表不含任何用户字段——它是内容级事实，不是权限真源（权限真源永远是 media_files 的用户关系）。
 */
@Data
@TableName("content_assets")
public class ContentAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private VideoPlatform platform;
    private String resourceType;
    private String externalResourceId;
    private String externalUnitId;

    private String canonicalUrl;
    private String title;
    private String author;
    private Long durationMs;

    // ---- 当前字节版本 ----
    private String contentHash;
    private String objectRef;
    private String coverRef;
    private Long fileSize;
    private ContentAssetStatus status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
