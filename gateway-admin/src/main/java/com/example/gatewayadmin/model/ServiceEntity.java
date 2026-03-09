package com.example.gatewayadmin.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Service entity for database persistence.
 *
 * @author leoli
 */
@Data
@TableName("services")
public class ServiceEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    
    private String name;
    
    private String serviceName;
    
    private String loadBalancer;
    
    private String healthCheckUrl;
    
    private String metadata;
    
    private Boolean enabled;
    
    private String description;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
}
