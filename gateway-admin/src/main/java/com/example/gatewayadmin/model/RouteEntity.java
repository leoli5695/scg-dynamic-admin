package com.example.gatewayadmin.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Route entity for database persistence.
 *
 * @author leoli
 */
@Data
@TableName("routes")
public class RouteEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    
    private String uri;
    
    private String predicates;
    
    private String filters;
    
    private String metadata;
    
    private Integer orderNum;
    
    private Boolean enabled;
    
    private String description;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
}
