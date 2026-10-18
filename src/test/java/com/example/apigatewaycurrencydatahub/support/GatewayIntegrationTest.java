package com.example.apigatewaycurrencydatahub.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.MockWebServer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * Base de los tests de integracion del gateway. Levanta lo que la aplicacion
 * necesita de verdad y rellena las cuatro variables que application.yml pide
 * sin valor por defecto.
 *
 * <p>Por que hace falta esto: el unico test que habia, contextLoads, fallaba
 * desde siempre con {@code RedisUrlSyntaxException: Invalid Redis URL
 * '${REDIS_URL}'}, y nadie lo veia porque el Dockerfile construye con
 * {@code gradle bootJar}, que se salta los tests.
 *
 * <p><b>Redis es de verdad, en un contenedor.</b> Es lo unico que no se puede
 * simular sin dejar de probar nada: la lista negra de tokens del logout se
 * consulta con {@code redisTemplate.hasKey(token)}
 * (JwtAuthFilter.java:57), y con un mock se estaria comprobando que el mock
 * devuelve lo que le han dicho.
 *
 * <p><b>El contenedor se arranca en un bloque static y no con
 * {@code @Container}</b> a proposito. Con la anotacion, quien arranca el
 * contenedor es la extension de Testcontainers, y el orden entre esa
 * extension y la de Spring no esta garantizado: si Spring crea el contexto
 * primero, {@link DynamicPropertySource} lee un puerto que todavia no existe.
 * Arrancandolo aqui, ya esta en pie antes de que JUnit mire la clase. Lo para
 * Ryuk (el contenedor de limpieza de Testcontainers) al morir la JVM.
 *
 * <p>Mismo motivo para el MockWebServer: su puerto lo asigna el sistema al
 * arrancar, y hace falta antes de construir el contexto.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class GatewayIntegrationTest {

    /**
     * El secreto con el que se firman los tokens del test. Tiene que llegar a
     * 32 bytes: Keys.hmacShaKeyFor exige 256 bits para HMAC-SHA256 y con menos
     * lanza WeakKeyException, asi que un secreto corto haria fallar el test
     * por un motivo que no es el que se esta probando.
     *
     * <p>No es el de produccion y no sale de aqui. El de verdad vive en el
     * .env del VPS.
     */
    protected static final String SECRETO = "secreto-de-test-con-mas-de-32-bytes!!";

    /** La clave de firma, ya derivada del secreto igual que en el filtro. */
    private static final Key CLAVE = Keys.hmacShaKeyFor(SECRETO.getBytes(StandardCharsets.UTF_8));

    /**
     * El servicio al que el gateway enruta. No es un mock del gateway: es un
     * servidor HTTP de verdad en un puerto de verdad, asi que la peticion
     * atraviesa el filtro, el enrutado y el cliente HTTP. Es lo que permite
     * preguntarle despues QUE cabeceras recibio.
     */
    protected static final MockWebServer BACKEND = new MockWebServer();

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

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
        // Las tres rutas del gateway (weather, currency, auth) apuntan al mismo
        // servidor falso. Lo que se prueba no es a quien llama, es que llama.
        registro.add("API_SERVICE_URL", GatewayIntegrationTest::urlDelBackend);
        registro.add("AUTH_SERVICE_URL", GatewayIntegrationTest::urlDelBackend);
        registro.add("JWT_SECRET", () -> SECRETO);
    }

    private static String urlDelBackend() {
        return "http://" + BACKEND.getHostName() + ":" + BACKEND.getPort();
    }

    // -------------------------------------------------------------------
    // Fabricas de tokens
    // -------------------------------------------------------------------

    /** Un token que el filtro debe aceptar, con sujeto y roles. */
    protected static String tokenValido(String usuario, List<String> roles) {
        return Jwts.builder()
                .setSubject(usuario)
                .claim("roles", roles)
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(CLAVE)
                .compact();
    }

    protected static String tokenValido() {
        return tokenValido("jabier", List.of("ROLE_USER", "ROLE_ADMIN"));
    }

    /** Bien formado y caducado hace una hora. */
    protected static String tokenCaducado() {
        return Jwts.builder()
                .setSubject("jabier")
                .setExpiration(Date.from(Instant.now().minusSeconds(3600)))
                .signWith(CLAVE)
                .compact();
    }

    /** Bien formado, pero firmado con otra clave: es el caso del token falsificado. */
    protected static String tokenConOtraFirma() {
        Key otra = Keys.hmacShaKeyFor("otro-secreto-distinto-de-32-bytes!!!!".getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .setSubject("intruso")
                .setExpiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(otra)
                .compact();
    }
}
