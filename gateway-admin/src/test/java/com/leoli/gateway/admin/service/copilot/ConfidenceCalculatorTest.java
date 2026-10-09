package com.leoli.gateway.admin.service.copilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfidenceCalculator 单元测试。
 * 重点回归：成功率以"去重后的成功工具数 / 去重调用数"计算，并被 clamp 到 [0,1]，
 * 不会因同一工具多次成功而 &gt; 1。
 */
class ConfidenceCalculatorTest {

    private final ConfidenceCalculator calc = new ConfidenceCalculator();

    @Test
    @DisplayName("无工具调用 => 基础分 70")
    void noTools_baseConfidence() {
        assertEquals(70, calc.calculate(Set.of(), 0, Set.of()));
    }

    @Test
    @DisplayName("单工具成功 => 70 + 15(工具) + 5(成功率100%) = 90")
    void singleToolSuccess() {
        assertEquals(90, calc.calculate(Set.of("get_routes"), 1, Set.of()));
    }

    @Test
    @DisplayName("3 工具全成功 => 70 +15 +5(多工具) +5(成功率) = 95")
    void multiToolAllSuccess() {
        assertEquals(95, calc.calculate(Set.of("a", "b", "c"), 3, Set.of()));
    }

    @Test
    @DisplayName("成功率低于 50% => 扣分：4 调用 1 成功 = 70 +15 +5(多工具) -10 = 80")
    void lowSuccessRatePenalty() {
        assertEquals(80, calc.calculate(Set.of("a", "b", "c", "d"), 1, Set.of()));
    }

    @Test
    @DisplayName("成功数超过调用数被 clamp，成功率不超过 1，结果不越界")
    void successCountClamped() {
        int confidence = calc.calculate(Set.of("a"), 5, Set.of());
        // 70 + 15(工具) + 5(clamp 后成功率=1.0) = 90
        assertEquals(90, confidence);
        assertTrue(confidence <= 100);
    }

    @Test
    @DisplayName("数据完整性加分叠加并封顶 100")
    void dataCompletenessBonusCapped() {
        int confidence = calc.calculate(
                Set.of("get_routes"), 1,
                Set.of("route", "service", "metrics", "diagnostic"));
        // 70 +15 +5(route+service) +3(metrics) +2(diagnostic) +5(成功率) = 100
        assertEquals(100, confidence);
    }
}
