package com.example.server;

import org.junit.jupiter.api.Test;
import org.mockito.exceptions.base.MockitoException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 配置守护：P0 要求 Mockito 固定使用 `mock-maker-subclass`。
 *
 * <p>原因：默认的 inline mock maker 需要 JVM 自附加（ByteBuddy agent），在受限执行环境里会直接失败，
 * 使回归测试无法产生稳定证据。`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
 * 已固定为 `mock-maker-subclass`，它不需要自附加。
 *
 * <p>subclass mock maker 无法模拟 final 类，本测试用这一可观察差异证明配置确实生效；
 * 如果扩展文件被删除或改回 inline，本测试会失败并直接指出原因。
 */
class MockMakerConfigurationTest {

    @Test
    void subclassMockMakerIsActiveSoFinalClassesCannotBeMocked() {
        MockitoException error = assertThrows(MockitoException.class, () -> mock(Duration.class));

        assertTrue(error.getMessage().contains("final class"),
                "期望 subclass mock maker 因 final 类而拒绝模拟，实际信息: " + error.getMessage());
    }
}
