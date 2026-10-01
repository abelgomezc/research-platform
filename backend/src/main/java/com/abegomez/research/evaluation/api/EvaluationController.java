package com.abegomez.research.evaluation.api;

import java.util.List;

import com.abegomez.research.evaluation.EvaluationRunner;
import com.abegomez.research.evaluation.MetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de metricas y evaluacion.
 *
 * <p>{@code POST /evaluation/run} lanza el dataset completo, que consume el mismo
 * presupuesto que una investigacion real. Se expone pero no se llama desde la
 * interfaz: es una operacion de medicion, no parte del uso normal.
 */
@RestController
@RequestMapping("/api")
public class EvaluationController {

    private final MetricsService metrics;
    private final EvaluationRunner runner;

    public EvaluationController(MetricsService metrics, EvaluationRunner runner) {
        this.metrics = metrics;
        this.runner = runner;
    }

    /**
     * Metricas de una investigacion.
     */
    @GetMapping("/research/{id}/metrics")
    public MetricsService.InvestigationMetrics metricas(@PathVariable long id) {
        return metrics.de(id);
    }

    /**
     * Comparativa entre investigaciones.
     */
    @GetMapping("/evaluation/summary")
    public List<MetricsService.AggregateMetrics> resumen(
            @RequestParam(defaultValue = "50") int limite) {
        return metrics.agregado(Math.min(200, Math.max(1, limite)));
    }

    /**
     * Ejecuta el dataset de evaluacion.
     *
     * <p>Bloqueante y lento: cada caso es una investigacion completa. El endpoint
     * devuelve los resultados cuando todos terminan, no el progreso.
     */
    @PostMapping("/evaluation/run")
    public ResponseEntity<EvaluationRunner.EvaluationReport> ejecutar(
            @RequestParam(required = false) Integer limite) {
        return ResponseEntity.ok(runner.ejecutar(limite));
    }
}
