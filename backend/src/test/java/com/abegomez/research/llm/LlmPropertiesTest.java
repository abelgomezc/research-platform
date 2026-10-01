package com.abegomez.research.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class LlmPropertiesTest {

    @Test
    @DisplayName("Resuelve el modelo de cada rol desde propiedades")
    void resolvesModelPerRole() {
        LlmProperties properties = bind(java.util.Map.of(
                "app.llm.models.planner", "qwen3:8b",
                "app.llm.models.researcher", "llama3.1:8b",
                "app.llm.models.verifier", "qwen3:8b",
                "app.llm.models.synthesizer", "qwen3:14b",
                "app.llm.models.reviewer", "qwen3:8b",
                "app.llm.models.evaluator", "qwen3:8b",
                "app.llm.embedding-model", "nomic-embed-text"));

        assertThat(properties.modelFor(AgentRole.PLANNER)).isEqualTo("qwen3:8b");
        assertThat(properties.modelFor(AgentRole.RESEARCHER)).isEqualTo("llama3.1:8b");
        assertThat(properties.modelFor(AgentRole.SYNTHESIZER)).isEqualTo("qwen3:14b");
        assertThat(properties.getEmbeddingModel()).isEqualTo("nomic-embed-text");
    }

    @Test
    @DisplayName("Falla de forma explicita si falta el modelo de un rol")
    void failsWhenRoleModelMissing() {
        LlmProperties properties = bind(java.util.Map.of("app.llm.models.planner", "qwen3:8b"));

        assertThatThrownBy(() -> properties.modelFor(AgentRole.REVIEWER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.llm.models.reviewer");
    }

    @Test
    @DisplayName("Los parametros de generacion son independientes por rol")
    void parametersArePerRole() {
        LlmProperties properties = bind(java.util.Map.of(
                "app.llm.models.planner", "qwen3:8b",
                "app.llm.params.verifier.temperature", "0.05",
                "app.llm.params.verifier.max-tokens", "1024"));

        assertThat(properties.getParams().forRole(AgentRole.VERIFIER).temperature()).isEqualTo(0.05);
        assertThat(properties.getParams().forRole(AgentRole.VERIFIER).maxTokens()).isEqualTo(1024);
        assertThat(properties.getParams().forRole(AgentRole.PLANNER).temperature()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("La politica de reintentos es configurable")
    void retryIsConfigurable() {
        LlmProperties properties = bind(java.util.Map.of(
                "app.llm.models.planner", "qwen3:8b",
                "app.llm.retry.max-attempts", "5",
                "app.llm.retry.initial-backoff", "3s"));

        assertThat(properties.getRetry().getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getRetry().getInitialBackoff().toSeconds()).isEqualTo(3);
        assertThat(properties.getRetry().getMultiplier()).isEqualTo(2.0);
    }

    private static LlmProperties bind(java.util.Map<String, String> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("app.llm", Bindable.of(LlmProperties.class))
                .orElseGet(LlmProperties::new);
    }
}
