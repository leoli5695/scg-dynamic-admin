package com.leoli.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication()
@EnableScheduling
public class MyGatewayApplication {

    public static void main(String[] args) {
        System.setProperty("debug", "true");
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
