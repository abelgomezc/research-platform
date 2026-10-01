package com.abegomez.research.research;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Senales de cancelacion por investigacion.
 *
 * <p>La cancelacion es cooperativa, no un {@code Thread.stop}: una tarea se
 * comprueba entre pasos y entre tools, no en mitad de una. Interrumpir una tool
 * a mitad dejaria una escritura a medias, y en este sistema las escrituras son
 * evidencia, que es justo lo que no puede quedar inconsistente.
 *
 * <p>Las senales viven en memoria y no en la base de datos porque su ciclo de
 * vida es el de la peticion. Si el proceso se reinicia, no hay ninguna
 * investigacion en ejecucion que cancelar, y las que quedaron INTERRUPTED se
 * reanudan por el endpoint de reanudacion, no por aqui.
 */
@Service
public class CancellationRegistry {

    private static final Logger log = LoggerFactory.getLogger(CancellationRegistry.class);

    private final Map<Long, AtomicBoolean> senales = new ConcurrentHashMap<>();

    /**
     * Marca una investigacion como cancelada.
     *
     * @return false si ya estaba cancelada o ya no esta en ejecucion
     */
    public boolean solicitar(long investigacionId) {
        AtomicBoolean senal = senales.computeIfAbsent(investigacionId,
                key -> new AtomicBoolean(false));

        boolean primeraVez = senal.compareAndSet(false, true);
        if (primeraVez) {
            log.info("Cancelacion solicitada para la investigacion {}", investigacionId);
        }
        return primeraVez;
    }

    /**
     * Si la investigacion tiene cancelacion pendiente.
     */
    public boolean estaCancelada(long investigacionId) {
        AtomicBoolean senal = senales.get(investigacionId);
        return senal != null && senal.get();
    }

    /**
     * Registra una investigacion como ejecutable.
     *
     * <p>Se llama al empezar. Limpia cualquier senal previa para que una
     * investigacion reanudada no arranque ya cancelada.
     */
    public void registrar(long investigacionId) {
        senales.put(investigacionId, new AtomicBoolean(false));
    }

    /**
     * Libera la senal al terminar la ejecucion.
     */
    public void limpiar(long investigacionId) {
        senales.remove(investigacionId);
    }

    /**
     * Lanza excepcion si hay cancelacion pendiente.
     *
     * <p>Se usa en los puntos del bucle donde no se puede simplemente dejar de
     * trabajar, por ejemplo antes de guardar un informe.
     */
    public void exigirNoCancelada(long investigacionId) {
        if (estaCancelada(investigacionId)) {
            throw new InvestigacionCanceladaException(investigacionId);
        }
    }

    /**
     * Se lanza cuando el usuario cancela la investigacion.
     *
     * <p>Se distingue de otros fallos para que el orquestador la trape y cierre
     * en CANCELLED, y no en FAILED: cancelar no es un error.
     */
    public static final class InvestigacionCanceladaException extends RuntimeException {

        private final long investigacionId;

        public InvestigacionCanceladaException(long investigacionId) {
            super("La investigacion " + investigacionId + " fue cancelada por el usuario");
            this.investigacionId = investigacionId;
        }

        public long investigacionId() {
            return investigacionId;
        }
    }
}
