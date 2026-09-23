package com.example.server.service.ingest;

import com.example.server.utils.VideoImportKeys;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Unit 获取阶段的分布式锁（契约 §7.3：{@code lock:video:import:{userId}:{sourceHash}}）。
 *
 * <p><b>它不是执行权来源</b>：执行权永远由 {@code QUEUED → ACQUIRING} 的数据库 CAS 给出。
 * 这把锁只解决一个 CAS 解决不了的问题：**"这个单元现在到底有没有人在下载"**。
 *
 * <p>为什么需要它：下载可能持续几分钟，而 `updated_at` 在整个下载期间不会变化。恢复扫描若只看超时，
 * 就会把"正在下载"的媒体判成卡死、重置状态并重投——结果是同一单元被重复下载，而且恢复预算被这种
 * 假故障快速消耗掉，真到需要重投时已经耗尽。锁在这里就是存活信号：
 * <ul>
 *   <li>持锁 = 进程还活着，正在下载 → 扫描跳过，不重置、不消耗预算；</li>
 *   <li>进程被杀 → Redisson 看门狗停止续期，锁在几十秒内自动消失 → 扫描可以安全重投。</li>
 * </ul>
 *
 * <p>看门狗续期（不显式指定 leaseTime）是刻意的：固定租约会让崩溃后的恢复要等满租约（契约给的
 * 上限是 30 分钟），而这里需要的是"进程活着锁就在"的语义（D-061）。
 *
 * <p>Redis 不可用时降级放行（契约 §10）：返回一个"已获得"的空句柄，让数据库 CAS 单独保证正确性，
 * 不允许因为锁服务不可用而让整个导入链路停摆。
 */
@Component
public class ImportUnitLock {

    private static final Logger log = LoggerFactory.getLogger(ImportUnitLock.class);

    private final RedissonClient redissonClient;

    public ImportUnitLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试立刻获得该单元的下载权锁，不等待。
     *
     * @return 锁句柄；未拿到锁时 {@link Handle#acquired()} 为 {@code false}，调用方必须直接返回，
     *         不产生任何副作用（真正的持有者会完成下载并推进状态）
     */
    public Handle tryLockUnit(Long userId, String sourceHash) {
        if (userId == null || sourceHash == null || sourceHash.isBlank()) {
            return new RedissonHandle(null, true);
        }
        return tryLock(VideoImportKeys.lockUnit(userId, sourceHash), "unit");
    }

    /** 该单元当前是否有进程持有下载权（恢复扫描用它判断"是不是真的有人在下载"）。 */
    public boolean isUnitLocked(Long userId, String sourceHash) {
        if (userId == null || sourceHash == null || sourceHash.isBlank()) {
            return false;
        }
        try {
            return redissonClient.getLock(VideoImportKeys.lockUnit(userId, sourceHash)).isLocked();
        } catch (RuntimeException e) {
            // 锁服务不可用时不能把"无法判断"当成"有人在下载"，否则会永久跳过恢复。
            log.warn("video_import_unit_lock_state_unavailable userId={}", userId, e);
            return false;
        }
    }

    /**
     * 尝试立刻获得**共享内容**的下载权锁（D-068），不等待。
     *
     * <p>Unit 锁按 {@code (userId, sourceHash)} 分片，因此两个用户导入同一个新视频时各拿一把锁，
     * 都会去下载——这正是规格 AC-07 要消除的重复："只启动一个共享下载和初始处理任务"。
     * 内容锁按资产 ID 分片，跨用户唯一，配合"拿到锁后再查一次资产字节"的双检，
     * 后到者要么直接复用、要么让出并等恢复扫描重投。
     *
     * <p>与 Unit 锁同样的存活语义（看门狗续期）：持锁 = 真的有人在下载这份内容。
     */
    public Handle tryLockContent(Long assetId) {
        if (assetId == null) {
            // 旧数据没有资产引用：降级放行，由数据库 CAS 单独保证正确性。
            return new RedissonHandle(null, true);
        }
        return tryLock(VideoImportKeys.lockContent(assetId), "content");
    }

    /**
     * 在有界时间内尝试获得共享内容锁。
     *
     * <p>仅给分析输入资产闸门吸收很短的发布窗口；超时不代表失败或成功，调用方仍须以
     * manifest 是否存在作为唯一就绪事实。下载路径继续使用零等待重载，避免长下载占住消费线程。
     */
    public Handle tryLockContent(Long assetId, long waitTime, TimeUnit unit) {
        if (assetId == null) {
            return new RedissonHandle(null, true);
        }
        return tryLock(VideoImportKeys.lockContent(assetId), "content", waitTime, unit);
    }

    /** 该内容资产当前是否有进程在下载（恢复扫描据此跳过等待中的媒体，避免误判卡死并消耗预算）。 */
    public boolean isContentLocked(Long assetId) {
        if (assetId == null) {
            return false;
        }
        try {
            return redissonClient.getLock(VideoImportKeys.lockContent(assetId)).isLocked();
        } catch (RuntimeException e) {
            log.warn("video_import_content_lock_state_unavailable assetId={}", assetId, e);
            return false;
        }
    }

    private Handle tryLock(String key, String kind) {
        return tryLock(key, kind, 0, TimeUnit.SECONDS);
    }

    private Handle tryLock(String key, String kind, long waitTime, TimeUnit unit) {
        RLock lock;
        try {
            lock = redissonClient.getLock(key);
            if (lock.tryLock(waitTime, unit)) {
                log.debug("video_import_{}_lock_acquired key={}", kind, key);
                return new RedissonHandle(lock, true);
            }
            return new RedissonHandle(lock, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RedissonHandle(null, false);
        } catch (RuntimeException e) {
            log.warn("video_import_{}_lock_unavailable key={}", kind, key, e);
            return new RedissonHandle(null, true);
        }
    }

    /** 锁句柄：{@link #close()} 释放锁；未获得锁时是空操作。 */
    public interface Handle extends AutoCloseable {

        boolean acquired();

        @Override
        void close();
    }

    /** 真实实现：只释放当前线程持有的锁。 */
    private record RedissonHandle(RLock lock, boolean acquired) implements Handle {

        @Override
        public void close() {
            if (lock == null || !acquired) {
                return;
            }
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                log.warn("video_import_unit_lock_release_failed key={}", lock.getName(), e);
            }
        }
    }
}
