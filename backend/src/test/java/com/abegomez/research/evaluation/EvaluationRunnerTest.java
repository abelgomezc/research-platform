package com.abegomez.research.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests del calculo de puntuacion del dataset.
 *
 * <p>Se prueban los agregados y la busqueda de criterios, que son la parte del
 * runner que se puede comprobar sin ejecutar una investigacion real.
 */
class EvaluationRunnerTest {

    @Test
    @DisplayName("La puntuacion media solo cuenta los casos ejecutados")
    void puntuacionMediaIgnoraNoEjecutados() {
        var ejecutados = new EvaluationRunner.EvaluationCaseResult(
                "A", "Caso A", 1L, true, 2, 2, 2, List.of(), true, "COMPLETED",
                1000L, 1.0, 4, 0, null);

        var fallido = new EvaluationRunner.EvaluationCaseResult(
                "B", "Caso B", 2L, false, 2, 0, 0, List.of(), false, "FAILED",
                null, null, null, null, "fallo");

        var descartado = new EvaluationRunner.EvaluationCaseResult(
                "C", "Caso C", 0L, false, 2, 0, 0, List.of(), false, "DESCARTADO",
                null, null, null, null, "sin criterios");

        var report = new EvaluationRunner.EvaluationReport(
                List.of(ejecutados, fallido, descartado), Duration.ofMinutes(1));

        assertEquals(3, report.totalCasos());
        assertEquals(1, report.casosEjecutados());
        assertEquals(1.0, report.puntuacionMedia(), 0.0001,
                "un caso fallido con 0 no debe arrastrar la media");
        assertEquals(1, report.informesAprobados());
    }

    @Test
    @DisplayName("Sin casos ejecutados la media es 0, no NaN")
    void sinCasosEjecutados() {
        var descartado = new EvaluationRunner.EvaluationCaseResult(
                "C", "Caso C", 0L, false, 2, 0, 0, List.of(), false, "DESCARTADO",
                null, null, null, null, "sin criterios");

        var report = new EvaluationRunner.EvaluationReport(List.of(descartado), Duration.ZERO);

        assertEquals(0.0, report.puntuacionMedia());
        assertEquals(0.0, report.tasaCitadoMedia());
    }

    @Test
    @DisplayName("La puntuacion es la fraccion de criterios cubiertos")
    void puntuacionPorCriterio() {
        var caso = new EvaluationRunner.EvaluationCaseResult(
                "A", "Caso A", 1L, true, 4, 3, 2, List.of(), false, "INTERRUPTED",
                100L, 0.5, 2, 1, null);

        assertEquals(0.75, caso.puntuacion(), 0.0001);
        assertEquals(0.5, caso.tasaCitado(), 0.0001);
    }

    @Test
    @DisplayName("Un caso sin criterios no divide por cero")
    void sinCriterios() {
        var caso = new EvaluationRunner.EvaluationCaseResult(
                "A", "Caso A", 1L, true, 0, 0, 0, List.of(), false, "COMPLETED",
                0L, 0.0, 0, 0, null);

        assertEquals(0.0, caso.puntuacion());
        assertEquals(0.0, caso.tasaCitado());
    }

    @Test
    @DisplayName("El dataset del proyecto carga y todos sus casos son evaluables")
    void datasetCargable() {
        // Se usa el classloader del test: el dataset esta en main/resources y
        // debe estar disponible sin levantar el contexto de Spring.
        var dataset = new EvaluationDataset(new ObjectMapper(),
                new org.springframework.core.io.DefaultResourceLoader());

        List<EvaluationDataset.EvaluationCase> casos = dataset.cargar();

        assertFalse(casos.isEmpty(), "el dataset debe tener al menos un caso");
        for (EvaluationDataset.EvaluationCase caso : casos) {
            assertTrue(caso.evaluable(),
                    "el caso " + caso.id() + " no tiene criterios y no se puede puntuar");
            assertTrue(caso.presupuestoTokens() > 0,
                    "el caso " + caso.id() + " necesita presupuesto");
        }
    }

    @Test
    @DisplayName("Un caso sin criterios se considera no evaluable")
    void casoNoEvaluable() {
        var sinCriterios = new EvaluationDataset.EvaluationCase(
                "X", "Sin criterios", "pregunta", List.of(), 1000, 1, "");

        var sinPregunta = new EvaluationDataset.EvaluationCase(
                "Y", "Sin pregunta", "", List.of("algo"), 1000, 1, "");

        assertFalse(sinCriterios.evaluable());
        assertFalse(sinPregunta.evaluable());
    }
}
