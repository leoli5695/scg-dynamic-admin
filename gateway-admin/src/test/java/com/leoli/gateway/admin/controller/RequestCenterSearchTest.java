package com.leoli.gateway.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leoli.gateway.admin.exception.GlobalExceptionHandler;
import com.leoli.gateway.admin.model.AccessLogEntryEntity;
import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.AccessLogEntryRepository;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import com.leoli.gateway.admin.service.RequestTraceService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.contains;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real repository queries and MVC binding, without starting application infrastructure. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RequestCenterSearchTest {
    private static final String TRACE_URL = "/api/traces/search";
    private static final String HISTORY_URL = "/api/access-log/history/entries";
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 28, 12, 0);
    private static final LocalDateTime END = START.plusHours(1);
    private static final LocalDateTime MIDDLE = START.plusMinutes(30);

    private final List<String> statements = new ArrayList<>();
    private SessionFactory factory;
    private EntityManager em;
    private MockMvc mvc;

    @BeforeAll
    void createDatabaseAndMvc() {
        Configuration configuration = new Configuration()
                .addAnnotatedClass(RequestTrace.class)
                .addAnnotatedClass(AccessLogEntryEntity.class);
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:request-search-" + UUID.randomUUID() + ";MODE=MySQL");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.hbm2ddl.auto", "create-drop");
        configuration.getProperties().put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
            statements.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        });
        factory = configuration.buildSessionFactory();
        em = factory.createEntityManager();
        JpaRepositoryFactory repositories = new JpaRepositoryFactory(em);
        RequestTraceRepository traces = repositories.getRepository(RequestTraceRepository.class);
        AccessLogEntryRepository logs = repositories.getRepository(AccessLogEntryRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RequestTraceService service = new RequestTraceService(traces, null, mapper);
        mvc = MockMvcBuilders.standaloneSetup(
                        new RequestTraceController(service, null, null, null, null, null),
                        new AccessLogConfigController(null, null, logs, mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @BeforeEach
    void beginTransaction() {
        em.getTransaction().begin();
        statements.clear();
    }

    @AfterEach
    void rollback() {
        em.getTransaction().rollback();
        em.clear();
    }

    @AfterAll
    void closeDatabase() {
        if (em != null) em.close();
        if (factory != null) factory.close();
    }

    @Test
    void tracesCombineAllFiltersBeforePaging() throws Exception {
        long first = trace("exact-trace", "gw-a", "orders", "GET", 201, MIDDLE);
        long second = trace("second", "gw-a", "orders", "GET", 201, MIDDLE);
        trace("other-instance", "gw-b", "orders", "GET", 201, MIDDLE);
        trace("other-route", "gw-a", "orders-extra", "GET", 201, MIDDLE);
        trace("other-method", "gw-a", "orders", "POST", 201, MIDDLE);
        trace("other-status", "gw-a", "orders", "GET", 200, MIDDLE);
        trace("too-old", "gw-a", "orders", "GET", 201, START.minusSeconds(1));
        trace("too-new", "gw-a", "orders", "GET", 201, END.plusSeconds(1));

        mvc.perform(traceRequest().param("routeId", "orders").param("method", "GET")
                        .param("statusCode", "201").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(exactTraceRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.data").doesNotExist());
        assertDatabasePagination();
    }

    @ParameterizedTest
    @CsvSource({
            "instanceId,gw-b", "instanceId,missing-instance", "routeId,order", "traceId,exact",
            "traceId,%", "method,POST", "statusCode,200",
            "start,2026-10-28T12:40:00", "end,2026-10-28T12:20:00"
    })
    void exactTraceIdNeverBypassesOtherFilters(String parameter, String value) throws Exception {
        trace("exact-trace", "gw-a", "orders", "GET", 201, MIDDLE);
        mvc.perform(exactTraceRequest().with(http -> { http.setParameter(parameter, value); return http; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void tracePagesHaveStableTimeAndIdOrderAndInclusiveBoundaries() throws Exception {
        long oldest = trace("oldest", "gw-a", null, null, null, START);
        long tiedFirst = trace("tied-first", "gw-a", "orders", "GET", 200, MIDDLE);
        long tiedSecond = trace("tied-second", "gw-a", "orders", "GET", 200, MIDDLE);
        long latest = trace("latest", "gw-a", "orders", "GET", 200, END);
        trace("foreign-latest", "gw-b", "orders", "GET", 200, END);
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(traceRequest().param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].id", contains((int) latest, (int) tiedSecond)))
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.totalPages").value(2));
        }
        mvc.perform(traceRequest().param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains((int) tiedFirst, (int) oldest)))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.totalElements").value(4));
        mvc.perform(traceRequest().param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(4));
        assertDatabasePagination();
    }

    @Test
    void historyCombinesEveryFilterIncludingTraceTimeAndStatus() throws Exception {
        long match = log("shared", "gw-a", "orders", "GET", 201, "/api/orders/1", MIDDLE);
        log("shared", "gw-b", "orders", "GET", 201, "/api/orders/1", MIDDLE);
        log("shared", "gw-a", "orders-extra", "GET", 201, "/api/orders/1", MIDDLE);
        log("shared", "gw-a", "orders", "POST", 201, "/api/orders/1", MIDDLE);
        log("shared", "gw-a", "orders", "GET", 200, "/api/orders/1", MIDDLE);
        log("shared", "gw-a", "orders", "GET", 201, "/api/users/1", MIDDLE);
        log("shared-extra", "gw-a", "orders", "GET", 201, "/api/orders/1", MIDDLE);
        log("shared", "gw-a", "orders", "GET", 201, "/api/orders/1", START.minusSeconds(1));
        log("shared", "gw-a", "orders", "GET", 201, "/api/orders/1", END.plusSeconds(1));

        mvc.perform(exactHistoryRequest().param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.entries[0].id").value(match))
                .andExpect(jsonPath("$.data.entries[0].routeId").value("orders"))
                .andExpect(jsonPath("$.data.entries[0].statusCode").value(201))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.history").value(true));
    }

    @ParameterizedTest
    @CsvSource({"method,GET", "statusCode,201", "path,orders", "traceId,shared", "routeId,orders"})
    void historySupportsEachOptionalFilterAlone(String parameter, String value) throws Exception {
        long match = log("shared", "gw-a", "orders", "GET", 201, "/api/orders/1", MIDDLE);
        log("other", "gw-a", "users", "POST", 200, "/api/users/1", MIDDLE);
        mvc.perform(historyRequest().param(parameter, value))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.entries[0].id").value(match));
    }

    @ParameterizedTest
    @CsvSource({
            "instanceId,gw-b", "routeId,order", "traceId,sha", "method,POST", "path,users", "statusCode,200",
            "startTime,2026-10-28T12:40:00Z", "endTime,2026-10-28T12:20:00Z"
    })
    void historyTraceIdNeverBypassesOtherFilters(String parameter, String value) throws Exception {
        log("shared", "gw-a", "orders", "GET", 201, "/api/orders/1", MIDDLE);
        mvc.perform(exactHistoryRequest().with(http -> { http.setParameter(parameter, value); return http; }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries").isEmpty())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    void historyPagesHaveStableTimeAndIdOrderAndInclusiveBoundaries() throws Exception {
        long oldest = log("shared", "gw-a", "orders", "GET", 201, "/orders", START);
        long tiedFirst = log("shared", "gw-a", "orders", "GET", 201, "/orders", MIDDLE);
        long tiedSecond = log("shared", "gw-a", "orders", "GET", 201, "/orders", MIDDLE);
        long latest = log("shared", "gw-a", "orders", "GET", 201, "/orders", END);
        log("shared", "gw-b", "orders", "GET", 201, "/orders", END);
        for (int repeat = 0; repeat < 2; repeat++) {
            mvc.perform(exactHistoryRequest().param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.entries[*].id", contains((int) latest, (int) tiedSecond)))
                    .andExpect(jsonPath("$.data.total").value(4))
                    .andExpect(jsonPath("$.data.totalPages").value(2));
        }
        mvc.perform(exactHistoryRequest().param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[*].id", contains((int) tiedFirst, (int) oldest)))
                .andExpect(jsonPath("$.data.total").value(4));
        mvc.perform(exactHistoryRequest().param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries").isEmpty())
                .andExpect(jsonPath("$.data.total").value(4));
        assertDatabasePagination();
    }

    @Test
    void historyRetainsDefaultInstanceTimeWindowAndPagination() throws Exception {
        LocalDateTime recent = LocalDateTime.now().minusMinutes(1);
        long match = log("recent", "default", "orders", "GET", 200, "/orders", recent);
        log("old", "default", "orders", "GET", 200, "/orders", recent.minusHours(2));
        log("foreign", "gw-a", "orders", "GET", 200, "/orders", recent);
        mvc.perform(get(HISTORY_URL).param("method", "").param("path", "").param("traceId", "").param("routeId", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries[0].id").value(match))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(50));
    }

    @ParameterizedTest
    @ValueSource(strings = {TRACE_URL, HISTORY_URL})
    void acceptsIsoZFractionalSecondsAndEqualTimeBounds(String url) throws Exception {
        LocalDateTime instant = MIDDLE.plusNanos(123_000_000);
        trace("fractional", "gw-a", "orders", "GET", 200, instant);
        log("fractional", "gw-a", "orders", "GET", 200, "/orders", instant);
        MockHttpServletRequestBuilder request = get(url).param("instanceId", "gw-a").param("size", "200");
        String prefix;
        if (TRACE_URL.equals(url)) {
            request.param("start", instant + "Z").param("end", instant + "Z");
            prefix = "$.totalElements";
        } else {
            request.param("startTime", instant + "Z").param("endTime", instant + "Z");
            prefix = "$.data.total";
        }
        mvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath(prefix).value(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"instanceId", "start", "end"})
    void traceSearchRequiresInstanceAndBothTimes(String missing) throws Exception {
        mvc.perform(traceRequest().with(http -> { http.removeParameter(missing); return http; }))
                .andExpect(status().isBadRequest());
        assertTrue(statements.isEmpty(), "Invalid input must not query or fall back to all instances");
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void invalidSearchParametersAre400WithoutQuerying(String url, String parameter, String value) throws Exception {
        MockHttpServletRequestBuilder request = TRACE_URL.equals(url) ? traceRequest() : historyRequest();
        mvc.perform(request.with(http -> { http.setParameter(parameter, value); return http; }))
                .andExpect(status().isBadRequest());
        assertTrue(statements.isEmpty(), "Invalid input must be rejected before executing a query");
    }

    static Stream<Arguments> invalidParameters() {
        List<Arguments> cases = new ArrayList<>();
        for (String url : List.of(TRACE_URL, HISTORY_URL)) {
            for (String[] parameter : new String[][] {
                    {"instanceId", ""}, {"instanceId", "   "}, {"page", "-1"}, {"page", "2147483647"},
                    {"page", "2147483648"}, {"page", "abc"}, {"size", "0"}, {"size", "-1"},
                    {"size", "201"}, {"size", "abc"}, {"statusCode", "99"}, {"statusCode", "600"},
                    {"statusCode", "2xx"}, {"statusCode", "201.5"}
            }) {
                cases.add(Arguments.of(url, parameter[0], parameter[1]));
            }
            String start = TRACE_URL.equals(url) ? "start" : "startTime";
            String end = TRACE_URL.equals(url) ? "end" : "endTime";
            for (String badDate : List.of("", "garbage", "2026-02-30T12:00:00Z", "2026-10-28", "2026-10-28T12:00:00junk")) {
                cases.add(Arguments.of(url, start, badDate));
                cases.add(Arguments.of(url, end, badDate));
            }
            cases.add(Arguments.of(url, start, END.plusSeconds(1).toString()));
            cases.add(Arguments.of(url, end, START.minusSeconds(1).toString()));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 599})
    void httpStatusBoundsAreAcceptedAsExactValues(int statusCode) throws Exception {
        trace("status-bound", "gw-a", "orders", "GET", statusCode, MIDDLE);
        log("status-bound", "gw-a", "orders", "GET", statusCode, "/orders", MIDDLE);
        mvc.perform(traceRequest().param("statusCode", String.valueOf(statusCode)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(historyRequest().param("statusCode", String.valueOf(statusCode)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(1));
    }

    private MockHttpServletRequestBuilder traceRequest() {
        return get(TRACE_URL).param("instanceId", "gw-a").param("start", START.toString()).param("end", END.toString());
    }

    private MockHttpServletRequestBuilder exactTraceRequest() {
        return traceRequest().param("routeId", "orders").param("traceId", "exact-trace")
                .param("method", "GET").param("statusCode", "201");
    }

    private MockHttpServletRequestBuilder historyRequest() {
        return get(HISTORY_URL).param("instanceId", "gw-a").param("startTime", START + "Z").param("endTime", END + "Z");
    }

    private MockHttpServletRequestBuilder exactHistoryRequest() {
        return historyRequest().param("routeId", "orders").param("traceId", "shared")
                .param("method", "GET").param("statusCode", "201").param("path", "orders");
    }

    private long trace(String traceId, String instance, String route, String method, Integer status, LocalDateTime time) {
        RequestTrace trace = new RequestTrace();
        trace.setTraceId(traceId);
        trace.setInstanceId(instance);
        trace.setRouteId(route);
        trace.setMethod(method);
        trace.setStatusCode(status);
        trace.setTraceTime(time);
        em.persist(trace);
        return trace.getId();
    }

    private long log(String traceId, String instance, String route, String method, int status, String path, LocalDateTime time) {
        AccessLogEntryEntity log = new AccessLogEntryEntity();
        log.setTraceId(traceId);
        log.setInstanceId(instance);
        log.setRouteId(route);
        log.setMethod(method);
        log.setStatusCode(status);
        log.setPath(path);
        log.setDurationMs(1L);
        log.setLogTimestamp(time);
        em.persist(log);
        return log.getId();
    }

    private void assertDatabasePagination() {
        // Hibernate omits OFFSET for page zero, but still limits the rows in SQL.
        assertTrue(statements.stream().anyMatch(sql -> sql.contains("fetch first")),
                "Pagination must execute in SQL, not after loading all rows");
        assertTrue(statements.stream().anyMatch(sql -> sql.contains("count(")),
                "Page totals must use the filtered database count");
    }
}
