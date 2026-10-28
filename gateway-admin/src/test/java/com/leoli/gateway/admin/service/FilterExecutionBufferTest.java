package com.leoli.gateway.admin.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class FilterExecutionBufferTest {
    private static class Registry extends SimpleMeterRegistry implements AutoCloseable { }
    @Test void ingestionDoesNotWriteDatabaseAndRejectsAnOverfullQueue() {
        var service=mock(FilterChainExecutionService.class);
        when(service.convertFromMap(anyMap())).thenReturn(new com.leoli.gateway.admin.model.FilterChainExecution());
        var writer=mock(DiagnosticBatchWriter.class);
        try(var meters=new Registry()){
            var buffer=new FilterExecutionBuffer(service,new ObjectMapper(),meters,writer);
            try{
                for(int i=0;i<256;i++)assertTrue(buffer.offer(List.of(Map.of("traceId","test"))));
                assertFalse(buffer.offer(List.of(Map.of("traceId","overflow"))));verifyNoInteractions(service);
                buffer.flush();verify(writer).writeExecutions(anyList());
                assertTrue(buffer.offer(List.of(Map.of("traceId","after-flush"))));
            }finally{buffer.close();}
            assertEquals(0,meters.get("gateway.admin.filter.executions.queued.bytes").gauge().value());
        }
    }
    @Test void failedPersistenceReleasesBudgetWithoutPerRecordRetry() {
        var service=mock(FilterChainExecutionService.class);
        when(service.convertFromMap(anyMap())).thenReturn(new com.leoli.gateway.admin.model.FilterChainExecution());
        var writer=mock(DiagnosticBatchWriter.class);when(writer.writeExecutions(anyList())).thenThrow(new IllegalStateException("offline"));
        try(var meters=new Registry()){
            var buffer=new FilterExecutionBuffer(service,new ObjectMapper(),meters,writer);
            try{
                assertTrue(buffer.offer(List.of(Map.of("traceId","test"))));buffer.flush();
                assertEquals(1,meters.counter("gateway.admin.filter.executions.failed.batches").count());
                assertEquals(0,meters.get("gateway.admin.filter.executions.queued.bytes").gauge().value());
                verify(writer,times(1)).writeExecutions(anyList());
            }finally{buffer.close();}
        }
    }
}
