package com.example.server.service.ingest;

import com.example.server.config.VideoImportProperties;
import com.example.server.dto.VideoImportErrorCode;
import com.example.server.entity.MediaFile;
import com.example.server.source.AcquiredMedia;
import com.example.server.source.SourceAdapterRegistry;
import com.example.server.source.VideoSourceAdapter;
import com.example.server.source.VideoSourceException;
import com.example.server.source.VideoSourceUnit;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

/**
 * 获取单个 Unit 的媒体并写入对象存储。
 *
 * <p>这是模块一的“媒体存储边界”：Adapter 只负责把内容拉到本地临时文件，哈希、对象命名、MinIO 写入和
 * 临时文件清理都在这里完成，因此对象引用与内容哈希的语义只有一处定义。
 *
 * <p>对象名是**内容寻址**的：{@code video-import/content/{contentHash}/source{suffix}}（D-068）。
 * 同一份内容无论被多少用户导入都落在同一个对象上，重复执行只会幂等覆盖，不产生随机 UUID 垃圾；
 * 先前的 {@code video-import/{userId}/{mediaId}/...} 命名会让同一视频存 N 份（实测同一 BVID 最多 7 份）。
 * 改动前的历史记录仍指向各自的旧对象，读取路径不依赖命名规则，因此不需要回溯迁移。
 *
 * <p>字节落盘后立刻登记到共享内容资产上（{@link ContentAssetService#publishBytes}）：
 * 其余用户下次导入同一视频时直接复用该引用，不再下载。
 */
@Component
public class ImportMediaAcquirer {

    private static final Logger log = LoggerFactory.getLogger(ImportMediaAcquirer.class);
    private static final String OBJECT_PREFIX = "video-import";
    /** 内容寻址前缀：对象只按内容哈希分片，与用户无关（D-068）。 */
    private static final String CONTENT_PREFIX = OBJECT_PREFIX + "/content";
    private static final String DEFAULT_SUFFIX = ".mp4";

    private final SourceAdapterRegistry adapterRegistry;
    private final MediaService mediaService;
    private final MinioUtils minioUtils;
    private final VideoImportProperties properties;

    public ImportMediaAcquirer(SourceAdapterRegistry adapterRegistry,
                               MediaService mediaService,
                               MinioUtils minioUtils,
                               VideoImportProperties properties) {
        this.adapterRegistry = adapterRegistry;
        this.mediaService = mediaService;
        this.minioUtils = minioUtils;
        this.properties = properties;
    }

    /** 一次获取的持久化结果；只包含受管对象引用与内容哈希，不含本机临时路径。 */
    public record StoredMedia(String objectUrl, String contentHash, String contentType,
                              long sizeBytes, String coverObjectUrl) {
    }

    /** 默认画质、匿名获取入口：等价于 {@code acquireAndStore(media, null, null)}。 */
    public StoredMedia acquireAndStore(MediaFile media) {
        return acquireAndStore(media, null, null);
    }

    public StoredMedia acquireAndStore(MediaFile media, Integer height, String cookie) {
        VideoSourceUnit unit = toSourceUnit(media);
        VideoSourceAdapter adapter = adapterRegistry.requireAdapter(URI.create(media.getCanonicalUrl()));
        AcquiredMedia acquired = adapter.acquire(unit, height, cookie);
        File tempFile = acquired.file();
        try {
            String contentHash = mediaService.calculateMd5(tempFile);
            StoredMedia stored = store(media, tempFile, acquired.contentType(), contentHash);
            // 封面是展示增强：抓取或转存失败只能降级为"没有封面"，绝不影响已经落盘的媒体。
            String coverObjectUrl = storeCover(adapter, media, contentHash);
            // 注意：字节**登记到共享资产**由调用方在写完用户条目之后做（见 ImportAcquireService）。
            // 顺序不能反——"字节就绪"的广播会让正在等这份内容的用户立刻复用，而广播时若本条目还没
            // 指向对象，等待者查询会把自己也算进去，白跑一次获取。
            return new StoredMedia(stored.objectUrl(), stored.contentHash(), stored.contentType(),
                    stored.sizeBytes(), coverObjectUrl);
        } catch (IOException e) {
            throw new VideoSourceException(VideoImportErrorCode.MEDIA_ACQUIRE_FAILED, "媒体读取失败", e);
        } catch (VideoSourceException e) {
            throw e;
        } catch (Exception e) {
            throw new VideoSourceException(VideoImportErrorCode.MEDIA_ACQUIRE_FAILED, "媒体写入对象存储失败", e);
        } finally {
            if (!tempFile.delete() && tempFile.exists()) {
                log.warn("video_import_temp_cleanup_failed mediaId={} path={}",
                        media.getId(), tempFile.getAbsolutePath());
            }
        }
    }

    /**
     * 把封面转存为受管对象 {@code video-import/content/{contentHash}/cover{suffix}}。
     *
     * <p>封面与字节同属共享资产：同一视频的封面也不需要每个用户抓一遍、存一份。
     *
     * @return 受管对象地址；平台没有封面或抓取失败时返回 {@code null}
     */
    private String storeCover(VideoSourceAdapter adapter, MediaFile media, String contentHash) {
        String coverUrl = media.getSourceCoverUrl();
        if (coverUrl == null || coverUrl.isBlank()) {
            return null;
        }
        try {
            VideoSourceAdapter.AcquiredCover cover = adapter.fetchCover(coverUrl);
            if (cover == null || cover.bytes() == null || cover.bytes().length == 0) {
                log.info("video_import_cover_unavailable mediaId={}", media.getId());
                return null;
            }
            String objectName = CONTENT_PREFIX + "/" + contentHash + "/cover" + cover.suffix();
            String objectUrl = minioUtils.uploadObject(objectName,
                    new ByteArrayInputStream(cover.bytes()), cover.bytes().length, cover.contentType());
            log.info("video_import_cover_stored mediaId={} bytes={}", media.getId(), cover.bytes().length);
            return objectUrl;
        } catch (Exception e) {
            log.warn("video_import_cover_store_failed mediaId={} reason={}", media.getId(), e.getMessage());
            return null;
        }
    }

    /** 媒体记录的来源身份直接来自数据库，消息只携带 mediaId。 */
    private VideoSourceUnit toSourceUnit(MediaFile media) {
        return new VideoSourceUnit(media.getPlatform(), media.getResourceType(),
                media.getExternalResourceId(), media.getExternalUnitId(),
                media.getCanonicalUrl(), media.getSourceTitle(), media.getSourceAuthor(),
                media.getSourceDurationMs(), media.getSourceCoverUrl(), null);
    }

    private StoredMedia store(MediaFile media, File tempFile, String contentType, String contentHash)
            throws Exception {
        String objectName = CONTENT_PREFIX + "/" + contentHash + "/source" + suffix(tempFile);
        try (InputStream inputStream = Files.newInputStream(tempFile.toPath())) {
            String objectUrl = minioUtils.uploadObject(
                    objectName, inputStream, tempFile.length(), contentType);
            log.info("video_import_media_stored mediaId={} bytes={} timeoutSeconds={}",
                    media.getId(), tempFile.length(), properties.getResolveTimeoutSeconds());
            return new StoredMedia(objectUrl, contentHash, contentType, tempFile.length(), null);
        }
    }

    private String suffix(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || name.length() - dot > 11) {
            return DEFAULT_SUFFIX;
        }
        String suffix = name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        return suffix.matches("\\.[a-z0-9]+") ? suffix : DEFAULT_SUFFIX;
    }
}
