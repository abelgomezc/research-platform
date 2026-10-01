package com.abegomez.research.common.health;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health indicator del proveedor de modelos local. Verifica que Ollama responda
 * y que los modelos configurados por rol esten disponibles.
 */
@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private final OllamaApi ollamaApi;
    private final List<String> requiredModels;

    public OllamaHealthIndicator(OllamaApi ollamaApi,
                                 com.abegomez.research.llm.LlmProperties properties) {
        this(ollamaApi, java.util.Arrays.stream(com.abegomez.research.llm.AgentRole.values())
                .map(properties::modelFor)
                .distinct()
                .toList());
    }

    OllamaHealthIndicator(OllamaApi ollamaApi, List<String> requiredModels) {
        this.ollamaApi = ollamaApi;
        this.requiredModels = requiredModels;
    }

    @Override
    public Health health() {
        try {
            var response = ollamaApi.listModels();
            List<String> available = response == null || response.models() == null
                    ? List.of()
                    : response.models().stream().map(model -> model.name()).toList();

            List<String> missing = requiredModels.stream()
                    .filter(required -> available.stream().noneMatch(name -> matches(name, required)))
                    .toList();

            if (!missing.isEmpty()) {
                return Health.down()
                        .withDetail("motivo", "Faltan modelos en Ollama")
                        .withDetail("faltantes", missing)
                        .withDetail("disponibles", available)
                        .build();
            }

            return Health.up()
                    .withDetail("modelos", available)
                    .withDetail("modelosRequeridos", requiredModels)
                    .build();
        }
        catch (RuntimeException ex) {
            return Health.down(ex)
                    .withDetail("motivo", "Ollama no responde")
                    .build();
        }
    }

    private static boolean matches(String available, String required) {
        return available.equals(required) || available.startsWith(required + ":");
    }

    /**
     * Tiempo maximo de espera para la comprobacion de salud.
     */
    public Duration timeout() {
        return Duration.ofSeconds(5);
    }
}
