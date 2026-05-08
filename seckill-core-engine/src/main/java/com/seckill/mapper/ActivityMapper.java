package com.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ============================================================================
 * 秒杀活动 Mapper
 * ============================================================================
 * 
 * 存储位置: ds_0（不分片）
 */
@Mapper
public interface ActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 根据状态查询活动
     */
    @Select("SELECT * FROM seckill_activity WHERE status = #{status}")
    List<SeckillActivity> selectByStatus(@Param("status") int status);

    /**
     * 根据时间范围查询活动（用于预热）
     */
    @Select("SELECT * FROM seckill_activity WHERE start_time BETWEEN #{start} AND #{end} AND status = 0")
    List<SeckillActivity> selectByStartTimeRange(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 更新预热状态
     */
    @Update("UPDATE seckill_activity SET warmed_up = #{warmedUp} WHERE id = #{seckillId}")
    void updateWarmupStatus(@Param("seckillId") Long seckillId, @Param("warmedUp") boolean warmedUp);

    /**
     * 悲观锁查询（用于降级模式的库存扣减）
     * 
     * 使用 SELECT ... FOR UPDATE 获取行锁
     * 注意：需要在事务中使用
     */
    @Select("SELECT * FROM seckill_activity WHERE id = #{seckillId} FOR UPDATE")
    SeckillActivity selectByIdForUpdate(@Param("seckillId") Long seckillId);
}