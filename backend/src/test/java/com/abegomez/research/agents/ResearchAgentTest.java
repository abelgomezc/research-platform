package com.abegomez.research.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.llm.LlmGateway;
import com.abegomez.research.llm.LlmMessages;
import com.abegomez.research.llm.LlmResult;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolDefinition;
import com.abegomez.research.tools.ToolExecutor;
import com.abegomez.research.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests del bucle de tool calling.
 *
 * <p>El gateway falso devuelve respuestas encoladas, lo que permite comprobar
 * el bucle completo sin modelo: que se detenga al cerrar, que repare una
 * invocacion mal formada y que respete los limites que impone el codigo.
 */
class ResearchAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> HERRAMIENTAS = List.of(
            ToolDefinition.Names.SEARCH_WEB,
            ToolDefinition.Names.SAVE_EVIDENCE,
            ToolDefinition.Names.MARK_TASK_COMPLETE);

    /**
     * Gateway que devuelve respuestas preparadas, en orden.
     */
    private static final class GatewayEncolado implements LlmGateway {

        private final Deque<String> respuestas = new ArrayDeque<>();
        private final List<LlmMessages> conversaciones = new ArrayList<>();
        RuntimeException fallo;

        GatewayEncolado(String... contenido) {
            for (String c : contenido) {
                // Cada linea es una respuesta independiente: varios pasos del
                // bucle se encolan asi.
                for (String linea : c.split("\n")) {
                    if (!linea.isBlank()) {
                        respuestas.add(linea);
                    }
                }
            }
        }

        @Override
        public LlmResult chat(AgentRole role, LlmMessages messages) {
            return responder(messages);
        }

        @Override
        public LlmResult chatWithTools(AgentRole role, LlmMessages messages) {
            return responder(messages);
        }

        private LlmResult responder(LlmMessages messages) {
            conversaciones.add(messages);
            if (fallo != null) {
                throw fallo;
            }
            if (respuestas.isEmpty()) {
                // Sin respuestas preparadas el agente recibiria null y el bucle
                // pararia; se hace explicito para que el test falle de forma legible.
                throw new IllegalStateException("Se agotaron las respuestas encoladas del test");
            }
            String contenido = respuestas.poll();
            return new LlmResult(contenido, "modelo-test", 100, 50, true, 10L, 1);
        }

        @Override
        public String modelFor(AgentRole role) {
            return "modelo-test";
        }

        int llamadas() {
            return conversaciones.size();
        }
    }

    private static ToolContext contexto() {
        return new ToolContext(1L, 10L, 100L,
                new ToolContext.AgentRoleSnapshot("RESEARCHER", "modelo-test"));
    }

    private ToolExecutor executorQueSiempreFalla() {
        ToolExecutor executor = mock(ToolExecutor.class);
        when(executor.ejecutar(anyString(), anyString(), any(), any()))
                .thenReturn(com.abegomez.research.tools.ToolResult.fallo(
                        "la dependencia no responde", Duration.ZERO, 1));
        return executor;
    }

    @Test
    @DisplayName("El agente usa una tool y luego cierra la tarea")
    void cicloBasico() {
        GatewayEncolado gateway = new GatewayEncolado(
                "{\"tool\":\"search_web\",\"params\":{\"query\":\"fraude\"}}",
                "{\"tool\":\"save_evidence\",\"params\":{\"citaTextual\":\"texto\",\"afirmacion\":\"a\"}}",
                "{\"tool\":\"mark_task_complete\",\"params\":{\"resumen\":\"encontre lo esperado\","
                        + "\"resultado\":\"COMPLETADA\"}}");

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);

        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertTrue(outcome.cerradaSiNo());
        assertFalse(outcome.fallo());
        assertEquals(3, outcome.pasos());
        assertEquals("encontre lo esperado", outcome.parametrosCierre().path("resumen").asText());
    }

    @Test
    @DisplayName("Una tool que falla no detiene el agente: se le devuelve el error")
    void toolQueFallaNoDetiene() {
        GatewayEncolado gateway = new GatewayEncolado(
                "{\"tool\":\"search_web\",\"params\":{\"query\":\"x\"}}",
                "{\"tool\":\"mark_task_complete\",\"params\":{\"resumen\":\"s\",\"resultado\":\"DESCARTADA\"}}");

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertTrue(outcome.cerradaSiNo(),
                "un fallo de tool no debe detener la tarea; el agente puede cerrarla como descartada");
        assertEquals("DESCARTADA", outcome.parametrosCierre().path("resultado").asText());
    }

    @Test
    @DisplayName("Una invocacion mal formada se devuelve al modelo para que la corrija")
    void reintentaAnteJsonInvalido() {
        GatewayEncolado gateway = new GatewayEncolado(
                "no se que hacer",
                "{\"tool\":\"mark_task_complete\",\"params\":{\"resumen\":\"s\",\"resultado\":\"COMPLETADA\"}}");

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertTrue(outcome.cerradaSiNo());
        assertEquals(2, outcome.pasos(), "el primer paso se consume en la respuesta invalida");
    }

    @Test
    @DisplayName("Una tool fuera de la lista no se ejecuta")
    void toolNoPermitidaNoSeEjecuta() {
        GatewayEncolado gateway = new GatewayEncolado(
                "{\"tool\":\"fetch_page\",\"params\":{\"url\":\"https://x.com\"}}",
                "{\"tool\":\"mark_task_complete\",\"params\":{\"resumen\":\"s\",\"resultado\":\"COMPLETADA\"}}");

        ToolExecutor executor = mock(ToolExecutor.class);
        ResearchAgent agent = new ResearchAgent(gateway, executor, MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertTrue(outcome.cerradaSiNo());
        org.mockito.Mockito.verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("El bucle se detiene si el modelo no cierra nunca la tarea")
    void detieneSinCierre() {
        // Todas las respuestas piden una tool legitima: el bucle debe agotar sus
        // pasos y devolverlo, en lugar de girar indefinidamente.
        GatewayEncolado gateway = new GatewayEncolado(
                repetir("{\"tool\":\"search_web\",\"params\":{\"query\":\"x\"}}", 20));

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertFalse(outcome.cerradaSiNo());
        assertFalse(outcome.fallo());
        assertTrue(outcome.motivoParada().contains("sin cerrar"));
        assertEquals(12, outcome.pasos(), "debe agotar exactamente el maximo de pasos");
    }

    @Test
    @DisplayName("Sin presupuesto el agente no hace ni una llamada")
    void sinPresupuestoNoLlama() {
        GatewayEncolado gateway = new GatewayEncolado("{\"tool\":\"mark_task_complete\"}");

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 0);

        assertFalse(outcome.cerradaSiNo());
        assertEquals(0, gateway.llamadas(), "no debe contactar al modelo sin presupuesto");
        assertTrue(outcome.motivoParada().contains("presupuesto"));
    }

    @Test
    @DisplayName("Un fallo del modelo no propaga: se devuelve como resultado de tarea")
    void falloDelModeloNoPropaga() {
        GatewayEncolado gateway = new GatewayEncolado("{\"tool\":\"mark_task_complete\"}");
        gateway.fallo = new RuntimeException("ollama caido");

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertTrue(outcome.fallo());
        assertFalse(outcome.cerradaSiNo());
        assertTrue(outcome.motivoParada().contains("modelo fallo"));
    }

    @Test
    @DisplayName("Cada tool ejecutada se registra en la transcripcion")
    void registraTranscripcion() {
        GatewayEncolado gateway = new GatewayEncolado(
                "{\"tool\":\"search_web\",\"params\":{\"query\":\"x\"}}",
                "{\"tool\":\"mark_task_complete\",\"params\":{\"resumen\":\"s\",\"resultado\":\"COMPLETADA\"}}");

        ResearchAgent agent = new ResearchAgent(gateway, executorQueSiempreFalla(), MAPPER);
        ResearchAgent.TaskOutcome outcome = agent.investigar(contexto(), "tarea", HERRAMIENTAS, 5000);

        assertTrue(outcome.transcripcion().contains("search_web"));
        assertTrue(outcome.transcripcion().lines().count() >= 1);
    }

    private static String repetir(String valor, int veces) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < veces; i++) {
            sb.append(valor).append('\n');
        }
        return sb.toString();
    }
}
