package com.seckill.service;

import com.seckill.entity.OrderDelivery;
import com.seckill.mapper.OrderDeliveryMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Executes mapper SQL against a real transactional JDBC engine, in MySQL compatibility mode. */
class OrderDeliveryDatabaseTest {
    @Test void durableIdempotentEventsRetainUnsentPartsAndRollbackFailedEnqueue() throws Exception {
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:delivery;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var connection=source.getConnection();var statement=connection.createStatement()) {
            statement.execute("CREATE SCHEMA seckill_db_0");
            try(var input=getClass().getResourceAsStream("/sql/order-delivery-migration.sql")) {
                statement.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            }
        }
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),source));
        config.setMapUnderscoreToCamelCase(true);config.addMapper(OrderDeliveryMapper.class);
        var factory=new SqlSessionFactoryBuilder().build(config);
        try(var session=factory.openSession(false)) {
            session.getConnection().setSchema("SECKILL_DB_0");
            var mapper=session.getMapper(OrderDeliveryMapper.class);
            var event=new OrderDelivery();event.setOrderNo("a");event.setPayload("{\"orderNo\":\"a\"}");event.setDelayLevel(17);
            mapper.enqueue(event);session.rollback();assertTrue(mapper.pending().isEmpty());
            mapper.enqueue(event);session.commit();
            mapper.markEs("a");session.commit();
            event.setPayload("different-redelivery");mapper.enqueue(event);session.commit();
            var pending=mapper.pending();assertEquals(1,pending.size());assertTrue(pending.getFirst().isEsSent());
            assertFalse(pending.getFirst().isRollbackSent());assertEquals(17,pending.getFirst().getDelayLevel());
            assertEquals("{\"orderNo\":\"a\"}",pending.getFirst().getPayload());
            mapper.reschedule("a",60);session.commit();assertTrue(mapper.pending().isEmpty());
            mapper.markRollback("a");mapper.reschedule("a",1);session.commit();
            try(var query=session.getConnection().createStatement();var rows=query.executeQuery("SELECT completed FROM order_delivery WHERE order_no='a'")) {
                assertTrue(rows.next());assertEquals(1,rows.getInt(1));
            }
        }
    }
}
