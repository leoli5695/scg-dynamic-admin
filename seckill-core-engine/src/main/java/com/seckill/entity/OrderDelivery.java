package com.seckill.entity;

import lombok.Data;

@Data
public class OrderDelivery {
    private String orderNo;
    private String payload;
    private boolean esSent;
    private boolean rollbackSent;
    private int delayLevel;
    private int attempts;
}
