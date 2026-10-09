package com.leoli.gateway.admin.service.copilot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PendingActionStore 单元测试。
 * 回归重点：票据一次性消费、取出的是服务端保存的原始动作（防篡改）、未知/空票据被拒。
 */
class PendingActionStoreTest {

    private final PendingActionStore store = new PendingActionStore();

    @Test
    @DisplayName("登记后可凭票据取出原始 toolName 与参数")
    void registerAndConsume() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("routeId", "abc-123");
        String ticket = store.register("delete_route", args);
        assertNotNull(ticket);

        PendingActionStore.Entry entry = store.consume(ticket);
        assertNotNull(entry);
        assertEquals("delete_route", entry.getToolName());
        assertEquals("abc-123", entry.getArguments().get("routeId"));
    }

    @Test
    @DisplayName("票据一次性：再次消费返回 null（防重放）")
    void ticketIsSingleUse() {
        String ticket = store.register("toggle_route", Map.of("routeId", "r1"));
        assertNotNull(store.consume(ticket));
        assertNull(store.consume(ticket), "同一票据不应被消费两次");
    }

    @Test
    @DisplayName("未知/空票据被拒")
    void unknownTicketRejected() {
        assertNull(store.consume(null));
        assertNull(store.consume(""));
        assertNull(store.consume("does-not-exist"));
    }

    @Test
    @DisplayName("保存的是参数副本：登记后修改原 map 不影响待执行动作")
    void argumentsAreCopied() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("routeId", "original");
        String ticket = store.register("modify_route", args);

        args.put("routeId", "tampered"); // 调用方事后篡改原 map

        PendingActionStore.Entry entry = store.consume(ticket);
        assertNotNull(entry);
        assertEquals("original", entry.getArguments().get("routeId"),
                "存储应持有登记时的参数副本，不受事后篡改影响");
    }
}
