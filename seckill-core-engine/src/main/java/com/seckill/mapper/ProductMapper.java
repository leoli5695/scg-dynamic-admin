package com.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.seckill.entity.SeckillProduct;
import org.apache.ibatis.annotations.Mapper;

/**
 * ============================================================================
 * 秒杀商品 Mapper
 * ============================================================================
 * 
 * 存储位置: ds_0（不分片）
 */
@Mapper
public interface ProductMapper extends BaseMapper<SeckillProduct> {
}