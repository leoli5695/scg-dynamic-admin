package com.leoli.gateway.admin.tool.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.model.AuditLogEntity;
import com.leoli.gateway.admin.model.RouteResponse;
import com.leoli.gateway.admin.repository.AuditLogRepository;
import com.leoli.gateway.admin.service.AuditLogService;
import com.leoli.gateway.admin.service.RouteService;
import com.leoli.gateway.admin.service.StrategyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the audit-log tools, relocated from {@code ToolExecutorTest} when the
 * implementation moved out of {@code ToolExecutor}. The assertions are the same ones that ran
 * through {@code toolExecutor.execute("audit_query", ...)} before, now aimed directly at the handler
 * so a failure points at the tool rather than at the dispatcher.
 *
 * <p>{@code ToolExecutorTest} keeps a separate, thinner test that the dispatch switch still routes
 * these three tool names here - the two layers are deliberately not tested twice.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditToolHandlerTest {

    private static final String INSTANCE_ID = "gateway-1";
    private static final String ROUTE_ID = "route-1";
    private static final String ROUTE_NAME = "api-route";

    @Mock
    private AuditLogService auditLogService;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private RouteService routeService;
    @Mock
    private StrategyService strategyService;

    private AuditToolHandler handler;

    @BeforeEach
    void setUp() {
        handler = new AuditToolHandler(auditLogService, auditLogRepository, routeService,
                strategyService, new ObjectMapper());
    }

    private AuditLogEntity auditLog(Long id, String operationType, String targetType) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setId(id);
        entity.setOperator("admin");
        entity.setOperationType(operationType);
        entity.setTargetType(targetType);
        entity.setTargetId(ROUTE_ID);
        entity.setTargetName(ROUTE_NAME);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setInstanceId(INSTANCE_ID);
        return entity;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object result) {
        return (Map<String, Object>) result;
    }

    @Test
    @DisplayName("audit_query returns a paged summary with simplified log rows")
    void auditQueryReturnsPagedSummary() {
        Map<String, Object> args = new HashMap<>();
        args.put("hours", 24);
        args.put("page", 0);
        args.put("size", 10);

        Page<AuditLogEntity> page = new PageImpl<>(
                List.of(auditLog(1L, "CREATE", "ROUTE"), auditLog(2L, "UPDATE", "ROUTE")));
        when(auditLogService.getAuditLogs(any(), any(), any(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(page);

        Map<String, Object> data = asMap(handler.auditQuery(args));

        assertEquals(2L, data.get("totalElements"));
        assertNotNull(data.get("logs"));
        assertEquals("24 hours", data.get("queryTimeRange"));
    }

    @Test
    @DisplayName("audit_query forwards the targetType filter to the service")
    void auditQueryForwardsTargetTypeFilter() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetType", "ROUTE");
        args.put("hours", 24);

        when(auditLogService.getAuditLogs(any(), eq("ROUTE"), any(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(auditLog(1L, "CREATE", "ROUTE"))));

        handler.auditQuery(args);

        verify(auditLogService).getAuditLogs(any(), eq("ROUTE"), any(), any(),
                any(LocalDateTime.class), any(LocalDateTime.class), anyInt(), anyInt());
    }

    @Test
    @DisplayName("audit_diff returns the formatted change set")
    void auditDiffReturnsFormattedChanges() {
        Map<String, Object> args = new HashMap<>();
        args.put("logId", 1L);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("id", 1L);
        diff.put("targetType", "ROUTE");
        diff.put("changes", Map.of("enabled", Map.of("old", "true", "new", "false")));
        when(auditLogService.getDiff(1L)).thenReturn(diff);

        Map<String, Object> data = asMap(handler.auditDiff(args));

        assertEquals(1L, data.get("id"));
        assertEquals("ROUTE", data.get("targetType"));
        assertNotNull(data.get("changes"));
    }

    @Test
    @DisplayName("audit_diff without logId throws naming the missing argument")
    void auditDiffWithoutLogIdThrows() {
        // At handler level this is a thrown IllegalArgumentException; ToolExecutor.execute is what
        // turns it into an error ToolResult the model can read. The message is the contract, because
        // the dispatcher's error text is asserted on by ToolExecutorTest.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> handler.auditDiff(new HashMap<>()));
        assertTrue(e.getMessage().contains("Required argument missing"), e.getMessage());
    }

    /**
     * diagnose_state_inconsistency had no test at all before this move, despite being ~150 lines that
     * read the live route state, replay the audit history and generate a bilingual conclusion. This
     * covers the "user expected disabled but the route is enabled, and the audit trail shows who
     * re-enabled it" path, which is the case the tool exists for.
     */
    @Test
    @DisplayName("diagnose_state_inconsistency reports the mismatch and the operation that caused it")
    void diagnoseStateInconsistencyReportsMismatch() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetType", "ROUTE");
        args.put("targetId", ROUTE_ID);
        args.put("userExpectedState", "disabled");
        args.put("language", "zh");

        RouteResponse route = new RouteResponse();
        route.setId(ROUTE_ID);
        route.setRouteName(ROUTE_NAME);
        route.setEnabled(true);
        route.setUri("lb://api-service");
        when(routeService.getRouteResponse(ROUTE_ID)).thenReturn(route);

        AuditLogEntity enableEvent = auditLog(9L, "ENABLE", "ROUTE");
        when(auditLogRepository.findRecentStateChangesForTarget("ROUTE", ROUTE_ID, 10))
                .thenReturn(List.of(enableEvent));

        Map<String, Object> data = asMap(handler.diagnoseStateInconsistency(args));

        assertEquals("ROUTE", data.get("targetType"));
        assertEquals(ROUTE_ID, data.get("targetId"));
        assertEquals(Boolean.FALSE, data.get("isSystemBug"),
                "a state change caused by a recorded ENABLE is not a system bug");

        Map<String, Object> currentState = asMap(data.get("currentState"));
        assertEquals(Boolean.TRUE, currentState.get("exists"));
        assertEquals(Boolean.TRUE, currentState.get("enabled"));

        Map<String, Object> analysis = asMap(data.get("analysis"));
        assertEquals(Boolean.TRUE, analysis.get("stateMismatch"),
                "user expected disabled but the route is enabled");
        assertNotNull(analysis.get("lastStateChange"));
        assertNotNull(data.get("conclusion"), "a zh conclusion must be produced");
        // The ENABLE branch of analyzeStateInconsistency produces this wording; asserting on it pins
        // that the last state change was correctly attributed to the recorded ENABLE event.
        assertTrue(String.valueOf(data.get("conclusion")).contains("重新启用"),
                "conclusion should attribute the mismatch to the re-enable operation, got: " + data.get("conclusion"));
        assertEquals("如需禁用，请调用 toggle_route 工具禁用该路由。", data.get("suggestion"));

        // instanceId was absent, so the non-instance-scoped lookup is the one that must be used.
        verify(auditLogRepository).findRecentStateChangesForTarget("ROUTE", ROUTE_ID, 10);
    }

    @Test
    @DisplayName("diagnose_state_inconsistency uses the instance-scoped lookup when instanceId is given")
    void diagnoseStateInconsistencyScopesByInstance() {
        Map<String, Object> args = new HashMap<>();
        args.put("targetType", "ROUTE");
        args.put("targetId", ROUTE_ID);
        args.put("instanceId", INSTANCE_ID);

        RouteResponse route = new RouteResponse();
        route.setId(ROUTE_ID);
        route.setRouteName(ROUTE_NAME);
        route.setEnabled(false);
        when(routeService.getRouteResponse(ROUTE_ID)).thenReturn(route);
        when(auditLogRepository.findRecentStateChangesForTargetByInstanceId(
                INSTANCE_ID, "ROUTE", ROUTE_ID, 10)).thenReturn(List.of());

        handler.diagnoseStateInconsistency(args);

        verify(auditLogRepository).findRecentStateChangesForTargetByInstanceId(
                INSTANCE_ID, "ROUTE", ROUTE_ID, 10);
    }
}
