package com.seckill.service;

import com.seckill.mapper.OrderMapper;
import com.seckill.config.SeckillConfig;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OrderStateDatabaseTest {
    private JdbcDataSource source;
    private SqlSessionFactory factory;
    @BeforeEach void database() throws Exception {
        source=new JdbcDataSource();source.setURL("jdbc:h2:mem:state"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var connection=source.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE seckill_order(id BIGINT PRIMARY KEY, order_no VARCHAR(64), user_id BIGINT, status INT, total_amount DECIMAL(10,2), " +
                    "create_time TIMESTAMP, update_time TIMESTAMP, stock_release_state INT DEFAULT 0, stock_release_id VARCHAR(64),stock_release_next_at TIMESTAMP)");
            sql.execute("INSERT INTO seckill_order(id,order_no,user_id,status,total_amount,create_time) VALUES(1,'a',42,0,10,TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP))");
        }
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);config.addMapper(OrderMapper.class);factory=new SqlSessionFactoryBuilder().build(config);
    }
    @AfterEach void close() throws Exception {try(var c=source.getConnection();var s=c.createStatement()){s.execute("DROP ALL OBJECTS");}}
    @Test void cancellationAndReleaseIntentRollbackTogether() {
        try(var session=factory.openSession(false)) {
            assertEquals(1,session.getMapper(OrderMapper.class).cancelExpired(42L,"a",LocalDateTime.now()));session.rollback();
            var current=session.getMapper(OrderMapper.class).selectByUserAndOrderNo(42L,"a");
            assertEquals(0,current.getStatus());assertEquals(0,current.getStockReleaseState());assertNull(current.getStockReleaseId());
        }
    }
    @Test void concurrentCancellationHasOneWinnerAndOneDurableRelease() throws Exception {
        AtomicInteger winners=new AtomicInteger();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(16)) {
            var tasks=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<64;i++)tasks.add(pool.submit(()->{try(var session=factory.openSession(true)){
                start.await();winners.addAndGet(session.getMapper(OrderMapper.class).cancelExpired(42L,"a",LocalDateTime.now()));
            }catch(Exception e){throw new IllegalStateException(e);}}));
            start.countDown();for(var task:tasks)task.get(5,TimeUnit.SECONDS);
        }
        assertEquals(1,winners.get());try(var session=factory.openSession(true)) {
            var pending=session.getMapper(OrderMapper.class).selectPendingReleases();assertEquals(1,pending.size());
            assertEquals(2,pending.getFirst().getStatus());assertEquals("a",pending.getFirst().getStockReleaseId());
        }
    }
    @Test void staleCancellationCannotOverwritePaidOrRefundedState() throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()){s.execute("UPDATE seckill_order SET status=1");}
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(OrderMapper.class);assertEquals(0,mapper.cancelExpired(42L,"a",LocalDateTime.now()));
            assertEquals(0,mapper.refundPaid(99L,"a",BigDecimal.TEN));assertEquals(0,mapper.refundPaid(42L,"a",BigDecimal.ONE));
            assertEquals(1,mapper.refundPaid(42L,"a",BigDecimal.TEN));assertEquals(0,mapper.refundPaid(42L,"a",BigDecimal.TEN));
            assertEquals(0,mapper.cancelExpired(42L,"a",LocalDateTime.now()));
            assertEquals(3,mapper.selectByUserAndOrderNo(42L,"a").getStatus());
        }
    }
    @Test void futureDeadlineAndWrongOwnerCannotCreateReleaseIntent() {
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(OrderMapper.class);
            assertEquals(0,mapper.cancelExpired(99L,"a",LocalDateTime.now()));
            assertEquals(0,mapper.cancelExpired(42L,"a",LocalDateTime.now().minusHours(2)));
            assertTrue(mapper.selectPendingReleases().isEmpty());
        }
    }
    @Test void deferredBrokenReleaseDoesNotStarveOtherRowsAndConfirmationIsConditional() {
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(OrderMapper.class);mapper.cancelExpired(42L,"a",LocalDateTime.now());
            mapper.deferRelease(42L,"a");assertTrue(mapper.selectPendingReleases().isEmpty());
            assertEquals(0,mapper.confirmRelease(42L,"a","different"));assertEquals(1,mapper.confirmRelease(42L,"a","a"));
            assertEquals(0,mapper.confirmRelease(42L,"a","a"));
        }
    }
    @Test void archivalCannotDeleteAnOrderThatBecameAPendingReleaseAfterTheSnapshot() throws Exception {
        try(var c=source.getConnection();var s=c.createStatement()){s.execute("UPDATE seckill_order SET status=1");}
        try(var session=factory.openSession(true)) {
            var mapper=session.getMapper(OrderMapper.class);
            assertEquals(1,mapper.selectForArchive(LocalDateTime.now(),10).size());
            mapper.refundPaid(42L,"a",BigDecimal.TEN);
            assertEquals(0,mapper.deleteArchivedOrder(42L,"a",1));
            assertTrue(mapper.selectForArchive(LocalDateTime.now(),10).isEmpty());
            assertEquals(0,mapper.deleteArchivedOrder(42L,"a",3));
            mapper.confirmRelease(42L,"a","a");
            assertEquals(1,mapper.deleteArchivedOrder(42L,"a",3));
        }
    }
}
