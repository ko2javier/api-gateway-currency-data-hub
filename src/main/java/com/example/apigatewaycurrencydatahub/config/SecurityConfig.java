package com.example.apigatewaycurrencydatahub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                // 1. Deshabilitar CSRF para APIs
                .csrf(csrf -> csrf.disable())
                // 2. Deshabilitar el formulario de Login por defecto (el que pide la password del log)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // 3. Configurar permisos
                .authorizeExchange(exchanges -> exchanges
                        // Rutas públicas de Swagger y Auth
                        .pathMatchers("/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/webjars/**", "/swagger-ui.html").permitAll()
                        // Permitimos todo lo demás para que lo maneje tu JwtAuthFilter
                        .anyExchange().permitAll()
                )
                .build();
    }
}