package com.leoli.gateway.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class TraceReportDispatcherTest {
    private static class Registry extends SimpleMeterRegistry implements AutoCloseable { }
    @Test void boundsQueueCountAndBytesWithoutSendingOnTheCallerThread() {
        try (var meters = new Registry()) {
            var dispatcher = new TraceReportDispatcher(new ObjectMapper(), meters);
            try {
                int accepted=0;
                for(int i=0;i<300;i++) if(dispatcher.submit(Map.of("traceId","test"))) accepted++;
                assertEquals(256,accepted);
                assertEquals(44,meters.counter("gateway.trace.export.dropped").count());
                assertFalse(dispatcher.submit(Map.of("requestBody","x".repeat(300*1024))));
                assertTrue(meters.get("gateway.trace.export.queued.bytes").gauge().value()<=4*1024*1024);
            } finally { dispatcher.close(); }
            assertEquals(0,meters.get("gateway.trace.export.queued.bytes").gauge().value());
        }
    }

    @Test void batchesTracesAndExportsFilterExecutionsOnTheIsolatedClient() throws Exception {
        var bodies=new CopyOnWriteArrayList<String>(); var paths=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api",exchange->{
            paths.add(exchange.getRequestURI().getPath());
            assertEquals("test-internal",exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202,-1);exchange.close();
        });server.start();
        try(var meters=new Registry()){
            var dispatcher=new TraceReportDispatcher(new ObjectMapper(),meters);
            try{
                ReflectionTestUtils.setField(dispatcher,"adminUrl","http://127.0.0.1:"+server.getAddress().getPort());
                ReflectionTestUtils.setField(dispatcher,"internalToken","test-internal");
                for(int i=0;i<3;i++)assertTrue(dispatcher.submit(Map.of("traceId","trace-"+i,"latencyMs",10,
                        "instanceId","test","filterExecutions",List.of(Map.of("filterName","Auth","order",1,"durationMs",5,"success",true)))));
                assertTrue(bodies.isEmpty());dispatcher.flush();
                assertEquals(List.of("/api/traces/internal/batch","/api/filter-executions/internal"),paths);
                var mapper=new ObjectMapper();assertEquals(3,mapper.readTree(bodies.get(0)).size());
                assertEquals(50.0,mapper.readTree(bodies.get(1)).get(0).get("timePercentage").asDouble());
                assertEquals(3,meters.counter("gateway.trace.export.sent").count());
                assertEquals(0,meters.get("gateway.trace.export.queued.bytes").gauge().value());
            }finally{dispatcher.close();}
        }finally{server.stop(0);}
    }

    @Test void failedAdminDropsTheBatchAndReleasesMemory() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{e.sendResponseHeaders(503,-1);e.close();});server.start();
        try(var meters=new Registry()){
            var dispatcher=new TraceReportDispatcher(new ObjectMapper(),meters);
            try{
                ReflectionTestUtils.setField(dispatcher,"adminUrl","http://127.0.0.1:"+server.getAddress().getPort());
                dispatcher.submit(Map.of("traceId","failure"));dispatcher.flush();
                assertEquals(1,meters.counter("gateway.trace.export.failed").count());
                assertEquals(0,meters.get("gateway.trace.export.queued.bytes").gauge().value());
            }finally{dispatcher.close();}
        }finally{server.stop(0);}
    }
}
