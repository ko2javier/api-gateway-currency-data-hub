package com.example.apigatewaycurrencydatahub.filter;


import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final WebClient webClient = WebClient.create("http://localhost:4000");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        // 🔓 dejar libre el auth
        if (path.startsWith("/auth")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        // ❌ no token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }

        // 🔥 Modifica esta parte de tu JwtAuthFilter
        return webClient.post()
                .uri("/auth/validate")
                .header("Authorization", authHeader)
                .retrieve()
                .bodyToMono(String.class) // ⬅️ Cambiamos a String para recibir el nombre del usuario
                .flatMap(username -> {
                    // Creamos una "copia" de la petición pero con el nuevo header
                    ServerWebExchange modifiedExchange = exchange.mutate()
                            .request(r -> r.header("X-User-Name", username))
                            .build();

                    return chain.filter(modifiedExchange);
                })
                .onErrorResume(e -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                });
    }

    @Override
    public int getOrder() {
        return -1; // 🔥 prioridad alta
    }
}