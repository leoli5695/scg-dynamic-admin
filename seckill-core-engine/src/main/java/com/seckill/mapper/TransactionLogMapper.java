package com.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seckill.entity.TransactionLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * ============================================================================
 * 事务日志 Mapper
 * ============================================================================
 * 
 * 存储位置: ds_0（不分片）
 * 
 * 用途:
 * 1. 记录RocketMQ事务消息状态
 * 2. 用于事务回查
 */
@Mapper
public interface TransactionLogMapper extends BaseMapper<TransactionLog> {

    /**
     * 根据事务ID查询
     */
    @Select("SELECT * FROM transaction_log WHERE transaction_id = #{transactionId}")
    TransactionLog selectByTransactionId(@Param("transactionId") String transactionId);

    /**
     * 根据用户和活动查询
     */
    @Select("SELECT * FROM transaction_log WHERE user_id = #{userId} AND seckill_id = #{seckillId}")
    TransactionLog selectByUserAndSeckill(@Param("userId") Long userId, @Param("seckillId") Long seckillId);

    /**
     * 查询超时未处理的事务
     */
    @Select("SELECT * FROM transaction_log WHERE status = #{status} AND create_time < #{expireTime} ORDER BY create_time ASC LIMIT 1000")
    java.util.List<TransactionLog> selectTimeoutTransactions(@Param("status") int status, @Param("expireTime") java.time.LocalDateTime expireTime);

    /**
     * 统计指定秒杀活动的成功事务数量（用于对账）
     * 
     * @param seckillId 秒杀活动ID
     * @return 成功事务数量（即有效订单数）
     */
    @Select("SELECT COUNT(*) FROM transaction_log WHERE seckill_id = #{seckillId} AND status = 1")
    int countSuccessTransactions(@Param("seckillId") Long seckillId);

    /**
     * 统计指定秒杀活动的处理中事务数量
     */
    @Select("SELECT COUNT(*) FROM transaction_log WHERE seckill_id = #{seckillId} AND status = 0")
    int countProcessingTransactions(@Param("seckillId") Long seckillId);

    /**
     * 统计指定秒杀活动的失败事务数量
     */
    @Select("SELECT COUNT(*) FROM transaction_log WHERE seckill_id = #{seckillId} AND status = 2")
    int countFailedTransactions(@Param("seckillId") Long seckillId);

    /**
     * 查询需要清理的事务日志（已完成状态，超过保留天数）
     *
     * 状态: 1=成功, 2=失败
     */
    @Select("SELECT * FROM transaction_log WHERE status IN (1, 2) AND create_time < #{threshold} ORDER BY create_time ASC LIMIT #{limit}")
    java.util.List<TransactionLog> selectForClean(@Param("threshold") java.time.LocalDateTime threshold, @Param("limit") int limit);
}