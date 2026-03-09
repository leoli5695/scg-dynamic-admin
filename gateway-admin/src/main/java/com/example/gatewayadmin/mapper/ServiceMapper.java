package com.example.gatewayadmin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.gatewayadmin.model.ServiceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Service mapper interface.
 *
 * @author leoli
 */
@Mapper
public interface ServiceMapper extends BaseMapper<ServiceEntity> {
}
