package com.abegomez.research.tools.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Timer.Sample;
import org.springframework.stereotype.Component;

/**
 * Metricas de ejecucion de herramientas: cuenta, duracion y fallos por tool.
 */
@Component
public class ToolMetrics {

    private final MeterRegistry registry;

    public ToolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String herramienta, String estado, long duracionMs) {
        registry.counter("tool.executions", "tool", herramienta, "estado", estado).increment();

        Timer.builder("tool.execution.duration")
                .description("Duracion de las ejecuciones de herramientas")
                .tag("tool", herramienta)
                .publishPercentileHistogram()
                .register(registry)
                .record(duracionMs, java.util.concurrent.TimeUnit.MILLISECONDS);

        if ("FALLIDA".equals(estado) || "TIMEOUT".equals(estado)) {
            registry.counter("tool.failures", "tool", herramienta, "estado", estado).increment();
        }
    }

    /**
     * Muestra una ejecucion en curso para medirla aunque el llamador la cancele.
     */
    public Sample start(String herramienta) {
        return Timer.start(registry);
    }
}