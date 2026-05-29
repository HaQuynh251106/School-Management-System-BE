package com.sse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point của API Gateway.
 *
 * TODO (P1, sprint S2): thêm Spring Cloud Gateway dependency + RouteLocator
 * để route /auth/** → identity, /classes/** → academic, v.v.
 */
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
