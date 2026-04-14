package com.example.apigatewaycurrencydatahub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveUserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = {
        ReactiveSecurityAutoConfiguration.class,
        ReactiveUserDetailsServiceAutoConfiguration.class
})
public class ApiGatewayCurrencyDataHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayCurrencyDataHubApplication.class, args);
    }
}