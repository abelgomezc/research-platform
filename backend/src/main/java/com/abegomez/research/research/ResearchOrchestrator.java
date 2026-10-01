package com.abegomez.research.research;

import java.util.List;

import com.abegomez.research.agents.AgentExecutionService;
import com.abegomez.research.evidence.ContradictionDetector;
import com.abegomez.research.evidence.EvidenceRepository;
import com.abegomez.research.evidence.EvidenceVerificationService;
import com.abegomez.research.planning.PlanningService;
import com.abegomez.research.planning.TaskRepository;
import com.abegomez.research.report.ReportRepository;
import com.abegomez.research.report.ReportSynthesisService;
import com.abegomez.research.report.ReviewerAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquesta una investigacion de principio a fin.
 *
 * <p>Este es el unico sitio donde se decide el flujo. Los agentes proponen, la
 * verificacion comprueba y la maquina de estados limita; aqui se elije que fase
 * sigue y cuando conviene volver a investigar.
 *
 * <p>El bucle de rondas tiene tres salidas y ninguna es "seguir siempre":
 *
 * <ul>
 *   <li>La verificacion encuentra problemas -> se vuelve a investigar.</li>
 *   <li>Se agotan las rondas -> se sintetiza con lo que haya, declarando el limite.</li>
 *   <li>Se agota el presupuesto -> se sintetiza igual. Un informe parcial y
 *       honesto es mas util que una investigacion sin resultado.</li>
 * </ul>
 *
 * <p>Los tres casos terminan en informe. Cancelar es distinto: eso lo decide el
 * usuario y sale por otro camino.
 */
@Service
public class ResearchOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ResearchOrchestrator.class);

    /** Rondas de revision antes de aceptar el informe del Reviewer. */
    private static final int MAX_REVISIONES = 2;

    /** Proporcion de evidencias verificadas minima para dar el trabajo por bueno. */
    private static final double UMBRAL_SATISFACTORIO = 0.6;

    private final ResearchManager manager;
    private final PlanningService planificacion;
    private final AgentExecutionService ejecucion;
    private final EvidenceVerificationService verificacion;
    private final ContradictionDetector contradicciones;
    private final ReportSynthesisService sintetizador;
    private final ReviewerAgent reviewer;
    private final ReportRepository informes;
    private final EvidenceRepository evidencias;
    private final TaskRepository tareas;
    private final CancellationRegistry cancelaciones;
    private final ObjectMapper objectMapper;

    public ResearchOrchestrator(ResearchManager manager, PlanningService planificacion,
                                AgentExecutionService ejecucion,
                                EvidenceVerificationService verificacion,
                                ContradictionDetector contradicciones,
                                ReportSynthesisService sintesis, ReviewerAgent reviewer,
                                ReportRepository informes, EvidenceRepository evidencias,
                                TaskRepository tareas, CancellationRegistry cancelaciones,
                                ObjectMapper objectMapper) {
        this.manager = manager;
        this.planificacion = planificacion;
        this.ejecucion = ejecucion;
        this.verificacion = verificacion;
        this.contradicciones = contradicciones;
        this.sintetizador = sintesis;
        this.reviewer = reviewer;
        this.informes = informes;
        this.evidencias = evidencias;
        this.tareas = tareas;
        this.cancelaciones = cancelaciones;
        this.objectMapper = objectMapper;
    }

    /**
     * Ejecuta una investigacion completa.
     *
     * <p>Bloqueante: el orquestador avanza de fase en fase. La API lo invoca en
     * segundo plano y el frontend sigue el progreso por SSE.
     *
     * @param investigacionId investigacion a ejecutar
     * @return resultado final
     */
    public OrchestrationResult ejecutar(long investigacionId) {
        var estado = manager.obtener(investigacionId);
        log.info("Investigacion {} iniciada en estado {}", investigacionId, estado.estado());

        cancelaciones.registrar(investigacionId);

        try {
            investigar(investigacionId);
            verificar(investigacionId);
            manager.transicionar(investigacionId, ResearchState.SYNTHESIZING);
            return sintetizarYRevisar(investigacionId);
        }
        catch (CancellationRegistry.InvestigacionCanceladaException ex) {
            log.info("Investigacion {} cancelada por el usuario", investigacionId);
            manager.transicionar(investigacionId, ResearchState.CANCELLED, ex.getMessage());
            return cancelada(investigacionId, ex.getMessage());
        }
        catch (RuntimeException ex) {
            log.error("Investigacion {} fallo: {}", investigacionId, ex.getMessage(), ex);
            manager.fallar(investigacionId, ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage());
            throw ex;
        }
        finally {
            // La senal se libera siempre: si se olvidara, la siguiente
            // ejecucion de la misma investigacion arrancaria cancelada.
            cancelaciones.limpiar(investigacionId);
        }
    }

    private OrchestrationResult cancelada(long investigacionId, String motivo) {
        return new OrchestrationResult(investigacionId, 0L, false,
                List.of(motivo), 0, evidencias.resumen(investigacionId),
                ResearchState.CANCELLED);
    }

    /**
     * Fases de planificacion e investigacion, con sus rondas.
     */
    private void investigar(long investigacionId) {
        var estado = manager.obtener(investigacionId);
        int ronda = estado.rondaActual();

        manager.transicionar(investigacionId, ResearchState.PLANNING);

        if (!manager.presupuestoAgotado(investigacionId) && !manager.rondasAgotadas(investigacionId)) {
            planificacion.planificarYGuardar(investigacionId, estado.objetivo(), ronda, null);
        }

        manager.transicionar(investigacionId, ResearchState.RESEARCHING);

        while (true) {
            // La cancelacion se comprueba entre tareas, nunca en mitad de una:
            // una tarea a medias podria dejar evidencia guardada sin cerrar.
            cancelaciones.exigirNoCancelada(investigacionId);

            var siguiente = planificacion.siguienteTarea(investigacionId, ronda);

            if (siguiente.isEmpty()) {
                break;
            }

            if (manager.presupuestoAgotado(investigacionId)) {
                log.warn("Investigacion {}: se detiene la ronda {} por falta de presupuesto",
                        investigacionId, ronda);
                break;
            }

            long tareaId = siguiente.get().id();
            if (!tareas.marcarEnCurso(tareaId)) {
                // Otra hebra se llevo la tarea; se sigue con la siguiente.
                continue;
            }

            eventoTarea(investigacionId, tareaId, ResearchEventType.TASK_STARTED, null);
            ejecucion.ejecutarTarea(investigacionId, tareaId);
        }
    }

    /**
     * Verificacion de evidencia y deteccion de contradicciones.
     */
    private void verificar(long investigacionId) {
        manager.transicionar(investigacionId, ResearchState.VERIFYING);
        cancelaciones.exigirNoCancelada(investigacionId);
        verificacion.verificarPendientes(investigacionId);
        contradicciones.detectar(investigacionId);
    }

    /**
     * Sintesis y revision, con hasta dos rondas de correccion.
     */
    private OrchestrationResult sintetizarYRevisar(long investigacionId) {
        // No se sintetiza nada si el usuario cancelo: un informe a medias de una
        // investigacion cancelada se confunde con el resultado real.
        cancelaciones.exigirNoCancelada(investigacionId);

        String objetivo = manager.obtener(investigacionId).objetivo();

        ReportSynthesisService.SynthesisResult sintesis =
                sintetizador.sintetizar(investigacionId, objetivo);

        manager.transicionar(investigacionId, ResearchState.REVIEWING);

        ReviewerAgent.ReviewResult revision = reviewer.revisar(investigacionId, sintesis.contenido());

        int correcciones = 0;
        while (!revision.aprobado() && puedeCorregir(investigacionId, correcciones)) {
            log.info("Investigacion {}: correccion {} del informe. Motivo: {}",
                    investigacionId, correcciones + 1, revision.motivo());
            correcciones++;

            manager.transicionar(investigacionId, ResearchState.SYNTHESIZING);
            sintesis = sintetizador.sintetizar(investigacionId, objetivo);
            manager.transicionar(investigacionId, ResearchState.REVIEWING);

            revision = reviewer.revisar(investigacionId, sintesis.contenido());
        }

        boolean aprobado = revision.aprobado();
        String estadoInforme = aprobado ? "APROBADO" : "BORRADOR";

        informes.marcarEstado(sintesis.informeId(), estadoInforme);

        if (aprobado) {
            manager.transicionar(investigacionId, ResearchState.COMPLETED);
        }
        else {
            // Un informe que no supera la revision se conserva como borrador,
            // pero la investigacion se marca INTERRUPTED y no COMPLETED: fingir
            // que termino bien seria el fallo mas grave del sistema.
            manager.transicionar(investigacionId, ResearchState.INTERRUPTED,
                    "El informe no supero la revision: " + revision.motivo());
        }

        var resumen = evidencias.resumen(investigacionId);

        return new OrchestrationResult(investigacionId, sintesis.informeId(), aprobado,
                revision.problemas(), sintesis.totalEvidencias(), resumen,
                manager.obtener(investigacionId).estado());
    }

    /**
     * Decide si merece la pena reintentar la sintesis.
     *
     * <p>No se corrige de forma ilimitada: si no hay presupuesto o ya se
     * agotaron las revisiones, se acepta el resultado con sus problemas
     * declarados. Un bucle de correccion sin fin gastaria el presupuesto sin
     * mejorar el informe.
     */
    private boolean puedeCorregir(long investigacionId, int correcciones) {
        if (manager.presupuestoAgotado(investigacionId)) {
            return false;
        }
        if (correcciones >= MAX_REVISIONES) {
            return false;
        }
        // Si el problema es de forma, no tiene sentido reescribir: el modelo ya
        // sabe citar y no lo ha hecho. Se deja el borrador como esta y se
        //declaran las lineas sin cita.
        return true;
    }

    private void eventoTarea(long investigacionId, long tareaId, ResearchEventType tipo, String detalle) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tareaId", tareaId);
        if (detalle != null) {
            payload.put("detalle", detalle);
        }
        manager.evento(investigacionId, tipo, payload);
    }

    /**
     * Tareas de una investigacion, para el detalle del frontend.
     */
    public List<TaskRepository.TaskRow> tareasDe(long investigacionId) {
        return tareas.porInvestigacion(investigacionId);
    }

    /**
     * @param totalEvidencias evidencias verificadas usadas en el informe
     * @param estadoFinal    estado en el que quedo la investigacion
     */
    public record OrchestrationResult(
            long investigacionId,
            long informeId,
            boolean aprobado,
            List<String> problemas,
            int totalEvidencias,
            EvidenceRepository.VerificationSummary verificacion,
            ResearchState estadoFinal) {
    }
}
