package com.example.apigatewaycurrencydatahub.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CARACTERIZACION — fija lo que hace HOY la aplicacion, no lo que deberia.
 *
 * <p>El filtro trata un fallo de Redis como "el token no esta en la lista
 * negra" (JwtAuthFilter.java:57-61):
 *
 * <pre>
 * return redisTemplate.hasKey(token)
 *         .onErrorResume(redisError -&gt; Mono.just(false))
 * </pre>
 *
 * <p>Es decir: <b>con Redis caido, todos los tokens revocados vuelven a
 * valer</b> hasta que caduquen. Un logout deja de significar nada. La
 * alternativa seria cerrar la puerta (401 mientras Redis no responda), que
 * cambia un agujero de seguridad por una caida total del Hub — y esa decision
 * no es mia. <b>Queda apuntada como deuda; aqui solo se fija y se mide.</b>
 *
 * <p>Esto es lo que ningun mock puede probar: hay que tener un Redis de
 * verdad, con el token escrito dentro, y <b>apagarlo</b> a mitad. Por eso esta
 * clase tiene su propio contenedor en vez de usar el compartido de
 * GatewayIntegrationTest: lo para, y pararle el Redis a los demas tests los
 * dejaria colgando de un contenedor muerto.
 *
 * <p>Los dos tests van en orden ({@code @Order}) porque son un antes y un
 * despues del mismo escenario: no son independientes a proposito.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RedisCaidoIT {

    private static final String SECRETO = "secreto-de-test-con-mas-de-32-bytes!!";
    private static final Key CLAVE = Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8));

    private static final MockWebServer BACKEND = new MockWebServer();

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    /** El token del escenario: se revoca con Redis vivo y se reusa con Redis muerto. */
    private static final String TOKEN = Jwts.builder()
            .setSubject("jabier")
            .claim("roles", List.of("ROLE_USER"))
            .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(CLAVE)
            .compact();

    static {
        REDIS.start();
        try {
            BACKEND.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("REDIS_URL", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getFirstMappedPort());
        registro.add("API_SERVICE_URL", () -> "http://" + BACKEND.getHostName() + ":" + BACKEND.getPort());
        registro.add("AUTH_SERVICE_URL", () -> "http://" + BACKEND.getHostName() + ":" + BACKEND.getPort());
        registro.add("JWT_SECRET", () -> SECRETO);
        // Sin esto el test tarda un minuto: el timeout de Lettuce por defecto
        // es 60 s, y con el contenedor parado la peticion se queda esperando
        // antes de que onErrorResume la recoja.
        registro.add("spring.data.redis.timeout", () -> "2s");
    }

    @Autowired
    private WebTestClient cliente;

    @Autowired
    private ReactiveStringRedisTemplate redis;

    @Test
    @Order(1)
    void con_redis_vivo_el_token_revocado_se_rechaza() {
        redis.opsForValue().set(TOKEN, "revocado").block();

        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.message").isEqualTo("Token revocado (Logout).");
    }

    @Test
    @Order(2)
    void con_redis_caido_el_mismo_token_revocado_PASA() throws InterruptedException {
        BACKEND.enqueue(new MockResponse().setResponseCode(200).setBody("{\"temp\":21}"));

        REDIS.stop();

        // El mismo token que acaba de dar 401 ahora atraviesa el gateway.
        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + TOKEN)
                .exchange()
                .expectStatus().isOk();

        // Y llega al servicio de detras con la identidad del usuario que habia
        // cerrado sesion.
        var recibida = BACKEND.takeRequest(10, TimeUnit.SECONDS);
        assertThat(recibida).isNotNull();
        assertThat(recibida.getHeader("X-User-Name")).isEqualTo("jabier");
    }
}
