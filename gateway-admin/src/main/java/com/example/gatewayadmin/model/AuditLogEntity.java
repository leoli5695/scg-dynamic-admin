package com.example.gatewayadmin.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Audit log entity for tracking configuration changes.
 *
 * @author leoli
 */
@Data
@TableName("audit_logs")
public class AuditLogEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    
    private String operator;
    
    private String operationType;
    
    private String targetType;
    
    private String targetId;
    
    private String oldValue;
    
    private String newValue;
    
    private String ipAddress;
    
    private LocalDateTime createdAt;
}
