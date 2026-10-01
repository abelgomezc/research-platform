package com.abegomez.research.research;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import com.abegomez.research.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests de la maquina de estados.
 *
 * <p>Se comprueba que todo estado tiene transiciones declaradas. Un estado
 * anadido al enum sin tocar esta tabla se quedaria sin poder avanzar y el fallo
 * apareceria en ejecucion, no aqui.
 */
class ResearchStateMachineTest {

    @Test
    @DisplayName("El flujo normal recorre las fases en orden")
    void flujoNormal() {
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.CREATED, ResearchState.PLANNING));
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.PLANNING, ResearchState.RESEARCHING));
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.RESEARCHING, ResearchState.VERIFYING));
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.VERIFYING, ResearchState.SYNTHESIZING));
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.SYNTHESIZING, ResearchState.REVIEWING));
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.REVIEWING, ResearchState.COMPLETED));
    }

    @Test
    @DisplayName("Un informe rechazado vuelve a sintetizarse")
    void revisarYPodereCorregir() {
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.REVIEWING, ResearchState.SYNTHESIZING),
                "el Reviewer debe poder pedir una correccion");
    }

    @Test
    @DisplayName("Se puede volver a investigar cuando la verificacion encuentra problemas")
    void verificarPuedeDevolverAResearch() {
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.VERIFYING, ResearchState.RESEARCHING));
        assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.RESEARCHING, ResearchState.RESEARCHING),
                "una ronda nueva empieza en el mismo estado");
    }

    @Test
    @DisplayName("Una investigacion interrumpida se puede reanudar en la fase en que estaba")
    void reanudarInterrumpida() {
        for (ResearchState estado : EnumSet.of(ResearchState.RESEARCHING, ResearchState.VERIFYING,
                ResearchState.SYNTHESIZING, ResearchState.REVIEWING)) {
            assertTrue(ResearchStateMachine.puedeTransicionar(ResearchState.INTERRUPTED, estado),
                    "debe poder reanudarse en " + estado);
        }
    }

    @ParameterizedTest
    @EnumSource(ResearchState.class)
    @DisplayName("Todo estado no terminal puede terminar en fallo o cancelacion")
    void todoEstadoTerminaEnFalloOCancelacion(ResearchState estado) {
        if (estado.isTerminal()) {
            assertTrue(ResearchStateMachine.transicionesDesde(estado).isEmpty(),
                    estado + " es terminal y no debe aceptar transiciones");
            return;
        }
        assertTrue(ResearchStateMachine.puedeTransicionar(estado, ResearchState.FAILED),
                estado + " debe poder pasar a FAILED");
        assertTrue(ResearchStateMachine.puedeTransicionar(estado, ResearchState.CANCELLED),
                estado + " debe poder pasar a CANCELLED");
    }

    @Test
    @DisplayName("INTERRUPTED no es terminal: se detiene pero se puede reanudar")
    void interruptedNoEsTerminal() {
        assertFalse(ResearchState.INTERRUPTED.isTerminal(),
                "una investigacion interrumpida se reanuda, no ha terminado");
        assertTrue(ResearchState.INTERRUPTED.isResumable());

        // Es la unica diferencia con un estado terminal, y por eso el
        // repositorio no debe marcar finalizado_en al interrumpir.
        assertFalse(ResearchStateMachine.transicionesDesde(ResearchState.INTERRUPTED).isEmpty());
    }

    @Test
    @DisplayName("Un estado terminal no admite ninguna transicion")
    void terminalesNoTransicionan() {
        for (ResearchState estado : EnumSet.of(ResearchState.COMPLETED, ResearchState.FAILED,
                ResearchState.CANCELLED)) {
            assertTrue(estado.isTerminal());
            for (ResearchState hacia : ResearchState.values()) {
                assertFalse(ResearchStateMachine.puedeTransicionar(estado, hacia),
                        estado + " no deberia poder pasar a " + hacia);
            }
        }
    }

    @Test
    @DisplayName("No se puede saltar de CREATED a COMPLETED")
    void noSeSaltaElFlujo() {
        assertFalse(ResearchStateMachine.puedeTransicionar(ResearchState.CREATED, ResearchState.COMPLETED));
        assertFalse(ResearchStateMachine.puedeTransicionar(ResearchState.CREATED, ResearchState.SYNTHESIZING));
        assertFalse(ResearchStateMachine.puedeTransicionar(ResearchState.PLANNING, ResearchState.COMPLETED));
    }

    @Test
    @DisplayName("exigir lanza excepcion ante una transicion invalida")
    void exigirFalla() {
        assertThrows(BusinessException.class,
                () -> ResearchStateMachine.exigir(ResearchState.CREATED, ResearchState.COMPLETED));

        // Una transicion valida no lanza.
        ResearchStateMachine.exigir(ResearchState.CREATED, ResearchState.PLANNING);
    }

    @Test
    @DisplayName("Todo estado no terminal declara transiciones: no hay estados muertos")
    void noHayEstadosMuertos() {
        for (ResearchState estado : ResearchState.values()) {
            if (estado.isTerminal()) {
                continue;
            }
            assertFalse(ResearchStateMachine.transicionesDesde(estado).isEmpty(),
                    estado + " no declara transiciones y la investigacion se quedaria atrapada");
        }
    }

    @Test
    @DisplayName("Los estados de trabajo son los que el orquestador ejecuta")
    void estadosDeTrabajo() {
        Set<ResearchState> trabajo = EnumSet.of(ResearchState.PLANNING, ResearchState.RESEARCHING,
                ResearchState.VERIFYING, ResearchState.SYNTHESIZING, ResearchState.REVIEWING);

        for (ResearchState estado : trabajo) {
            assertTrue(ResearchStateMachine.esEstadoDeTrabajo(estado));
        }
        assertFalse(ResearchStateMachine.esEstadoDeTrabajo(ResearchState.COMPLETED));
        assertFalse(ResearchStateMachine.esEstadoDeTrabajo(ResearchState.CREATED));
    }
}
