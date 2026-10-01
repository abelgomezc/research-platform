package com.abegomez.research.agents;

import java.util.List;

import com.abegomez.research.llm.AgentRole;
import com.abegomez.research.planning.TaskRepository;
import com.abegomez.research.research.ResearchEventType;
import com.abegomez.research.research.ResearchManager;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ejecuta una tarea de punta a punta: abre la ejecucion del agente, lo invoca,
 * cierra la tarea y registra el resultado.
 *
 * <p>La ejecucion del agente se registra siempre, se complete o no. Sin eso, una
 * tarea que agota el presupuesto deja cero rastro y la evaluacion de la Fase 10
 * no puede distinguir "no hizo nada" de "no se ejecuto".
 */
@Service
public class AgentExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AgentExecutionService.class);

    private final ResearchAgent agent;
    private final ToolRegistry registry;
    private final ResearchManager manager;
    private final TaskRepository tareas;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentExecutionService(ResearchAgent agent, ToolRegistry registry,
                                 ResearchManager manager, TaskRepository tareas,
                                 JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.agent = agent;
        this.registry = registry;
        this.manager = manager;
        this.tareas = tareas;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Ejecuta una tarea como agente INVESTIGADOR.
     *
     * @return resultado de la tarea
     */
    @Transactional
    public ResearchAgent.TaskOutcome ejecutarTarea(long investigacionId, long tareaId) {
        var tarea = tareas.find(tareaId).orElseThrow(() -> new IllegalArgumentException(
                "No existe la tarea " + tareaId));

        var estado = manager.obtener(investigacionId);
        String modelo = com.abegomez.research.llm.AgentRole.RESEARCHER.name();

        Long ejecucionId = jdbcTemplate.queryForObject("""
                INSERT INTO ejecuciones_agente
                    (investigacion_id, tarea_id, agente, modelo, version_prompt, estado)
                VALUES (?, ?, ?, ?, ?, 'EN_CURSO')
                RETURNING id
                """, Long.class, investigacionId, tareaId, "RESEARCHER", modelo, agent.versionPrompt());

        List<String> herramientas = registry.toolsDe(AgentRole.RESEARCHER.name()).stream()
                .map(tool -> tool.definition().name())
                .toList();

        // El contexto lleva tarea y ejecucion: las tools no reciben otra cosa, de
        // modo que no pueden escribir fuera del alcance de esta tarea.
        ToolContext context = new ToolContext(investigacionId, tareaId, ejecucionId,
                new ToolContext.AgentRoleSnapshot("RESEARCHER", modelo));

        long presupuestoPaso = manager.maximoSiguienteLlamada(investigacionId);

        ResearchAgent.TaskOutcome outcome = agent.investigar(context, tarea.descripcion(),
                herramientas, presupuestoPaso);

        if (outcome.tokensEstimados() > 0) {
            manager.descontarTokens(investigacionId, outcome.tokensEstimados());
        }

        cerrarEjecucion(ejecucionId, outcome);

        if (outcome.cerradaSiNo()) {
            cerrarTarea(tareaId, outcome, investigacionId);
        }
        else if (outcome.fallo()) {
            tareas.marcarFallida(tareaId, outcome.motivoParada());
            eventoTarea(investigacionId, tareaId, ResearchEventType.TASK_FAILED,
                    outcome.motivoParada());
        }
        else {
            // No cerro y no fallo: la tarea vuelve a PENDIENTE para el siguiente
            // intento. Marcar COMPLETADA seria mentir sobre lo que se hizo.
            tareas.devolverAPendiente(tareaId);
            eventoTarea(investigacionId, tareaId, ResearchEventType.TASK_FAILED,
                    outcome.motivoParada());
        }

        log.info("Investigacion {}, tarea {}: {} pasos, cerrada={}, tokens~{}",
                investigacionId, tareaId, outcome.pasos(), outcome.cerradaSiNo(),
                outcome.tokensEstimados());
        return outcome;
    }

    private void cerrarEjecucion(long ejecucionId, ResearchAgent.TaskOutcome outcome) {
        jdbcTemplate.update("""
                UPDATE ejecuciones_agente
                SET estado = ?, duracion_ms = ?, error = ?
                WHERE id = ?
                """,
                outcome.fallo() ? "FALLIDA" : "EXITOSA",
                0L,
                outcome.motivoParada(),
                ejecucionId);
    }

    /**
     * Traduce el cierre que pidio el agente a una transicion de la tarea.
     */
    private void cerrarTarea(long tareaId, ResearchAgent.TaskOutcome outcome, long investigacionId) {
        var params = outcome.parametrosCierre();

        String resumen = params.path("resumen").asText("").trim();
        String resultado = params.path("resultado").asText("").toUpperCase(java.util.Locale.ROOT);

        boolean completada = "COMPLETADA".equals(resultado);

        jdbcTemplate.update("""
                UPDATE tareas_investigacion
                SET estado = ?,
                    resumen_resultado = ?,
                    intentos = intentos + 1,
                    actualizado_en = NOW()
                WHERE id = ?
                """, completada ? "COMPLETADA" : "DESCARTADA", resumen, tareaId);

        eventoTarea(investigacionId, tareaId,
                completada ? ResearchEventType.TASK_COMPLETED : ResearchEventType.TASK_DISCARDED,
                resumen);
    }

    private void eventoTarea(long investigacionId, long tareaId, ResearchEventType tipo, String detalle) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tareaId", tareaId);
        if (detalle != null) {
            payload.put("detalle", detalle.length() > 500 ? detalle.substring(0, 500) : detalle);
        }
        manager.evento(investigacionId, tipo, payload);
    }

    /**
     * Herramientas que el investigador puede usar.
     *
     * <p>Se expone para el prompt y los tests, de modo que la lista que el
     * sistema impone y la que el agente ve no puedan divergir.
     */
    public List<String> herramientasDelInvestigador() {
        return registry.toolsDe(AgentRole.RESEARCHER.name()).stream()
                .map(tool -> tool.definition().name())
                .toList();
    }

    /**
     * Nombres de todas las tools declaradas, para diagnostico.
     */
    public List<String> herramientasDeclaradas() {
        return registry.names();
    }
}
