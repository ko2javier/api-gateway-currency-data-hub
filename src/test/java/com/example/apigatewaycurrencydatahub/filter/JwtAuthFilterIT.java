package com.example.apigatewaycurrencydatahub.filter;

import com.example.apigatewaycurrencydatahub.support.GatewayIntegrationTest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtAuthFilter, que es la unica logica propia de este proyecto: todo lo demas
 * es configuracion de rutas. Es ademas la puerta de entrada de los tres
 * servicios del Hub, asi que un fallo aqui los deja a los tres abiertos o a
 * los tres cerrados.
 *
 * <p>Las peticiones salen por HTTP contra el puerto real de la aplicacion
 * (RANDOM_PORT en la clase base): un GlobalFilter de Spring Cloud Gateway solo
 * entra en juego cuando hay enrutado de verdad, asi que con un entorno
 * simulado estos tests pasarian sin ejecutar el filtro.
 */
class JwtAuthFilterIT extends GatewayIntegrationTest {

    @Autowired
    private WebTestClient cliente;

    /** La misma lista negra que consulta el filtro, para poder escribir en ella. */
    @Autowired
    private ReactiveStringRedisTemplate redis;

    // -------------------------------------------------------------------
    // Sin token: 401 y el cuerpo que escribe onError a mano
    // -------------------------------------------------------------------
    @Test
    void sin_cabecera_de_autorizacion_devuelve_401() {
        cliente.get().uri("/weather/today")
                .exchange()
                .expectStatus().isUnauthorized()
                // El cuerpo se construye con String.format en
                // JwtAuthFilter.onError, no con un @ExceptionHandler, asi que
                // esta forma exacta es el contrato que ve el Angular.
                .expectBody()
                .jsonPath("$.error").isEqualTo("Acceso Denegado")
                .jsonPath("$.message").isEqualTo("No se proporcionó un Token JWT válido.");
    }

    @Test
    void una_cabecera_que_no_empieza_por_bearer_devuelve_401() {
        // Basic en vez de Bearer. El filtro mira el prefijo, no solo que la
        // cabecera exista.
        cliente.get().uri("/weather/today")
                .header("Authorization", "Basic amFiaWVyOjEyMzQ=")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // -------------------------------------------------------------------
    // Token invalido: las dos formas de que el parseo falle
    // -------------------------------------------------------------------
    @Test
    void un_token_firmado_con_otra_clave_devuelve_401() {
        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + tokenConOtraFirma())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Token inválido o caducado.");
    }

    @Test
    void un_token_caducado_devuelve_401() {
        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + tokenCaducado())
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Token inválido o caducado.");
    }

    // -------------------------------------------------------------------
    // Token valido: pasa, y el gateway le cuenta al servicio quien llama
    // -------------------------------------------------------------------
    @Test
    void un_token_valido_pasa_y_anade_las_cabeceras_de_identidad() throws InterruptedException {
        BACKEND.enqueue(new MockResponse().setResponseCode(200).setBody("{\"temp\":21}"));

        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + tokenValido("jabier", List.of("ROLE_USER", "ROLE_ADMIN")))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.temp").isEqualTo(21);

        // Esto es lo que ningun mock del filtro podria decir: que lo que le
        // llega al servicio de detras lleva la identidad ya resuelta. Los tres
        // servicios del Hub confian en estas dos cabeceras, asi que si el
        // gateway dejara de ponerlas se quedarian sin saber quien llama.
        RecordedRequest recibida = BACKEND.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recibida).isNotNull();
        assertThat(recibida.getPath()).isEqualTo("/weather/today");
        assertThat(recibida.getHeader("X-User-Name")).isEqualTo("jabier");
        assertThat(recibida.getHeader("X-User-Roles")).isEqualTo("ROLE_USER,ROLE_ADMIN");
    }

    @Test
    void un_token_sin_roles_pasa_con_la_cabecera_de_roles_vacia() throws InterruptedException {
        // El filtro hace claims.get("roles", List.class) y, si no viene,
        // manda "" en vez de reventar (JwtAuthFilter.java:78). Se fija aqui
        // porque un NullPointerException en el filtro dejaria sin servicio a
        // los tres servicios de detras, no solo a este endpoint.
        BACKEND.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));

        cliente.get().uri("/currency/eur")
                .header("Authorization", "Bearer " + tokenValido("jabier", null))
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recibida = BACKEND.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recibida).isNotNull();
        assertThat(recibida.getHeader("X-User-Name")).isEqualTo("jabier");
        assertThat(recibida.getHeader("X-User-Roles")).isEmpty();
    }

    // -------------------------------------------------------------------
    // La lista negra del logout: el motivo de que Redis sea de verdad
    // -------------------------------------------------------------------
    @Test
    void un_token_en_la_lista_negra_devuelve_401_aunque_la_firma_sea_buena() {
        String token = tokenValido();
        // Lo que escribe el servicio de auth al cerrar sesion. La clave es el
        // token entero; el filtro solo pregunta si existe (hasKey).
        redis.opsForValue().set(token, "revocado").block();

        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Token revocado (Logout).");
    }

    @Test
    void un_token_que_no_esta_en_la_lista_negra_pasa() throws InterruptedException {
        // El control del test de arriba: sin esto, un 401 por cualquier otro
        // motivo (firma, caducidad, Redis mal conectado) lo daria por bueno.
        BACKEND.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
        String token = tokenValido();
        assertThat(redis.hasKey(token).block()).isFalse();

        cliente.get().uri("/weather/today")
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();

        assertThat(BACKEND.takeRequest(5, TimeUnit.SECONDS)).isNotNull();
    }

    // -------------------------------------------------------------------
    // Rutas publicas: las que el filtro deja pasar sin mirar el token
    // -------------------------------------------------------------------
    @Test
    void la_ruta_de_login_pasa_sin_token() throws InterruptedException {
        // Si /auth exigiera token no se podria iniciar sesion: para pedir el
        // token habria que tener uno.
        BACKEND.enqueue(new MockResponse().setResponseCode(200).setBody("{\"token\":\"x\"}"));

        cliente.post().uri("/auth/login")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recibida = BACKEND.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recibida).isNotNull();
        // Y no lleva identidad: no hay token del que sacarla.
        assertThat(recibida.getHeader("X-User-Name")).isNull();
    }

    @Test
    void el_actuator_pasa_sin_token() {
        // Lo llama Prometheus, que no tiene con que autenticarse. Si esto se
        // cerrara, el panel de Grafana se quedaria sin datos sin que nadie
        // toque Grafana.
        cliente.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void el_swagger_agregado_pasa_sin_token() {
        // Es la puerta publica de la documentacion de los tres servicios, y
        // ademas donde esta el formulario de login.
        cliente.get().uri("/swagger-ui.html")
                .exchange()
                // 2xx o 3xx: springdoc contesta con una redireccion a
                // /webjars/..., y lo que se prueba es que NO es 401.
                .expectStatus().value(codigo -> assertThat(codigo).isLessThan(400));
    }
}
