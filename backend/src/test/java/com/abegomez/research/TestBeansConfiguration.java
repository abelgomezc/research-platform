package com.abegomez.research;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaApi;

/**
 * Sustituye el acceso a Ollama por dobles durante los tests de contexto, para
 * que el arranque de Spring no dependa de tener un servidor de modelos arriba.
 */
@TestConfiguration
public class TestBeansConfiguration {

    @Bean
    @Primary
    public OllamaApi testOllamaApi() {
        return OllamaApi.builder().baseUrl("http://localhost:11434").build();
    }

    @Bean
    @Primary
    public ChatModel testChatModel() {
        return prompt -> {
            throw new UnsupportedOperationException(
                    "Los tests no deben llamar a un modelo real. Usa un doble de LlmGateway.");
        };
    }
}
