package com.example.server.service.ingest;

import com.example.server.utils.VideoImportKeys;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit 下载锁契约。
 *
 * <p>它不是执行权来源（执行权永远是数据库 CAS），而是"这个单元现在有没有人在下载"的存活信号：
 * 下载期间 {@code updated_at} 不变，恢复扫描只有靠它才能区分"正在下载"和"进程已死"。
 *
 * <p>最关键的一条是**降级放行**：锁服务不可用时必须返回已获得，让数据库 CAS 单独保证正确性——
 * 绝不能因为 Redis 故障而让整个导入链路停摆（契约 §10）。
 */
class ImportUnitLockTest {

    private static final Long USER_ID = 7L;
    private static final String SOURCE_HASH = "abc123";

    private final RedissonClient redissonClient = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);

    private final ImportUnitLock unitLock = new ImportUnitLock(redissonClient);

    @Test
    void grantedWhenLockIsFree() throws Exception {
        when(redissonClient.getLock(VideoImportKeys.lockUnit(USER_ID, SOURCE_HASH))).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);

        try (ImportUnitLock.Handle handle = unitLock.tryLockUnit(USER_ID, SOURCE_HASH)) {
            assertTrue(handle.acquired());
        }

        // 释放必须只作用于当前线程持有的锁。
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        unitLock.tryLockUnit(USER_ID, SOURCE_HASH);
        verify(lock, org.mockito.Mockito.atLeastOnce()).tryLock(anyLong(), any(TimeUnit.class));
    }

    @Test
    void rejectedWhenAnotherProcessHoldsIt() throws Exception {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        try (ImportUnitLock.Handle handle = unitLock.tryLockUnit(USER_ID, SOURCE_HASH)) {
            assertFalse(handle.acquired(), "未拿到锁时调用方必须直接返回，不产生任何副作用");
        }
    }

    @Test
    void contentLockSupportsBoundedWaitForArtifactGate() throws Exception {
        when(redissonClient.getLock(VideoImportKeys.lockContent(11L))).thenReturn(lock);
        when(lock.tryLock(3, TimeUnit.SECONDS)).thenReturn(false);

        try (ImportUnitLock.Handle handle = unitLock.tryLockContent(11L, 3, TimeUnit.SECONDS)) {
            assertFalse(handle.acquired());
        }

        verify(lock).tryLock(3, TimeUnit.SECONDS);
    }

    /** 降级放行：锁服务不可用时不能让导入链路停摆。 */
    @Test
    void redisFailureFailsOpen() {
        when(redissonClient.getLock(anyString())).thenThrow(new IllegalStateException("redis down"));

        try (ImportUnitLock.Handle handle = unitLock.tryLockUnit(USER_ID, SOURCE_HASH)) {
            assertTrue(handle.acquired(), "锁不可用时按契约走数据库 CAS 路径");
        }
    }

    @Test
    void lockStateIsReportedForLivenessCheck() {
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isLocked()).thenReturn(true);

        assertTrue(unitLock.isUnitLocked(USER_ID, SOURCE_HASH));
    }

    /** 判断不了"是否有人持锁"时不能当成"有人在下载"，否则恢复会被永久跳过。 */
    @Test
    void lockStateFailureIsReportedAsNotLocked() {
        when(redissonClient.getLock(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertFalse(unitLock.isUnitLocked(USER_ID, SOURCE_HASH));
    }

    @Test
    void missingIdentitySkipsLocking() {
        assertTrue(unitLock.tryLockUnit(null, SOURCE_HASH).acquired());
        assertFalse(unitLock.isUnitLocked(USER_ID, " "));
    }
}
