package com.example.apigatewaycurrencydatahub.filter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 🔓 Dejar libre el auth
        if (path.startsWith("/auth") || path.contains("/v3/api-docs") || path.contains("/webjars") || path.contains("/swagger-ui")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");

        // ❌ Sin token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            // Le pasamos el segundo argumento con el texto del error
            return onError(exchange, "No se proporcionó un Token JWT válido en los Headers.");
        }

        String token = authHeader.substring(7);

        try {
            // ⚡️ VALIDACIÓN LOCAL EN 1 MILISEGUNDO ⚡️
            // Esto comprueba la firma matemática y si ha expirado. Si algo falla, salta al catch.
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(secret.getBytes()))
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            // Extraemos el usuario y los roles que inyectamos en el paso anterior
            String username = claims.getSubject();
            List<?> roles = claims.get("roles", List.class);
            String rolesString = roles != null ? String.join(",", (List<String>) roles) : "";

            // Creamos la copia de la petición con DOS headers nuevos
            ServerWebExchange modifiedExchange = exchange.mutate()
                    .request(r -> r.header("X-User-Name", username)
                            .header("X-User-Roles", rolesString)) // Ej: "ROLE_ADMIN,ROLE_USER"
                    .build();

            return chain.filter(modifiedExchange);

        } catch (Exception e) {
            // ❌ Token caducado, alterado (hackeo) o inválido
            // Le pasamos el segundo argumento con el texto del error
            return onError(exchange, "El Token JWT ha caducado o la firma es inválida.");
        }
    }

    // 🔥 NUEVO MÉTODO DE ERROR CON JSON BONITO 🔥
    private Mono<Void> onError(ServerWebExchange exchange, String errorMessage) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // Construimos un JSON a mano súper rápido
        String jsonString = String.format("{\"error\": \"Acceso Denegado\", \"message\": \"%s\"}", errorMessage);

        // En WebFlux (Reactivo) escribimos los datos en un "DataBuffer"
        byte[] bytes = jsonString.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);

        return response.writeWith(Mono.just(buffer));
    }
    // Extraje la respuesta de error a un método para no repetir código
    /*
    private Mono<Void> onError(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }*/

    @Override
    public int getOrder() {
        return -1;
    }
}