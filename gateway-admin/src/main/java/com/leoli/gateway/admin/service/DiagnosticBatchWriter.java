package com.leoli.gateway.admin.service;

import com.leoli.gateway.admin.model.FilterChainExecution;
import com.leoli.gateway.admin.model.RequestTrace;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/** JDBC batching avoids Hibernate's per-row IDENTITY inserts and their network round trips. */
@Service
@RequiredArgsConstructor
@Transactional
public class DiagnosticBatchWriter {
    private final JdbcTemplate jdbc;

    public int writeTraces(List<RequestTrace> batch) {
        if (batch.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        jdbc.batchUpdate("""
                INSERT INTO request_trace (instance_id,trace_id,route_id,method,uri,path,query_string,
                request_headers,request_body,status_code,response_headers,response_body,target_instance,
                latency_ms,error_message,error_type,client_ip,user_agent,trace_type,replayable,replay_type,
                replay_count,last_replay_result,trace_time,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, batch, batch.size(), (ps, trace) -> {
            ps.setString(1, trace.getInstanceId()); ps.setString(2, trace.getTraceId());
            ps.setString(3, trace.getRouteId()); ps.setString(4, trace.getMethod());
            ps.setString(5, trace.getUri()); ps.setString(6, trace.getPath());
            ps.setString(7, trace.getQueryString()); ps.setString(8, trace.getRequestHeaders());
            ps.setString(9, trace.getRequestBody()); ps.setObject(10, trace.getStatusCode(), Types.INTEGER);
            ps.setString(11, trace.getResponseHeaders()); ps.setString(12, trace.getResponseBody());
            ps.setString(13, trace.getTargetInstance()); ps.setObject(14, trace.getLatencyMs(), Types.BIGINT);
            ps.setString(15, trace.getErrorMessage()); ps.setString(16, trace.getErrorType());
            ps.setString(17, trace.getClientIp()); ps.setString(18, trace.getUserAgent());
            ps.setString(19, trace.getTraceType()); ps.setObject(20, trace.getReplayable(), Types.BOOLEAN);
            ps.setString(21, trace.getReplayType()); ps.setObject(22, trace.getReplayCount(), Types.INTEGER);
            ps.setString(23, trace.getLastReplayResult());
            ps.setTimestamp(24, Timestamp.valueOf(trace.getTraceTime() == null ? now : trace.getTraceTime()));
            ps.setTimestamp(25, Timestamp.valueOf(trace.getCreatedAt() == null ? now : trace.getCreatedAt()));
        });
        return batch.size();
    }

    public int writeExecutions(List<FilterChainExecution> batch) {
        if (batch.isEmpty()) return 0;
        LocalDateTime now = LocalDateTime.now();
        jdbc.batchUpdate("""
                INSERT INTO filter_chain_execution (trace_id,filter_name,filter_order,duration_ms,
                duration_micros,self_time_ms,self_time_micros,success,error_message,time_percentage,instance_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, batch, batch.size(), (ps, execution) -> {
            ps.setString(1, execution.getTraceId()); ps.setString(2, execution.getFilterName());
            ps.setObject(3, execution.getFilterOrder(), Types.INTEGER);
            ps.setObject(4, execution.getDurationMs(), Types.BIGINT);
            ps.setObject(5, execution.getDurationMicros(), Types.BIGINT);
            ps.setObject(6, execution.getSelfTimeMs(), Types.BIGINT);
            ps.setObject(7, execution.getSelfTimeMicros(), Types.BIGINT);
            ps.setObject(8, execution.getSuccess(), Types.BOOLEAN);
            ps.setString(9, execution.getErrorMessage());
            ps.setObject(10, execution.getTimePercentage(), Types.DOUBLE);
            ps.setString(11, execution.getInstanceId());
            ps.setTimestamp(12, Timestamp.valueOf(execution.getCreatedAt() == null ? now : execution.getCreatedAt()));
        });
        return batch.size();
    }
}
