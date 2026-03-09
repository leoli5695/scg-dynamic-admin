package com.example.gatewayadmin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.gatewayadmin.model.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Audit log mapper interface.
 *
 * @author leoli
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {
}
