package com.abegomez.research.research;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.abegomez.research.research.ResearchEventRepository.ResearchEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Stream de progreso de una investigacion.
 *
 * <p>El stream se apoya en la tabla de eventos, no en un bus en memoria. Un
 * cliente que se conecta tarde, o que reconecta tras una caida, recibe lo que se
 * emitio mientras no estaba mirando en vez de un hueco silencioso. Es la misma
 * razon por la que los eventos se persisten en auditoria.
 *
 * <p>Cada evento lleva su id y el cliente lo reenvia como {@code Last-Event-ID}.
 * Esa es la pieza que convierte un stream de "ver en vivo" en algo reanudable.
 */
@Service
public class ResearchEventStreamService {

    private static final Logger log = LoggerFactory.getLogger(ResearchEventStreamService.class);

    /** Timeout del emitter: reconecta el cliente si no hay actividad. */
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    /** Cada cuanto se comprueba si hay eventos nuevos. */
    private static final long INTERVALO_MS = 500;

    /** Eventos enviados por tanda. */
    private static final int LOTE = 100;

    private final ResearchEventRepository repositorio;
    private final CancellationRegistry cancelaciones;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public ResearchEventStreamService(ResearchEventRepository repositorio,
                                      CancellationRegistry cancelaciones,
                                      org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.repositorio = repositorio;
        this.cancelaciones = cancelaciones;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Abre un stream de la linea de tiempo de una investigacion.
     *
     * @param investigacionId investigacion a seguir
     * @param desdeId         ultimo evento ya recibido; 0 para empezar desde el principio
     */
    public SseEmitter abrir(long investigacionId, long desdeId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        AtomicBoolean cerrado = new AtomicBoolean(false);
        long cursor = desdeId;

        Runnable bomba = () -> bombear(investigacionId, emitter, cerrado, cursor);

        // Hilo dedicado por stream: uno por consumidor SSE, no por evento. Es
        // aceptable porque un stream abierto es un recurso de cliente y su
        // numero esta acotado por el numero de pestanas abiertas.
        Thread hilo = new Thread(bomba, "sse-" + investigacionId);
        // Daemon: si la aplicacion baja, ningun stream la retiene.
        hilo.setDaemon(true);
        hilo.start();

        emitter.onCompletion(() -> cerrado.set(true));
        emitter.onTimeout(() -> cerrar(emitter, cerrado));
        emitter.onError(ex -> cerrado.set(true));

        return emitter;
    }

    /**
     * Bucle de envio. Consulta la tabla de eventos y avanza el cursor.
     */
    private void bombear(long investigacionId, SseEmitter emitter,
                         AtomicBoolean cerrado, long cursorInicial) {
        long cursor = cursorInicial;

        try {
            while (!cerrado.get()) {
                List<ResearchEvent> eventos = repositorio.desde(investigacionId, cursor, LOTE);

                for (ResearchEvent evento : eventos) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(evento.id()))
                            .name(evento.tipo().name())
                            .data(evento.payload()));
                    cursor = evento.id();
                }

                if (cancelaciones.estaCancelada(investigacionId) || estaTerminada(investigacionId)) {
                    // Se da un ultimo margen para que el cliente reciba lo que
                    // acaba de registrarse antes del estado final.
                    if (eventos.isEmpty()) {
                        emitter.send(SseEmitter.event()
                                .name("estado")
                                .data(estadoActual(investigacionId)));
                        cerrar(emitter, cerrado);
                        return;
                    }
                }
                else {
                    // Latido: mantiene viva la conexion a traves de proxies que
                    // cierran conexiones ociosas.
                    emitter.send(SseEmitter.event().comment("latido"));
                }

                Thread.sleep(INTERVALO_MS);
            }
        }
        catch (IOException ex) {
            // El cliente se fue: lo normal al cerrar una pestana.
            log.debug("Stream cerrado para la investigacion {}", investigacionId);
            cerrar(emitter, cerrado);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cerrar(emitter, cerrado);
        }
        catch (IllegalStateException ex) {
            // El emitter ya se completo por el timeout del servidor.
            log.debug("Stream ya cerrado para la investigacion {}", investigacionId);
            cerrar(emitter, cerrado);
        }
    }

    private void cerrar(SseEmitter emitter, AtomicBoolean cerrado) {
        if (cerrado.compareAndSet(false, true)) {
            try {
                emitter.complete();
            }
            catch (RuntimeException ex) {
                // El emitter ya estaba cerrado: no hay nada que hacer.
            }
        }
    }

    /**
     * Si la investigacion ya llego a un estado terminal y no enviara mas eventos.
     *
     * <p>Se consulta la tabla de investigaciones y no la de eventos: el estado es
     * la fuente de verdad, y adivinarlo a partir de los eventos daria por terminada
     * una investigacion recien creada que aun no ha registrado ninguno.
     */
    private boolean estaTerminada(long investigacionId) {
        List<String> estados = jdbcTemplate.queryForList(
                "SELECT estado FROM investigaciones WHERE id = ?", String.class, investigacionId);

        if (estados.isEmpty()) {
            return true;
        }

        try {
            return ResearchState.valueOf(estados.get(0)).isTerminal()
                    || ResearchState.valueOf(estados.get(0)).isResumable();
        }
        catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String estadoActual(long investigacionId) {
        List<String> estados = jdbcTemplate.queryForList(
                "SELECT estado FROM investigaciones WHERE id = ?", String.class, investigacionId);

        String estado = estados.isEmpty() ? "DESCONOCIDO" : estados.get(0);

        return "{\"investigacionId\":" + investigacionId
                + ",\"estado\":\"" + estado
                + "\",\"ultimoEvento\":" + repositorio.ultimoId(investigacionId) + "}";
    }
}
