package com.example.gatewayadmin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.gatewayadmin.model.PluginEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Plugin mapper interface.
 *
 * @author leoli
 */
@Mapper
public interface PluginMapper extends BaseMapper<PluginEntity> {
}
