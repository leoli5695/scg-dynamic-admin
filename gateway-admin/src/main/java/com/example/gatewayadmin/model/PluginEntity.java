package com.example.gatewayadmin.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Plugin entity for database persistence.
 *
 * @author leoli
 */
@Data
@TableName("plugins")
public class PluginEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    
    private String pluginType;
    
    private String routeId;
    
    private String config;
    
    private Boolean enabled;
    
    private Integer priority;
    
    private String description;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
}
