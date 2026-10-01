package com.abegomez.research.research;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkpoint y reanudacion de una investigacion.
 *
 * <p>No se guarda un estado completo y arbitrario: se guarda lo que el esquema ya
 * tiene, mas un marcador de hasta donde se llego. La investigacion es
 * <b>reconstruible</b> a partir de la base de datos, porque tareas, evidencias y
 * veredictos ya estan persistidos. Un snapshot que copiara todo el estado del
 * modelo seria mas comodo de reanudar y mucho mas fragil de mantener.
 *
 * <p>Lo que el checkpoint anade es una sola cosa: saber en que fase estaba y que
 * se estaba haciendo, para que la reanudacion no repita trabajo ya terminado.
 */
@Service
public class CheckpointService {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final ResearchManager manager;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CheckpointService(ResearchManager manager, JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.manager = manager;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Guarda el estado actual como checkpoint.
     *
     * @return id del evento de checkpoint
     */
    @Transactional
    public Checkpoint guardar(long investigacionId) {
        var estado = manager.obtener(investigacionId);

        var pendientes = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tareas_investigacion
                WHERE investigacion_id = ? AND estado = 'PENDIENTE'
                """, Integer.class, investigacionId);

        var enCurso = jdbcTemplate.queryForList("""
                SELECT id FROM tareas_investigacion
                WHERE investigacion_id = ? AND estado = 'EN_CURSO'
                """, Long.class, investigacionId);

        var resumenEvidencias = jdbcTemplate.queryForMap("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE estado_verificacion = 'VERIFICADA') AS verificadas
                FROM evidencias WHERE investigacion_id = ?
                """, investigacionId);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("estado", estado.estado().name());
        payload.put("ronda", estado.rondaActual());
        payload.put("maxRondas", estado.maxRondas());
        payload.put("tokensConsumidos", estado.tokensConsumidos());
        payload.put("presupuestoTokens", estado.presupuestoTokens());
        payload.put("tareasPendientes", pendientes == null ? 0 : pendientes);
        payload.set("tareasEnCurso", objectMapper.valueToTree(enCurso));
        payload.set("evidencias", objectMapper.valueToTree(resumenEvidencias));

        manager.evento(investigacionId, ResearchEventType.CHECKPOINT_SAVED, payload);

        log.info("Investigacion {}: checkpoint guardado en {} ({} tareas pendientes, "
                        + "{} en curso, {} tokens)",
                investigacionId, estado.estado(), pendientes == null ? 0 : pendientes,
                enCurso.size(), estado.tokensConsumidos());

        return new Checkpoint(investigacionId, estado.estado(), estado.rondaActual(),
                estado.rondaActual() >= estado.maxRondas(),
                estado.presupuestoAgotado(),
                pendientes == null ? 0 : pendientes,
                enCurso.size(),
                ((Number) resumenEvidencias.getOrDefault("verificadas", 0)).longValue(),
                ((Number) resumenEvidencias.getOrDefault("total", 0)).longValue());
    }

    /**
     * Comprueba si una investigacion puede reanudarse.
     */
    public boolean reanudable(long investigacionId) {
        var estado = manager.obtener(investigacionId);
        return estado.estado().isResumable();
    }

    /**
     * Devuelve las tareas que quedaron EN_CURSO a PENDIENTE.
     *
     * <p>Necesario tras una interrupcion: una tarea que estaba corriendo cuando
     * se cortó el proceso puede haber quedado a medias, y su estado EN_CURSO
     * haria que nunca volviera a la cola.
     *
     * @return numero de tareas devueltas
     */
    @Transactional
    public int prepararReanudacion(long investigacionId) {
        var estado = manager.obtener(investigacionId);

        if (!estado.estado().isResumable()) {
            throw new com.abegomez.research.common.exception.BusinessException(
                    com.abegomez.research.common.ErrorCode.CONFLICT,
                    "La investigacion " + investigacionId + " esta en " + estado.estado()
                            + " y no se puede reanudar");
        }

        int devueltas = jdbcTemplate.update("""
                UPDATE tareas_investigacion
                SET estado = 'PENDIENTE', actualizado_en = NOW()
                WHERE investigacion_id = ? AND estado = 'EN_CURSO'
                """, investigacionId);

        if (devueltas > 0) {
            log.info("Investigacion {}: {} tareas en curso devueltas a PENDIENTE",
                    investigacionId, devueltas);
        }

        // Los informes en borrador de una version anterior se conservan: si la
        // reanudacion falla, el trabajo anterior sigue consultable.
        List<Long> borradores = jdbcTemplate.queryForList("""
                SELECT id FROM informes
                WHERE investigacion_id = ? AND estado_revision = 'BORRADOR'
                """, Long.class, investigacionId);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("tareasDevueltas", devueltas);
        payload.put("desdeEstado", estado.estado().name());
        payload.put("informesPrevios", borradores.size());

        manager.evento(investigacionId, ResearchEventType.CHECKPOINT_RESUMED, payload);

        return devueltas;
    }

    /**
     * Ultimo checkpoint registrado de una investigacion.
     */
    public java.util.Optional<JsonNode> ultimo(long investigacionId) {
        List<JsonNode> eventos = jdbcTemplate.query("""
                SELECT payload_json FROM eventos_investigacion
                WHERE investigacion_id = ? AND tipo = 'CHECKPOINT_SAVED'
                ORDER BY id DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            String json = rs.getString("payload_json");
            try {
                return json == null ? objectMapper.createObjectNode() : objectMapper.readTree(json);
            }
            catch (Exception ex) {
                return objectMapper.createObjectNode();
            }
        }, investigacionId);

        return eventos.stream().findFirst();
    }

    /**
     * @param presupuestoAgotado si ya no queda presupuesto
     * @param verificadas        evidencias con veredicto de verificada
     */
    public record Checkpoint(long investigacionId, ResearchState estado, int ronda,
                             boolean rondasAgotadas, boolean presupuestoAgotado,
                             int tareasPendientes, int tareasEnCurso,
                             long verificadas, long totalEvidencias) {

        /**
         * Motivo por el que no se puede continuar, o null si se puede.
         */
        public String motivoBloqueo() {
            if (rondasAgotadas) {
                return "Se agotaron las rondas de investigacion";
            }
            if (presupuestoAgotado) {
                return "Se agoto el presupuesto de tokens";
            }
            if (tareasPendientes == 0 && tareasEnCurso == 0) {
                return "No quedan tareas pendientes";
            }
            return null;
        }

        public boolean puedeContinuar() {
            return motivoBloqueo() == null;
        }
    }
}
