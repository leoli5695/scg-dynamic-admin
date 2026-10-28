package com.seckill.mapper;

import com.seckill.entity.OrderDelivery;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Durable events; insert returns only after the DB statement has committed. */
@Mapper
public interface OrderDeliveryMapper {
    @Insert("INSERT INTO order_delivery(order_no,payload,delay_level) VALUES(#{orderNo},#{payload},#{delayLevel}) ON DUPLICATE KEY UPDATE order_no=VALUES(order_no)")
    int enqueue(OrderDelivery delivery);
    @Select("SELECT order_no,payload,delay_level,es_sent,rollback_sent,attempts FROM order_delivery WHERE completed=0 AND next_attempt_at <= CURRENT_TIMESTAMP ORDER BY next_attempt_at,order_no LIMIT 50")
    List<OrderDelivery> pending();
    @Update("UPDATE order_delivery SET es_sent=1 WHERE order_no=#{orderNo}")
    int markEs(String orderNo);
    @Update("UPDATE order_delivery SET rollback_sent=1 WHERE order_no=#{orderNo}")
    int markRollback(String orderNo);
    @Update("UPDATE order_delivery SET completed=CASE WHEN es_sent=1 AND rollback_sent=1 THEN 1 ELSE 0 END, attempts=attempts+1, next_attempt_at=TIMESTAMPADD(SECOND,#{delay},CURRENT_TIMESTAMP) WHERE order_no=#{orderNo}")
    int reschedule(@Param("orderNo") String orderNo, @Param("delay") int delay);
    @Delete("DELETE FROM order_delivery WHERE completed=1 AND created_at < TIMESTAMPADD(DAY,-7,CURRENT_TIMESTAMP) LIMIT 500")
    int cleanup();
}
