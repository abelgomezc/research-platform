package com.abegomez.research.common.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.abegomez.research.common.config.SearchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator de SearXNG. Un metabuscador caido no tumba la plataforma:
 * la tool de busqueda web reporta el fallo y el agente continua con otras fuentes.
 */
@Component("searxng")
public class SearxngHealthIndicator implements HealthIndicator {

    private final SearchProperties properties;
    private final HttpClient httpClient;

    @Autowired
    public SearxngHealthIndicator(SearchProperties properties) {
        this(properties, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    SearxngHealthIndicator(SearchProperties properties, HttpClient httpClient) {
        this.properties = properties;
        this.httpClient = httpClient;
    }

    @Override
    public Health health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getBaseUrl() + "/healthz"))
                    .timeout(properties.getTimeout())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return Health.up()
                        .withDetail("url", properties.getBaseUrl())
                        .build();
            }
            return Health.down()
                    .withDetail("url", properties.getBaseUrl())
                    .withDetail("status", response.statusCode())
                    .build();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Health.down(ex).withDetail("motivo", "La comprobacion fue interrumpida").build();
        }
        catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("url", properties.getBaseUrl())
                    .withDetail("motivo", "SearXNG no responde")
                    .build();
        }
    }
}
