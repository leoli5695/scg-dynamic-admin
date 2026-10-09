package com.leoli.gateway.admin.service.copilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CopilotUsageGuard 单元测试：每分钟限流 + 每日 token 预算 + 关闭态。
 */
class CopilotUsageGuardTest {

    @Test
    @DisplayName("每分钟限流：超过上限即拒，且按用户隔离")
    void rateLimitPerMinute() {
        CopilotUsageGuard guard = new CopilotUsageGuard(2, 0);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        assertTrue(guard.checkRequestAllowed("alice").isPresent(), "第 3 次应被限流");
        assertTrue(guard.checkRequestAllowed("bob").isEmpty(), "限流按用户隔离");
    }

    @Test
    @DisplayName("每日 token 预算：累计超预算即拒")
    void dailyTokenBudget() {
        CopilotUsageGuard guard = new CopilotUsageGuard(0, 100);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        guard.recordUsage("alice", 60);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty(), "60 < 100 仍放行");
        guard.recordUsage("alice", 50);
        assertEquals(110, guard.dailyUsed("alice"));
        assertTrue(guard.checkRequestAllowed("alice").isPresent(), "110 >= 100 应拒");
    }

    @Test
    @DisplayName("限额 <=0 表示关闭对应闸门，始终放行")
    void disabledGatesAlwaysAllow() {
        CopilotUsageGuard guard = new CopilotUsageGuard(0, 0);
        for (int i = 0; i < 100; i++) {
            assertTrue(guard.checkRequestAllowed("alice").isEmpty());
        }
        guard.recordUsage("alice", 1_000_000);
        assertTrue(guard.checkRequestAllowed("alice").isEmpty());
    }

    @Test
    @DisplayName("operator 为 null 归并为 anonymous，不抛异常")
    void nullOperatorIsAnonymous() {
        CopilotUsageGuard guard = new CopilotUsageGuard(1, 0);
        assertTrue(guard.checkRequestAllowed(null).isEmpty());
        assertTrue(guard.checkRequestAllowed(null).isPresent());
        guard.recordUsage(null, 10);
        assertEquals(10, guard.dailyUsed(null));
    }
}
