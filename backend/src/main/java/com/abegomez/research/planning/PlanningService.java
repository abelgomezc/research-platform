package com.abegomez.research.planning;

import java.util.List;

import com.abegomez.research.research.ResearchEventType;
import com.abegomez.research.research.ResearchManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persiste el plan del Planner como tareas reales.
 *
 * <p>Separa generar el plan de guardarlo para que un plan valido nunca se pierda
 * por un fallo al escribir, y para que la validacion del plan y su persistencia
 * sean un unico paso coherente.
 */
@Service
public class PlanningService {

    private static final Logger log = LoggerFactory.getLogger(PlanningService.class);

    /**
     * Tope de tareas que se crean de un plan.
     *
     * <p>Un modelo puede devolver cincuenta lineas de trabajo para una pregunta
     * que se responde con dos. El tope protege el presupuesto, que es una
     * decision del sistema y no del modelo.
     */
    private static final int MAX_TAREAS_POR_PLAN = 12;

    private final PlannerAgent planner;
    private final TaskRepository tareas;
    private final ResearchManager manager;
    private final ObjectMapper objectMapper;

    public PlanningService(PlannerAgent planner, TaskRepository tareas,
                           ResearchManager manager, ObjectMapper objectMapper) {
        this.planner = planner;
        this.tareas = tareas;
        this.manager = manager;
        this.objectMapper = objectMapper;
    }

    /**
     * Genera el plan de una ronda y lo persiste como tareas.
     *
     * @param ronda ronda a la que pertenecen las tareas
     * @return numero de tareas creadas
     */
    @Transactional
    public int planificarYGuardar(long investigacionId, String objetivo, int ronda, String contexto) {
        var estado = manager.obtener(investigacionId);

        long maxTokens = manager.maximoSiguienteLlamada(investigacionId);
        if (maxTokens <= 0) {
            log.warn("No se planifica la ronda {}: no queda presupuesto", ronda);
            return 0;
        }

        ResearchPlan plan = planner.planificar(investigacionId, objetivo,
                (int) Math.min(maxTokens, 8000), contexto);

        if (manager.descontarTokens(investigacionId, estimarTokens(plan))) {
            log.debug("Investigacion {}: plan consumio ~{} tokens estimados",
                    investigacionId, estimarTokens(plan));
        }

        return persistir(investigacionId, plan, ronda, estado.objetivo());
    }

    /**
     * Guarda un plan como tareas.
     */
    @Transactional
    public int persistir(long investigacionId, ResearchPlan plan, int ronda, String objetivo) {
        List<ResearchPlan.PlanTask> lineas = plan.tareas().size() > MAX_TAREAS_POR_PLAN
                ? plan.tareas().subList(0, MAX_TAREAS_POR_PLAN)
                : plan.tareas();

        ArrayNode tareasJson = objectMapper.createArrayNode();

        for (ResearchPlan.PlanTask linea : lineas) {
            // Los criterios se anteponen a la descripcion porque son la unica
            // parte que le dice al agente cuando puede cerrar la tarea.
            String descripcionCompleta = linea.criterios().isEmpty()
                    ? linea.descripcion()
                    : linea.descripcion() + "\n\nCriterio de cierre: "
                            + String.join(" | ", linea.criterios());

            long tareaId = tareas.crear(investigacionId, descripcionCompleta,
                    linea.tipoFuente().name(), linea.prioridad(), ronda);

            ObjectNode nodo = objectMapper.createObjectNode();
            nodo.put("tareaId", tareaId);
            nodo.put("descripcion", linea.descripcion());
            nodo.put("tipoFuente", linea.tipoFuente().name());
            nodo.put("prioridad", linea.prioridad());
            tareasJson.add(nodo);
        }

        if (lineas.size() < plan.tareas().size()) {
            log.warn("El plan de la investigacion {} tenia {} tareas y se guardaron {}: "
                            + "el resto superaba el maximo por ronda",
                    investigacionId, plan.tareas().size(), lineas.size());
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("ronda", ronda);
        payload.put("modelo", com.abegomez.research.llm.AgentRole.PLANNER.name());
        payload.set("tareas", tareasJson);
        manager.evento(investigacionId, ResearchEventType.PLAN_CREATED, payload);

        log.info("Investigacion {}: {} tareas creadas en la ronda {}",
                investigacionId, lineas.size(), ronda);
        return lineas.size();
    }

    /**
     * Estimacion del coste de un plan.
     *
     * <p>Solo la peticion: la respuesta del Planner ya se contabilizo en
     * {@link PlannerAgent} a traves del wrapper LLM, y estimarla otra vez seria
     * contarlo dos veces.
     */
    private long estimarTokens(ResearchPlan plan) {
        long caracteres = 0;
        for (ResearchPlan.PlanTask tarea : plan.tareas()) {
            caracteres += tarea.descripcion().length();
            for (String criterio : tarea.criterios()) {
                caracteres += criterio.length();
            }
        }
        // Relacion aproximada de caracteres por token en castellano.
        return Math.max(100, caracteres / 4);
    }

    /**
     * Siguiente tarea a ejecutar en la ronda.
     */
    public java.util.Optional<TaskRepository.TaskRow> siguienteTarea(long investigacionId, int ronda) {
        return tareas.siguientePendiente(investigacionId, ronda);
    }

    public List<TaskRepository.TaskRow> todas(long investigacionId) {
        return tareas.porInvestigacion(investigacionId);
    }
}
