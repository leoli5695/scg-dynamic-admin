package com.leoli.gateway.admin;

import com.leoli.gateway.admin.model.RequestTrace;
import com.leoli.gateway.admin.repository.RequestTraceRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * Test data generator for Analytics testing.
 */
@SpringBootTest
public class GenerateTestData {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(GenerateTestData.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Bean
    public CommandLineRunner run(RequestTraceRepository repository) {
        return args -> {
            System.out.println("Generating test data...");
            
            Random random = new Random();
            String[] routes = {"route-1", "route-2", "route-3", "api-users", "api-orders", "api-products"};
            String[] methods = {"GET", "POST", "PUT", "DELETE"};
            String[] clients = {"192.168.1.100", "192.168.1.101", "192.168.1.102", "10.0.0.50", "10.0.0.51"};
            String[] services = {"user-service:8080", "order-service:8080", "product-service:8080"};
            Integer[] statusCodes = {200, 200, 200, 200, 201, 204, 400, 404, 500, 503};
            String[] uris = {
                "/api/users", "/api/users/1", "/api/orders", "/api/orders/100",
                "/api/products", "/api/products/50", "/api/health", "/api/status"
            };
            
            // Generate 100 test traces in the last 24 hours
            for (int i = 0; i < 100; i++) {
                RequestTrace trace = new RequestTrace();
                trace.setTraceId("test-trace-" + System.currentTimeMillis() + "-" + i);
                trace.setRouteId(routes[random.nextInt(routes.length)]);
                trace.setMethod(methods[random.nextInt(methods.length)]);
                trace.setUri(uris[random.nextInt(uris.length)]);
                trace.setPath(uris[random.nextInt(uris.length)]);
                trace.setQueryString("");
                trace.setRequestHeaders("{}");
                trace.setRequestBody("{}");
                trace.setStatusCode(statusCodes[random.nextInt(statusCodes.length)]);
                trace.setResponseHeaders("{}");
                trace.setResponseBody("{\"status\":\"ok\"}");
                trace.setTargetInstance(services[random.nextInt(services.length)]);
                trace.setLatencyMs((long) (random.nextInt(500) + 50)); // 50-550ms
                trace.setClientIp(clients[random.nextInt(clients.length)]);
                trace.setUserAgent("TestClient/1.0");
                trace.setTraceType("ALL");
                trace.setReplayable(true);
                trace.setReplayCount(0);
                
                // Random time in the last 24 hours
                long hoursAgo = random.nextInt(24);
                long minutesAgo = random.nextInt(60);
                trace.setTraceTime(LocalDateTime.now().minusHours(hoursAgo).minusMinutes(minutesAgo));
                
                repository.save(trace);
            }
            
            System.out.println("Generated 100 test traces!");
            
            // Show summary
            long total = repository.count();
            System.out.println("Total traces in database: " + total);
        };
    }
}
