package com.abegomez.research.research.api;

import java.util.List;

import com.abegomez.research.evidence.EvidenceRepository;
import com.abegomez.research.planning.TaskRepository;
import com.abegomez.research.report.ReportRepository;
import com.abegomez.research.research.CancellationRegistry;
import com.abegomez.research.research.CheckpointService;
import com.abegomez.research.research.ResearchEventRepository;
import com.abegomez.research.research.ResearchEventType;
import com.abegomez.research.research.ResearchManager;
import com.abegomez.research.research.ResearchOrchestrator;
import com.abegomez.research.research.ResearchRepository;
import com.abegomez.research.research.ResearchState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * API de investigaciones: crear, seguir, cancelar y reanudar.
 *
 * <p>La ejecucion es asincrona a proposito. Una investigacion consume minutos y
 * varias llamadas al modelo; si la peticion HTTP esperara, el cliente tendria que
 * mantener una conexion abierta durante todo el proceso y un corte de red
 * perderia el trabajo. Se devuelve un id y el progreso se sigue por SSE.
 */
@RestController
@RequestMapping("/api/research")
public class ResearchController {

    private final ResearchManager manager;
    private final ResearchRepository repository;
    private final ResearchOrchestrator orchestrator;
    private final ResearchEventRepository eventos;
    private final com.abegomez.research.research.ResearchEventStreamService stream;
    private final CancellationRegistry cancelaciones;
    private final CheckpointService checkpoints;
    private final TaskRepository tareas;
    private final ReportRepository informes;
    private final EvidenceRepository evidencias;

    public ResearchController(ResearchManager manager, ResearchRepository repository,
                              ResearchOrchestrator orchestrator, ResearchEventRepository eventos,
                              com.abegomez.research.research.ResearchEventStreamService stream,
                              CancellationRegistry cancelaciones, CheckpointService checkpoints,
                              TaskRepository tareas, ReportRepository informes,
                              EvidenceRepository evidencias) {
        this.manager = manager;
        this.repository = repository;
        this.orchestrator = orchestrator;
        this.eventos = eventos;
        this.stream = stream;
        this.cancelaciones = cancelaciones;
        this.checkpoints = checkpoints;
        this.tareas = tareas;
        this.informes = informes;
        this.evidencias = evidencias;
    }

    /**
     * Crea una investigacion y la lanza en segundo plano.
     */
    @PostMapping
    public ResponseEntity<ResearchCreatedResponse> crear(@Valid @RequestBody CreateResearchRequest request) {
        long id = manager.crear(request.objetivo(), request.presupuestoTokens(), request.maxRondas());

        // El orquestador corre en un hilo aparte: el endpoint responde de
        // inmediato con el id.
        Thread hilo = new Thread(() -> orchestrator.ejecutar(id), "research-" + id);
        hilo.setDaemon(true);
        hilo.start();

        return ResponseEntity.accepted()
                .body(new ResearchCreatedResponse(id, "CREATED", "La investigacion se ha iniciado"));
    }

    /**
     * Detalle de una investigacion.
     */
    @GetMapping("/{id}")
    public ResearchDetailResponse obtener(@PathVariable long id) {
        var estado = manager.obtener(id);

        return new ResearchDetailResponse(
                estado.id(),
                estado.objetivo(),
                estado.estado().name(),
                estado.rondaActual(),
                estado.maxRondas(),
                estado.presupuestoTokens(),
                estado.tokensConsumidos(),
                estado.motivoFallo(),
                evidencias.resumen(id),
                tareas.porInvestigacion(id).size(),
                informes.versiones(id).size());
    }

    /**
     * Listado de investigaciones, paginado.
     */
    @GetMapping
    public List<ResearchListItem> listar(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limite,
            @RequestParam(defaultValue = "0") @Min(0) int desplazamiento) {
        return repository.listar(limite, desplazamiento).stream()
                .map(estado -> new ResearchListItem(
                        estado.id(),
                        estado.objetivo(),
                        estado.estado().name(),
                        estado.rondaActual(),
                        estado.tokensConsumidos(),
                        estado.presupuestoTokens()))
                .toList();
    }

    /**
     * Stream de progreso.
     *
     * <p>Se acepta {@code Last-Event-ID} porque es lo que envia el navegador al
     * reconectar solo: si no se usara, un corte de red perderia todo el progreso
     * anterior.
     */
    @GetMapping(value = "/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable long id,
                             @RequestHeader(value = "Last-Event-ID", required = false) String ultimoEventoId) {
        manager.obtener(id);

        long desde = 0;
        if (ultimoEventoId != null && !ultimoEventoId.isBlank()) {
            try {
                desde = Long.parseLong(ultimoEventoId.trim());
            }
            catch (NumberFormatException ex) {
                // Un id mal formado se ignora y se empieza desde el principio:
                // es preferible reenviar de mas que perder el hilo.
                desde = 0;
            }
        }

        return stream.abrir(id, desde);
    }

    /**
     * Eventos离散os, para consultar el historial sin abrir un stream.
     */
    @GetMapping("/{id}/events")
    public List<ResearchEventRepository.ResearchEvent> listarEventos(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") long desdeId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limite) {
        manager.obtener(id);
        return eventos.desde(id, desdeId, limite);
    }

    /**
     * Cancela una investigacion en curso.
     *
     * <p>Devuelve 202 y no 200: la cancelacion es cooperativa y la investigacion
     * sigue viva hasta que el orquestador llega a un punto seguro.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelResponse> cancelar(@PathVariable long id) {
        var estado = manager.obtener(id);

        if (estado.estado().isTerminal()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new CancelResponse(id, estado.estado().name(),
                            "La investigacion ya termino en " + estado.estado()));
        }

        boolean aceptada = cancelaciones.solicitar(id);

        var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("motivo", "Cancelacion solicitada por el usuario");
        manager.evento(id, ResearchEventType.CANCELLATION_REQUESTED, payload);

        return ResponseEntity.accepted()
                .body(new CancelResponse(id, estado.estado().name(),
                        aceptada ? "Cancelacion en curso" : "La cancelacion ya estaba solicitada"));
    }

    /**
     * Guarda un checkpoint del estado actual.
     */
    @PostMapping("/{id}/checkpoint")
    public CheckpointService.Checkpoint checkpoint(@PathVariable long id) {
        manager.obtener(id);
        return checkpoints.guardar(id);
    }

    /**
     * Reanuda una investigacion interrumpida.
     */
    @PostMapping("/{id}/resume")
    public ResponseEntity<ResumeResponse> reanudar(@PathVariable long id) {
        var estado = manager.obtener(id);

        if (!estado.estado().isResumable()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ResumeResponse(id, estado.estado().name(), false,
                            "La investigacion esta en " + estado.estado()
                                    + " y no se puede reanudar"));
        }

        int devueltas = checkpoints.prepararReanudacion(id);

        manager.transicionar(id, ResearchState.RESEARCHING);

        Thread hilo = new Thread(() -> orchestrator.ejecutar(id), "research-resume-" + id);
        hilo.setDaemon(true);
        hilo.start();

        return ResponseEntity.accepted()
                .body(new ResumeResponse(id, estado.estado().name(), true,
                        devueltas + " tareas devueltas a la cola"));
    }

    /**
     * Informe de una investigacion.
     */
    @GetMapping("/{id}/report")
    public ResponseEntity<Object> informe(@PathVariable long id,
                                           @RequestParam(defaultValue = "false") boolean historico) {
        manager.obtener(id);

        if (historico) {
            return ResponseEntity.ok(informes.versiones(id));
        }

            var ultimo = informes.ultimo(id);
        if (ultimo.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("La investigacion todavia no tiene informe"));
        }
        return ResponseEntity.ok(ultimo.get());
    }

    /**
     * Solicitud de creacion.
     *
     * @param presupuestoTokens presupuesto total de tokens
     */
    public record CreateResearchRequest(
            @NotBlank @Size(max = 2000, message = "El objetivo no puede superar los 2000 caracteres")
            String objetivo,

            @Min(10000) @Max(2_000_000)
            long presupuestoTokens,

            @Min(1) @Max(5)
            int maxRondas) {

        public CreateResearchRequest {
            if (presupuestoTokens == 0) {
                presupuestoTokens = 200_000;
            }
            if (maxRondas == 0) {
                maxRondas = 3;
            }
        }
    }

    public record ResearchCreatedResponse(long id, String estado, String mensaje) {
    }

    public record ResearchListItem(long id, String objetivo, String estado, int ronda,
                                   long tokensConsumidos, long presupuestoTokens) {
    }

    public record ResearchDetailResponse(long id, String objetivo, String estado, int ronda,
                                         int maxRondas, long presupuestoTokens, long tokensConsumidos,
                                         String motivoFallo,
                                         EvidenceRepository.VerificationSummary verificacion,
                                         int totalTareas, int totalInformes) {
    }

    public record CancelResponse(long id, String estado, String mensaje) {
    }

    public record ResumeResponse(long id, String estadoAnterior, boolean reanudada, String mensaje) {
    }

    public record ErrorResponse(String mensaje) {
    }
}
