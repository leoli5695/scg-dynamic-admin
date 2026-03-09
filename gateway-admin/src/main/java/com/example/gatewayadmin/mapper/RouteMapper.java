package com.example.gatewayadmin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.gatewayadmin.model.RouteEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Route mapper interface.
 *
 * @author leoli
 */
@Mapper
public interface RouteMapper extends BaseMapper<RouteEntity> {
}
