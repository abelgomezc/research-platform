package com.abegomez.research.planning;

import java.util.List;

import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.llm.LlmGateway;
import com.abegomez.research.llm.LlmMessages;
import com.abegomez.research.llm.LlmResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Genera el plan inicial de una investigacion.
 *
 * <p>El Planner propone tareas; no las ejecuta y no decide el flujo. Su salida
 * se valida y se guarda, pero el orquestador puede descartar tareas que no
 * aporten nada. Un plan es una hipotesis de trabajo, no un contrato.
 *
 * <p>Si el modelo devuelve algo que no es JSON valido, se cae a un plan minimo
 * derivado del objetivo en lugar de fallar la investigacion. Un plan pobre
 * todavía permite investigar; uno inexistente no.
 */
@Service
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private static final String VERSION_PROMPT = "planner-v1";

    private final LlmGateway llm;
    private final ObjectMapper objectMapper;

    public PlannerAgent(LlmGateway llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    public String versionPrompt() {
        return VERSION_PROMPT;
    }

    /**
     * Genera el plan de una investigacion.
     *
     * @param investigacionId  investigacion a la que pertenece
     * @param objetivo         objetivo de la investigacion
     * @param maxTokens        presupuesto de esta llamada
     * @param contexto         informacion previa que el agente debe considerar
     */
    public ResearchPlan planificar(long investigacionId, String objetivo, int maxTokens,
                                   String contexto) {
        LlmResult resultado = llm.chat(AgentRole.PLANNER,
                LlmMessages.single(promptSistema(), promptUsuario(objetivo, contexto)));

        JsonNode plan = extraerPlan(resultado.content());

        if (plan == null || !plan.isArray()) {
            log.warn("El Planner no devolvio un plan utilizable para la investigacion {}. "
                    + "Se genera un plan minimo.", investigacionId);
            return planMinimo(investigacionId, objetivo);
        }

                return construir(investigacionId, objetivo, plan);
    }

    /**
     * Interpreta la respuesta del modelo.
     *
     * <p>Se acepta tanto un array puro como un objeto con la clave {@code tareas},
     * porque el modelo cambia entre una y otra forma y el plan no debe depender de
     * eso.
     */
    private JsonNode extraerPlan(String contenido) {
        if (contenido == null || contenido.isBlank()) {
            return null;
        }
        try {
            JsonNode nodo = objectMapper.readTree(limpiar(contenido));
            if (nodo.isArray()) {
                return nodo;
            }
            if (nodo.isObject() && nodo.path("tareas").isArray()) {
                return nodo.get("tareas");
            }
            return null;
        }
        catch (Exception ex) {
            log.warn("La respuesta del Planner no es JSON valido: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Quita el venvoltura de codigo que algunos modelos anaden.
     */
    private String limpiar(String contenido) {
        String texto = contenido.trim();
        if (texto.startsWith("```")) {
            int primera = texto.indexOf('\n');
            int ultimo = texto.lastIndexOf("```");
            if (primera > 0 && ultimo > primera) {
                return texto.substring(primera + 1, ultimo).trim();
            }
        }
        return texto;
    }

    private ResearchPlan construir(long investigacionId, String objetivo, JsonNode tareas) {
        List<ResearchPlan.PlanTask> lineas = new java.util.ArrayList<>();

        for (JsonNode tarea : tareas) {
            String descripcion = tarea.path("descripcion").asText("").trim();
            if (descripcion.length() < 15) {
                // Una tarea sin objetivo claro no es una tarea: el agente
                // consumira tokens sin poder decidir cuando ha terminado.
                log.debug("Tarea descartada por descripcion insuficiente: {}", descripcion);
                continue;
            }

            List<String> criterios = new java.util.ArrayList<>();
            for (JsonNode criterio : tarea.path("criterios")) {
                String texto = criterio.asText("").trim();
                if (!texto.isEmpty()) {
                    criterios.add(texto);
                }
            }

            lineas.add(new ResearchPlan.PlanTask(
                    descripcion,
                    ResearchPlan.TipoFuentePlan.desde(tarea.path("tipoFuente").asText(null)),
                    Math.max(0, Math.min(100, tarea.path("prioridad").asInt(50))),
                    criterios));
        }

        return new ResearchPlan(investigacionId, objetivo, lineas, List.of());
    }

    /**
     * Plan minimo cuando el modelo no responde de forma utilizable.
     */
    private ResearchPlan planMinimo(long investigacionId, String objetivo) {
        return new ResearchPlan(investigacionId, objetivo,
                List.of(new ResearchPlan.PlanTask(
                        "Determinar que se sabe hasta ahora sobre: " + objetivo
                                + ". Registrar lo encontrado y lo que no se pudo determinar.",
                        ResearchPlan.TipoFuentePlan.INTERNA, 50,
                        List.of("Hay al menos una evidencia registrada o se declara que no hay informacion"))),
                List.of());
    }

    private String promptSistema() {
        return """
                Eres el planificador de una plataforma de investigacion.

                Tu unico trabajo es descomponer el objetivo en lineas de trabajo
                concretas. No investigas, no redactas y no opinions sobre el tema.

                Reglas:
                - Cada tarea empieza por un verbo de accion y es autosuficiente.
                - Cada tarea indica COMO se sabra que esta terminada: ese es el
                  criterio, y es lo que permite cerrar la tarea sin inventar.
                - Prefiere pocas tareas precisas a muchas vaguas.
                - No incluyas tareas que solo repitan lo que ya consta en el contexto.

                Responde solo con JSON valido, sin texto alrededor, con esta forma:
                {
                  "tareas": [
                    {
                      "descripcion": "...",
                      "tipoFuente": "INTERNA" | "WEB" | "BASE_DATOS",
                      "prioridad": 0-100,
                      "criterios": ["...", "..."]
                    }
                  ]
                }
                """;
    }

    private String promptUsuario(String objetivo, String contexto) {
        return """
                Objetivo de la investigacion:
                %s

                Contexto disponible:
                %s

                Genera el plan.
                """.formatted(objetivo, contexto == null || contexto.isBlank()
                ? "(sin contexto previo: es la primera ronda)"
                : contexto);
    }
}
