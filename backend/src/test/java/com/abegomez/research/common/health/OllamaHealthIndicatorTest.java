package com.abegomez.research.common.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.actuate.health.Status;

class OllamaHealthIndicatorTest {

    private static OllamaApi.Model model(String name) {
        return new OllamaApi.Model(name, name, java.time.Instant.now(), 1L, "digest", null);
    }

    @Test
    @DisplayName("Sano cuando los modelos por rol estan descargados")
    void upWhenModelsPresent() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.listModels()).thenReturn(new OllamaApi.ListModelResponse(
                List.of(model("qwen3:8b"), model("nomic-embed-text:latest"))));

        var health = new OllamaHealthIndicator(api, List.of("qwen3:8b", "nomic-embed-text")).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("modelosRequeridos", List.of("qwen3:8b", "nomic-embed-text"));
    }

    @Test
    @DisplayName("Caido cuando falta un modelo requerido")
    void downWhenModelMissing() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.listModels()).thenReturn(new OllamaApi.ListModelResponse(List.of(model("qwen3:8b"))));

        var health = new OllamaHealthIndicator(api, List.of("qwen3:8b", "llama3.1:8b")).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("faltantes", List.of("llama3.1:8b"));
    }

    @Test
    @DisplayName("Caido cuando Ollama no responde")
    void downWhenUnreachable() {
        OllamaApi api = mock(OllamaApi.class);
        when(api.listModels()).thenThrow(new RuntimeException("conexion rechazada"));

        var health = new OllamaHealthIndicator(api, List.of("qwen3:8b")).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("motivo", "Ollama no responde");
    }
}
