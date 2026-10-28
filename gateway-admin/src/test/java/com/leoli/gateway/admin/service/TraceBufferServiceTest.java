package com.leoli.gateway.admin.service;
import com.leoli.gateway.admin.model.RequestTrace;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class TraceBufferServiceTest {
    @Test void boundsTotalQueuedBodyMemoryBeforeCountLimitIsReached() {
        var repo=mock(DiagnosticBatchWriter.class);var buffer=new TraceBufferService(repo);
        try{
            var trace=new RequestTrace();trace.setRequestBody("x".repeat(200_000));
            int accepted=0;for(int i=0;i<100;i++)if(buffer.offer(trace))accepted++;
            assertTrue(accepted<50);assertTrue(buffer.getStats().get("queuedBytes")<=16*1024*1024);
            verifyNoInteractions(repo);
        }finally{buffer.shutdown();}
        assertEquals(0,buffer.getStats().get("queuedBytes"));
    }
    @Test void blockedPersistenceDoesNotBlockScheduledOrHttpThreads() throws Exception {
        var repo=mock(DiagnosticBatchWriter.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(repo.writeTraces(anyList())).thenAnswer(call->{entered.countDown();release.await(2,TimeUnit.SECONDS);return ((java.util.List<?>)call.getArgument(0)).size();});
        var buffer=new TraceBufferService(repo);
        try{
            for(int i=0;i<100;i++)buffer.offer(new RequestTrace());
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            long start=System.nanoTime();for(int i=0;i<200;i++){buffer.offer(new RequestTrace());buffer.scheduledFlush();}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<500);
            verify(repo,times(1)).writeTraces(anyList());
        }finally{release.countDown();buffer.shutdown();}
    }
}
