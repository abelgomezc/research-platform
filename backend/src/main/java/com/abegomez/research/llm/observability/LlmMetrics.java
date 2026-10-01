package com.abegomez.research.llm.observability;

import com.abegomez.research.llm.AgentRole;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Metricas de las llamadas al modelo: duracion,okens y fallos por rol de agente.
 */
@Component
public class LlmMetrics {

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(AgentRole role, String model, int inputTokens, int outputTokens,
                              long durationMs, int attempts) {
        Timer.builder("llm.call.duration")
                .description("Duracion de las llamadas al modelo de lenguaje")
                .tag("role", role.name().toLowerCase())
                .tag("model", model)
                .publishPercentileHistogram()
                .register(registry)
                .record(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        registry.counter("llm.call.attempts", "role", role.name().toLowerCase(),
                "outcome", "success").increment(attempts - 1);

        if (inputTokens > 0) {
            registry.counter("llm.tokens.input", "role", role.name().toLowerCase(),
                    "model", model).increment(inputTokens);
        }
        if (outputTokens > 0) {
            registry.counter("llm.tokens.output", "role", role.name().toLowerCase(),
                    "model", model).increment(outputTokens);
        }
    }

    public void recordFailure(AgentRole role, String model, int attempts) {
        registry.counter("llm.call.attempts", "role", role.name().toLowerCase(),
                "outcome", "failure").increment(attempts);
        registry.counter("llm.call.failures", "role", role.name().toLowerCase(),
                "model", model).increment();
    }
}
