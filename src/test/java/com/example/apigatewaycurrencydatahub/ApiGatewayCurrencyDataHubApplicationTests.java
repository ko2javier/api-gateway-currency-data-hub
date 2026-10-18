package com.example.apigatewaycurrencydatahub;

import com.example.apigatewaycurrencydatahub.support.GatewayIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteLocator;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El test que venia con el proyecto. Estaba roto desde el primer dia:
 *
 * <pre>
 * RedisUrlSyntaxException: Invalid Redis URL '${REDIS_URL}'
 * </pre>
 *
 * <p>application.yml pide REDIS_URL, API_SERVICE_URL, AUTH_SERVICE_URL y
 * JWT_SECRET sin valor por defecto, asi que el contexto no podia arrancar
 * fuera del servidor. Nadie lo noto porque el Dockerfile construye con
 * {@code gradle bootJar}, que no ejecuta tests, y el workflow solo hace
 * {@code docker compose build} por SSH. Lo unico que cambia es de donde salen
 * esas cuatro variables: ahora de {@link GatewayIntegrationTest}.
 */
class ApiGatewayCurrencyDataHubApplicationTests extends GatewayIntegrationTest {

    @Autowired
    private RouteLocator rutas;

    @Test
    void contextLoads() {
    }

    @Test
    void estan_dadas_de_alta_las_nueve_rutas() {
        // No comprueba a donde apuntan (en el test van todas al mismo sitio),
        // sino que el YAML se leyo entero. Una ruta mal indentada no rompe el
        // arranque: desaparece en silencio, y el servicio al que lleva
        // empieza a dar 404 sin que nada falle.
        List<String> ids = Objects.requireNonNull(
                rutas.getRoutes().map(ruta -> ruta.getId()).collectList().block());

        assertThat(ids).containsExactlyInAnyOrder(
                "weather-service",
                "currency-service",
                "auth-service",
                "kafka-events-swagger",
                "kafka-events-api-docs",
                "events-service",
                "openapi-apis",
                "openapi-auth",
                "openapi-events");
    }
}
