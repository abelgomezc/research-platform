package com.abegomez.research.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de las reglas de citacion del informe.
 *
 * <p>Es la comprobacion que traduce la promesa del proyecto en algo verificable:
 * lo que el informe afirma, lo cita.
 */
class ReportValidatorTest {

    private static final Set<Long> VALIDAS = Set.of(1L, 2L, 3L);

    @Test
    @DisplayName("Un informe que cita en cada afirmacion se aprueba")
    void informeCorrecto() {
        String informe = """
                # Informe
                ## Hallazgos
                El fraude aumento un 12 por ciento en 2024 [evidencia 1].
                La deteccion se baso en reglas y no en aprendizaje automatico [evidencia 2].
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado());
        assertEquals(2, resultado.totalCitas());
        assertTrue(resultado.lineasSinCita().isEmpty());
    }

    @Test
    @DisplayName("Una afirmacion sin cita impide aprobar")
    void afirmacionSinCita() {
        String informe = """
                # Informe
                ## Hallazgos
                El fraude aumento un 12 por ciento en 2024.
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertFalse(resultado.aprobado());
        assertEquals(1, resultado.lineasSinCita().size());
    }

    @Test
    @DisplayName("Una cita a evidencia que no existe impide aprobar")
    void citaAEvidenciaInexistente() {
        String informe = """
                # Informe
                El fraude aumento [evidencia 99].
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertFalse(resultado.aprobado());
        assertEquals(1, resultado.citasDesconocidas().size());
        assertTrue(resultado.citasDesconocidas().get(0).contains("99"));
    }

    @Test
    @DisplayName("Una cita a evidencia no verificada se trata como inexistente")
    void evidenciaNoVerificada() {
        // El conjunto de ids validos son solo los verificados, asi que citar el 5
        // es citar algo que la verificacion todavia no respalda.
        String informe = "El fraude aumento [evidencia 5].";

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertFalse(resultado.aprobado());
    }

    @Test
    @DisplayName("Una cita con varias evidencias cuenta todas")
    void citaMultiple() {
        String informe = "El dato aparece en dos fuentes [evidencias 1, 2].";

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado());
        assertEquals(2, resultado.totalCitas());
    }

    @Test
    @DisplayName("Una cita multiple donde una sola es invalida impide aprobar")
    void citaMultipleConUnaInvalida() {
        String informe = "El dato aparece en dos fuentes [evidencias 1, 88].";

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertFalse(resultado.aprobado());
        assertEquals(1, resultado.citasDesconocidas().size());
    }

    @Test
    @DisplayName("Los titulos no necesitan cita")
    void titulosSinCita() {
        String informe = """
                # Informe
                ## Hallazgos principales
                ### El contexto
                #### Detalle
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado(), "los titulos son estructura, no afirmaciones");
    }

    @Test
    @DisplayName("Declarar un limite no necesita cita")
    void declaracionDeLimite() {
        String informe = """
                # Informe
                No se pudo determinar la tasa de fraude de 2023.
                Queda sin confirmar si el modelo se actualizo ese ano.
                No hay evidencia suficiente sobre el impacto economico.
                Sin datos de origen para el sector publico.
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado(),
                "declarar que no se sabe debe poder hacerse sin inventar una fuente");
    }

    @Test
    @DisplayName("Las tablas y separadores no se tratan como afirmaciones")
    void tablasYSeparadores() {
        String informe = """
                # Informe
                | Concepto | Valor |
                |---------|-------|
                | Total   | 100   |
                ---texto---de-separacion
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado());
    }

    @Test
    @DisplayName("Un informe vacio no se aprueba")
    void informeVacio() {
        assertFalse(ReportValidator.validar("", VALIDAS).aprobado());
        assertFalse(ReportValidator.validar(null, VALIDAS).aprobado());
    }

    @Test
    @DisplayName("Un informe sin ninguna cita genera advertencia")
    void sinNingunaCita() {
        ReportValidator.Resultado resultado =
                ReportValidator.validar("# Informe\n\nEste es el informe.", VALIDAS);

        assertTrue(resultado.advertencias().stream()
                .anyMatch(advertencia -> advertencia.contains("ninguna cita")));
    }

    @Test
    @DisplayName("Las advertencias no impiden aprobar")
    void advertenciasNoBloquean() {
        String informe = "Un dato verificado [evidencia 1].";

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado());
    }

    @Test
    @DisplayName("Se aceptan variantes de mayusculas en el marcador de cita")
    void marcadorCaseInsensitive() {
        String informe = "El dato [Evidencia 1] y tambien [FUENTE 2].";

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertTrue(resultado.aprobado());
        assertEquals(2, resultado.totalCitas());
    }

    @Test
    @DisplayName("Una afirmacion larga sin cita se detecta aunque tenga puntacion")
    void conPuntuacion() {
        String informe = "El fraude aumento un 12 por ciento en 2024, segun el informe anual.";

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertFalse(resultado.aprobado());
    }

    @Test
    @DisplayName("La lista de problemas junciona forma y citas invalidas")
    void problemasCombinados() {
        String informe = """
                Una afirmacion sin cita alguna.
                Otra con cita a evidencia inexistente [evidencia 77].
                """;

        ReportValidator.Resultado resultado = ReportValidator.validar(informe, VALIDAS);

        assertFalse(resultado.aprobado());
        assertEquals(2, resultado.problemas().size());
    }
}
