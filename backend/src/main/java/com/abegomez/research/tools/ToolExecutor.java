package com.abegomez.research.tools;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.common.exception.BusinessException;
import com.abegomez.research.tools.observability.ToolMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Ejecutor de herramientas.
 *
 * <p>Aqui viven las tres garantias del framework de tools:
 *
 * <ol>
 *   <li><b>Timeout.</b> Una tool que se cuelga no bloquea la investigacion: se
 *       interrumpe y la tarea continua con otras fuentes.</li>
 *   <li><b>Reintentos con backoff acotado.</b> Solo para fallos transitorios.</li>
 *   <li><b>Registro.</b> Cada ejecucion queda en {@code ejecuciones_herramienta}.</li>
 * </ol>
 *
 * <p>Ningun fallo de tool propaga la excepcion al orquestador. El error vuelve
 * como un {@link ToolResult} con {@code exitoso=false} y un mensaje legible, que
 * es lo que el agente necesita para decidir que hacer despues.
 */
@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    /**
     * Tamano maximo del resumen guardado. Un resultado enorme infla la tabla sin
     * aportar nada: lo relevante ya quedo en la evidencia.
     */
    private static final int MAX_RESUMEN_CHARS = 4000;

    private static final int MAX_BACKOFF_MS = 5000;

    private static final int BACKOFF_BASE_MS = 250;

    private final ToolRegistry registry;
    private final ToolExecutionLogRepository logRepository;
    private final ToolMetrics metrics;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final Sleeper sleeper;

    @Autowired
    public ToolExecutor(ToolRegistry registry,
                        ToolExecutionLogRepository logRepository,
                        ToolMetrics metrics,
                        ObjectMapper objectMapper) {
        this(registry, logRepository, metrics, objectMapper, Thread::sleep);
    }

    ToolExecutor(ToolRegistry registry,
                 ToolExecutionLogRepository logRepository,
                 ToolMetrics metrics,
                 ObjectMapper objectMapper,
                 Sleeper sleeper) {
        this.registry = registry;
        this.logRepository = logRepository;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.sleeper = sleeper;
        // Hilos virtuales: las tools de una tarea se ejecutan en paralelo y una
        // llamada lenta a la red no debe ocupar un hilo de plataforma.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Ejecuta una tool para un agente, si ese agente tiene permiso.
     */
    public ToolResult ejecutar(String agente, String nombreTool, ToolContext context, JsonNode parametros) {
        Optional<ToolResult> rechazado = verificarPermiso(agente, nombreTool);
        if (rechazado.isPresent()) {
            ToolResult resultado = rechazado.get();
            registrar(context, nombreTool, parametros, resultado, EstadoEjecucion.FALLIDA);
            return resultado;
        }

        ResearchTool tool = registry.find(nombreTool).orElseThrow();
        ToolDefinition definition = tool.definition();

        long inicio = System.nanoTime();
        int intentos = 0;
        Ejecucion ultima = null;

        for (int intento = 1; intento <= definition.retries() + 1; intento++) {
            intentos = intento;
            ultima = ejecutarConTimeout(tool, context, parametros);

            if (ultima.resultado().exitoso()) {
                break;
            }
            boolean quedanReintentos = intento <= definition.retries();
            if (quedanReintentos && esReintentable(ultima.resultado().error())) {
                log.warn("Tool fallo y es reintentable: tool={}, intento={}/{}, error={}",
                        nombreTool, intento, definition.retries() + 1, ultima.resultado().error());
                dormir(backoffMs(intento));
            }
            else {
                break;
            }
        }

        long duracionMs = (System.nanoTime() - inicio) / 1_000_000L;
        ToolResult contenido = ultima.resultado();
        ToolResult resultado = new ToolResult(contenido.exitoso(), contenido.contenido(),
                contenido.error(), Duration.ofMillis(duracionMs), intentos);

        registrar(context, nombreTool, parametros, resultado, ultima.estado());
        return resultado;
    }

    private Optional<ToolResult> verificarPermiso(String agente, String nombreTool) {
        if (!registry.existe(nombreTool)) {
            return Optional.of(ToolResult.fallo(
                    "La herramienta '" + nombreTool + "' no existe. Disponibles: " + registry.names(),
                    Duration.ZERO, 1));
        }
        if (registry.findPermitida(agente, nombreTool).isEmpty()) {
            return Optional.of(ToolResult.fallo(
                    "El agente '" + agente + "' no tiene permiso para usar '" + nombreTool + "'",
                    Duration.ZERO, 1));
        }
        return Optional.empty();
    }

    private Ejecucion ejecutarConTimeout(ResearchTool tool, ToolContext context, JsonNode parametros) {
        Duration timeout = tool.definition().timeout();
        CompletableFuture<ToolResult> future =
                CompletableFuture.supplyAsync(() -> tool.execute(context, parametros), executor);

        try {
            ToolResult resultado = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (resultado == null) {
                return new Ejecucion(ToolResult.fallo("La herramienta devolvio un resultado nulo",
                        Duration.ZERO, 1), EstadoEjecucion.FALLIDA);
            }
            return new Ejecucion(resultado,
                    resultado.exitoso() ? EstadoEjecucion.EXITOSA : EstadoEjecucion.FALLIDA);
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("Tool supero el timeout: tool={}, timeout={}", tool.definition().name(), timeout);
            return new Ejecucion(ToolResult.fallo(
                    "La herramienta '" + tool.definition().name() + "' supero el tiempo limite de "
                            + timeout.toMillis() + " ms. Intenta con otra fuente.",
                    timeout, 1), EstadoEjecucion.TIMEOUT);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "La ejecucion de la herramienta fue interrumpida", ex);
        }
        catch (ExecutionException ex) {
            Throwable causa = ex.getCause() == null ? ex : ex.getCause();
            log.warn("Tool lanzo una excepcion: tool={}, causa={}",
                    tool.definition().name(), causa.getMessage(), causa);
            return new Ejecucion(ToolResult.fallo(
                    "La herramienta '" + tool.definition().name() + "' fallo: " + causa.getMessage(),
                    Duration.ZERO, 1), EstadoEjecucion.FALLIDA);
        }
    }

    /**
     * Un error de negocio no tiene sentido reintentarlo: o la entrada es
     * invalida o la operacion no esta permitida. Solo se reintenta lo que puede
     * cambiar por ser reintentado.
     */
    private boolean esReintentable(String error) {
        if (error == null) {
            return false;
        }
        String normalizado = error.toLowerCase(Locale.ROOT);
        return normalizado.contains("tiempo limite")
                || normalizado.contains("timeout")
                || normalizado.contains("no se pudo contactar")
                || normalizado.contains("connection")
                || normalizado.contains("503")
                || normalizado.contains("502")
                || normalizado.contains("temporalmente");
    }

    private int backoffMs(int intento) {
        return Math.min(MAX_BACKOFF_MS, (int) (BACKOFF_BASE_MS * Math.pow(2, intento - 1)));
    }

    private void registrar(ToolContext context, String nombreTool, JsonNode parametros,
                           ToolResult resultado, EstadoEjecucion estado) {
        String resumen = truncar(resultado.exitoso() ? resultado.contenido() : resultado.error());
        String error = resultado.exitoso() ? null : truncar(resultado.error());
        long duracionMs = resultado.duracionMs().toMillis();

        try {
            logRepository.registrar(context.agentExecutionId(), nombreTool,
                    serializar(parametros), resumen, estado.name(), duracionMs, error);
        }
        catch (RuntimeException ex) {
            // Un fallo al registrar el log no debe tumbar la investigacion.
            log.warn("No se pudo registrar la ejecucion de la tool {}: {}",
                    nombreTool, ex.getMessage());
        }

        metrics.record(nombreTool, estado.name(), duracionMs);
    }

    private String serializar(JsonNode parametros) {
        if (parametros == null || parametros.isNull()) {
            return "{}";
        }
        try {
            return truncar(objectMapper.writeValueAsString(parametros));
        }
        catch (Exception ex) {
            return "{\"error\":\"no serializable\"}";
        }
    }

    private String truncar(String texto) {
        if (texto == null) {
            return null;
        }
        return texto.length() <= MAX_RESUMEN_CHARS
                ? texto
                : texto.substring(0, MAX_RESUMEN_CHARS) + "...[truncado]";
    }

    private void dormir(long millis) {
        try {
            sleeper.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void cerrar() {
        executor.shutdown();
    }

    /**
     * Estado persistido en {@code ejecuciones_herramienta}.
     */
    enum EstadoEjecucion {
        EXITOSA,
        FALLIDA,
        TIMEOUT
    }

    private record Ejecucion(ToolResult resultado, EstadoEjecucion estado) {
    }

    /**
     * Permite simular el backoff en pruebas sin dormir de verdad.
     */
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}