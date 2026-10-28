package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.DistributedTraceEntity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DistributedTraceBatchWriterTest {
    @Test void retryIsIdempotentAndInvalidBatchRollsBack() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:trace-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source); var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("""
            CREATE TABLE distributed_trace (id BIGINT AUTO_INCREMENT PRIMARY KEY,report_id VARCHAR(36) UNIQUE,
            trace_id VARCHAR(36) NOT NULL,service_name VARCHAR(255) NOT NULL,path VARCHAR(500),method VARCHAR(10),
            total_duration_ms BIGINT,status_code INT,success BOOLEAN,error_message VARCHAR(500),client_ip VARCHAR(50),
            spans MEDIUMTEXT,trace_time TIMESTAMP,created_at TIMESTAMP NOT NULL,is_slow BOOLEAN,gateway_instance_id VARCHAR(36),
            upstream_service VARCHAR(255),downstream_services VARCHAR(1000),capture_mode VARCHAR(20),dropped_span_count INT,
            open_span_count INT,start_time_ms BIGINT)
            """);
        var writer = new DiagnosticBatchWriter(jdbc); var record = record("one");
        tx.executeWithoutResult(status -> writer.writeDistributedTraces(List.of(record)));
        tx.executeWithoutResult(status -> writer.writeDistributedTraces(List.of(record)));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM distributed_trace", Integer.class));
        var row = jdbc.queryForMap("SELECT * FROM distributed_trace"); assertEquals("SAMPLED", row.get("CAPTURE_MODE"));
        assertNotNull(row.get("CREATED_AT")); assertEquals("中文节点", row.get("SPANS"));
        var invalid = record("bad"); invalid.setServiceName(null);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status -> writer.writeDistributedTraces(List.of(record("two"), invalid))));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM distributed_trace", Integer.class));
    }
    private DistributedTraceEntity record(String id) {
        var trace = new DistributedTraceEntity(); trace.setReportId(UUID.nameUUIDFromBytes(id.getBytes()).toString());
        trace.setTraceId("trace"); trace.setServiceName("orders"); trace.setSpans("中文节点"); trace.setCaptureMode("SAMPLED"); return trace;
    }
}
