package com.abegomez.research.llm;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configura timeouts en el cliente HTTP usado por Spring AI para hablar
 * con Ollama. Sin esto, si Ollama se atasca (p. ej. al cargar un modelo
 * de memoria), la llamada nunca termina.
 */
@Configuration
public class LlmRestClientConfig {

    private final LlmProperties properties;

    public LlmRestClientConfig(LlmProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestClientCustomizer llmTimeoutCustomizer() {
        int timeoutMs = properties.getTimeoutSeconds() * 1000;
        return new RestClientCustomizer() {
            @Override
            public void customize(RestClient.Builder builder) {
                builder.requestFactory(new SimpleClientHttpRequestFactory() {{
                    setConnectTimeout(timeoutMs);
                    setReadTimeout(timeoutMs);
                }});
            }
        };
    }
}
