package com.leoli.gateway;

import com.leoli.gateway.config.HeartbeatProperties;
import com.leoli.gateway.config.TrustedProxyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        RedisAutoConfiguration.class,
        RedisRepositoriesAutoConfiguration.class
})
@EnableScheduling
@EnableConfigurationProperties({TrustedProxyProperties.class, HeartbeatProperties.class})
public class MyGatewayApplication {

    public static void main(String[] args) {
        System.setProperty("debug", "true");
        SpringApplication.run(MyGatewayApplication.class, args);
    }
}
