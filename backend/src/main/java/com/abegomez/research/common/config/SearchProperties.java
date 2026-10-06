package com.abegomez.research.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import org.springframework.stereotype.Component;

/**
 * Configuracion del metabuscador local usado por la tool de busqueda web.
 */
@Component
@ConfigurationProperties(prefix = "app.search")
public class SearchProperties {

    /**
     * URL base de SearXNG.
     *
     * <p>El default coincide con el puerto publicado en {@code docker-compose.yml}.
     * Antes pointed to 8081, que es el backend: si la propiedad se olvidara, el
     * agente se habria haciendo peticiones a si mismo en bucle.
     */
    private String baseUrl = "http://localhost:8090";

    /**
     * Tiempo maximo de espera por consulta.
     */
    private Duration timeout = Duration.ofSeconds(15);

    /**
     * Numero maximo de resultados devueltos por consulta.
     */
    private int maxResults = 8;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }
}
