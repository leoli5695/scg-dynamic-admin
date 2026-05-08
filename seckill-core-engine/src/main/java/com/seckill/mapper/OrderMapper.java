package com.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 订单 Mapper
 * ============================================================================
 *
 * 分片规则:
 * - 分库: user_id % 8
 * - 分表: user_id % 16
 *
 * 注意:
 * - 只能按user_id维度查询，跨库查询需用ES
 * - ShardingSphere自动路由
 */
@Mapper
public interface OrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 根据订单号查询
     *
     * 注意: 订单号有唯一索引，可精确查询
     * ShardingSphere会自动路由（通过广播表或唯一索引路由）
     */
    @Select("SELECT * FROM seckill_order WHERE order_no = #{orderNo}")
    SeckillOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 根据用户和活动查询订单
     *
     * 用于检查用户是否已购买
     */
    @Select("SELECT * FROM seckill_order WHERE user_id = #{userId} AND seckill_id = #{seckillId}")
    SeckillOrder selectByUserAndSeckill(@Param("userId") Long userId, @Param("seckillId") Long seckillId);

    /**
     * 查询超时未支付订单（用于补偿服务）
     *
     * 注意: 这是全路由查询，会扫描所有分片表，仅在补偿等低频场景使用
     */
    @Select("SELECT * FROM seckill_order WHERE status = 0 AND create_time <= #{unpaidTime} ORDER BY create_time ASC LIMIT 1000")
    List<SeckillOrder> selectUnpaidOrders(@Param("unpaidTime") LocalDateTime unpaidTime);

    /**
     * 更新订单状态
     */
    @Update("UPDATE seckill_order SET status = #{status}, update_time = #{updateTime} WHERE id = #{id}")
    int updateOrderStatus(@Param("id") Long id, @Param("status") Integer status, @Param("updateTime") LocalDateTime updateTime);

    /**
     * 查询需要归档的订单（已完成状态，超过保留天数）
     *
     * 注意: 这是全路由查询，仅在归档等低频场景使用
     * 状态: 1=已支付, 2=已取消, 3=已退款
     */
    @Select("SELECT * FROM seckill_order WHERE status IN (1, 2, 3) AND create_time < #{threshold} ORDER BY create_time ASC LIMIT #{limit}")
    List<SeckillOrder> selectForArchive(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /**
     * ============================================================================
     * 【攒批落库】批量插入订单
     * ============================================================================
     *
     * 性能优化：
     * - 单条 insert: 每次 ~5ms，TPS ~2000
     * - 批量 insert: 每次 ~50ms（50条），TPS ~10000+
     *
     * 注意：
     * - ShardingSphere 会根据 user_id 自动路由到对应库表
     * - 批量插入时，不同 user_id 的订单会路由到不同库
     * - MyBatis-Plus 的 insertBatch 已支持分片路由
     */
    int insertBatch(@Param("list") List<SeckillOrder> list);
}