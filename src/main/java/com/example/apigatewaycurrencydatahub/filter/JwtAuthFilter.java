package com.example.apigatewaycurrencydatahub.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    @Value("${jwt.secret}")
    private String secret;

    private final ReactiveStringRedisTemplate redisTemplate;

    public JwtAuthFilter(ReactiveStringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 🔓 1. Rutas públicas
        if (path.startsWith("/auth") || path.contains("/v3/api-docs") ||
                path.contains("/webjars") || path.contains("/swagger-ui")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return onError(exchange, "No se proporcionó un Token JWT válido.");
        }

        String token = authHeader.substring(7);

        // 🛡️ 2. Verificación en Redis y Validación de Firma
        return redisTemplate.hasKey(token)
                .onErrorResume(redisError -> {
                    System.err.println("⚠️ Redis no disponible, saltando blacklist check: " + redisError.getMessage());
                    return reactor.core.publisher.Mono.just(false);
                })
                .flatMap(isBlacklisted -> {
                    if (isBlacklisted) {
                        return onError(exchange, "Token revocado (Logout).");
                    }

                    try {
                        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

                        Claims claims = Jwts.parserBuilder()
                                .setSigningKey(Keys.hmacShaKeyFor(keyBytes))
                                .build()
                                .parseClaimsJws(token)
                                .getBody();

                        String username = claims.getSubject();
                        List<?> roles = claims.get("roles", List.class);
                        String rolesString = roles != null ? String.join(",", (List<String>) roles) : "";

                        ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                            @Override
                            public HttpHeaders getHeaders() {
                                HttpHeaders headers = new HttpHeaders();
                                headers.putAll(super.getHeaders());
                                headers.set("X-User-Name", username);
                                headers.set("X-User-Roles", rolesString);
                                return headers;
                            }
                        };

                        return chain.filter(exchange.mutate().request(mutatedRequest).build());

                    } catch (Exception e) {
                        System.err.println("❌ ERROR VALIDANDO JWT [" + e.getClass().getSimpleName() + "]: " + e.getMessage());
                        e.printStackTrace();
                        return onError(exchange, "Token inválido o caducado.");
                    }
                });
    }

    private Mono<Void> onError(ServerWebExchange exchange, String errorMessage) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String jsonString = String.format("{\"error\": \"Acceso Denegado\", \"message\": \"%s\"}", errorMessage);
        byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -1;
    }
}