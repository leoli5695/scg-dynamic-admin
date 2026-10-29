package com.leoli.gateway.admin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.leoli.gateway.admin.model.GatewayInstanceEntity;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class HeartbeatTimeSerializationTest {
    @Test void heartbeatIncludesTheActualServerOffsetAndPreservesItsInstant() throws Exception {
        TimeZone previous = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "Asia/Shanghai", "America/New_York"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                Instant instant = Instant.parse("2026-10-29T13:00:00Z");
                var entity = new GatewayInstanceEntity();
                entity.setLastHeartbeatTime(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()));
                var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
                String value = mapper.readTree(mapper.writeValueAsString(entity)).get("lastHeartbeatTime").asText();
                assertEquals(instant, OffsetDateTime.parse(value).toInstant(), zone);
            }
        } finally { TimeZone.setDefault(previous); }
    }
    @Test void absentHeartbeatStaysAbsent() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assertTrue(mapper.readTree(mapper.writeValueAsString(new GatewayInstanceEntity())).get("lastHeartbeatTime").isNull());
    }
}
