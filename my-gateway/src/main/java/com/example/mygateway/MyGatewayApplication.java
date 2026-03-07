package com.example.mygateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class MyGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
