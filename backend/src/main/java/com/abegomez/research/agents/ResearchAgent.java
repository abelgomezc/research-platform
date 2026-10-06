package com.abegomez.research.agents;

import java.util.List;

import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.llm.LlmGateway;
import com.abegomez.research.llm.LlmMessages;
import com.abegomez.research.llm.LlmResult;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolExecutor;
import com.abegomez.research.tools.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agente que investiga una tarea usando herramientas.
 *
 * <p>El bucle es deliberadamente corto y acotado por tres limites que el codigo
 * impone, no el modelo:
 *
 * <ol>
 *   <li><b>Pasos maximos.</b> Un agente que no termina se detiene solo.</li>
 *   <li><b>Presupuesto.</b> Antes de cada paso se comprueba que queda margen; si
 *       no, se para con lo que haya.</li>
 *   <li><b>Cierre obligatorio.</b> Sin una tool de cierre, el agente podria
 *       agotar el presupuesto sin devolver nada utilizable.</li>
 * </ol>
 *
 * <p>Ningun fallo de tool propaga. Se devuelve al agente como texto y decide: una
 * fuente caida no es motivo para perder la tarea entera.
 */
@Service
public class ResearchAgent {

    private static final Logger log = LoggerFactory.getLogger(ResearchAgent.class);

    private static final String VERSION_PROMPT = "researcher-v1";

    /** Tope de pasos por tarea. */
    private static final int MAX_PASOS = 12;

    private final LlmGateway llm;
    private final ToolExecutor executor;
    private final ObjectMapper objectMapper;

    public ResearchAgent(LlmGateway llm, ToolExecutor executor, ObjectMapper objectMapper) {
        this.llm = llm;
        this.executor = executor;
        this.objectMapper = objectMapper;
    }

    public String versionPrompt() {
        return VERSION_PROMPT;
    }

    /**
     * Ejecuta una tarea de investigacion.
     *
     * @param context           investigacion, tarea y ejecucion del agente
     * @param descripcionTarea  que hay que determinar
     * @param herramientas      nombres de tools que el agente puede usar
     * @param maxTokensPaso     presupuesto de la llamada de este paso
     * @return resultado de la tarea
     */
    public TaskOutcome investigar(ToolContext context, String descripcionTarea,
                                  List<String> herramientas, long maxTokensPaso) {
        String agente = context.agentRole() == null ? "RESEARCHER" : context.agentRole().nombre();

        LlmMessages mensajes = LlmMessages.single(promptSistema(herramientas), descripcionTarea);

        int pasos = 0;
        StringBuilder transcripcion = new StringBuilder();
        long tokensConsumidos = 0;

        while (pasos < MAX_PASOS) {
            if (maxTokensPaso <= 0) {
                return TaskOutcome.sinPresupuesto(pasos, tokensConsumidos, transcripcion.toString());
            }

            pasos++;

            LlmResult respuesta;
            try {
                respuesta = llm.chatWithTools(AgentRole.RESEARCHER, mensajes);
            }
            catch (RuntimeException ex) {
                log.warn("El agente investigador fallo en el paso {} de la tarea {}: {}",
                        pasos, context.tareaId(), ex.getMessage());
                return TaskOutcome.fallida(pasos, tokensConsumidos, transcripcion.toString(),
                        "El modelo fallo: " + ex.getMessage());
            }

            tokensConsumidos += respuesta.totalTokens();

            ToolCall llamada = ToolCall.parse(respuesta.content(), objectMapper);

            log.debug("LLM respuesta raw (pasos={}): {}", pasos, respuesta.content());

            if (!llamada.valido()) {
                // Se devuelve el error al modelo en vez de terminar: puede
                // corregir la sintaxis en el siguiente paso.
                mensajes = mensajes.appendAssistant(respuesta.content())
                        .appendUser("La invocacion no se pudo interpretar: " + llamada.error()
                                + "\nCorrige el JSON y vuelve a intentarlo.");
                continue;
            }

            String nombreTool = llamada.tool();

            if (nombreTool.equals(com.abegomez.research.tools.ToolDefinition.Names.MARK_TASK_COMPLETE)) {
                return TaskOutcome.completada(pasos, tokensConsumidos, transcripcion.toString(),
                        llamada.params());
            }

            if (!herramientas.contains(nombreTool)) {
                // Una tool no permitida no se ejecuta: se informa al modelo para
                // que use una que si tenga.
                mensajes = mensajes.appendAssistant(respuesta.content())
                        .appendUser("No tienes permiso para usar '" + nombreTool
                                + "'. Tools disponibles: " + String.join(", ", herramientas));
                continue;
            }

            ToolResult resultado = executor.ejecutar(agente, nombreTool, context, llamada.params());

            transcripcion.append("[paso ").append(pasos).append("] ").append(nombreTool)
                    .append(resultado.exitoso() ? " ok" : " fallo").append('\n');

            mensajes = mensajes.appendAssistant(respuesta.content())
                    .appendUser("Resultado de '" + nombreTool + "':\n" + resultado.paraElAgente()
                            + "\n\nSigue investigando o cierra la tarea.");
        }

        return TaskOutcome.sinCierre(pasos, tokensConsumidos, transcripcion.toString());
    }

    private String promptSistema(List<String> herramientas) {
        return """
                Eres un agente de investigacion. Tu trabajo es responder UNA tarea
                concreta usando herramientas, y terminar cuando puedas.

                Herramientas disponibles:
                %s

                Como trabajar:
                - Responde SIEMPRE con un unico objeto JSON y nada mas.
                - Para usar una herramienta:
                  {"tool": "<nombre>", "params": { ... }}
                - Para terminar la tarea:
                  {"tool": "mark_task_complete", "params": {
                     "resumen": "que se busco, que se hallo y que quedo abierto",
                     "resultado": "COMPLETADA" | "DESCARTADA",
                     "informacionFaltante": ["lo que no se pudo determinar"]
                  }}

                Reglas innegociables:
                - No inventes datos. Todo lo que afirmes debe estar en el resultado
                  de una herramienta que hayas ejecutado.
                - Cita el texto exacto de la fuente cuando registres evidencia.
                - Si una herramienta falla, prueba con otra. Si ninguna funciona,
                  cierra la tarea como DESCARTADA explicando por que.
                - Si no sabes algo, dejalo en informacionFaltante. No lo rellenes.
                - Cierra la tarea antes de quedarte sin pasos. Devolver sin cerrar
                  hace que tu trabajo se pierda.
                """.formatted(String.join("\n  - ", herramientas));
    }

    /**
     * Resultado de investigar una tarea.
     *
     * @param cerradaSiNo   si el agente cerro la tarea
     * @param motivoParada  por que se detuvo, si no se cerro por el agente
     */
    public record TaskOutcome(
            boolean cerradaSiNo,
            boolean fallo,
            int pasos,
            long tokensEstimados,
            String transcripcion,
            com.fasterxml.jackson.databind.JsonNode parametrosCierre,
            String motivoParada) {

        static TaskOutcome completada(int pasos, long tokens, String transcripcion,
                                       com.fasterxml.jackson.databind.JsonNode params) {
            return new TaskOutcome(true, false, pasos, tokens, transcripcion, params, null);
        }

        static TaskOutcome sinCierre(int pasos, long tokens, String transcripcion) {
            return new TaskOutcome(false, false, pasos, tokens, transcripcion, null,
                    "El agente agoto los pasos sin cerrar la tarea");
        }

        static TaskOutcome sinPresupuesto(int pasos, long tokens, String transcripcion) {
            return new TaskOutcome(false, false, pasos, tokens, transcripcion, null,
                    "Se agoto el presupuesto antes de terminar la tarea");
        }

        static TaskOutcome fallida(int pasos, long tokens, String transcripcion, String motivo) {
            return new TaskOutcome(false, true, pasos, tokens, transcripcion, null, motivo);
        }
    }
}
