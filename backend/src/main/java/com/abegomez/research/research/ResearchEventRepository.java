package com.abegomez.research.research;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Linea de tiempo de una investigacion.
 *
 * <p>Los eventos son a la vez registro de auditoria y fuente del stream SSE. Se
 * guardan en la base de datos en lugar de solo publicarse en memoria, porque un
 * consumidor que se conecta tarde, o un cliente que reconecta tras una caida,
 * necesita poder recuperar lo que se perdió.
 */
@Repository
public class ResearchEventRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ResearchEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Registra un evento.
     *
     * <p>La serializacion de un payload que falla no debe tumbar la
     * investigacion: se guarda con el texto de error en lugar del payload. Perder
     * el detalle de un evento es malo, pero perder la investigacion por un
     * detalle de evento es peor.
     */
    public void registrar(long investigacionId, ResearchEventType tipo, JsonNode payload) {
        String json;
        try {
            json = payload == null ? null : objectMapper.writeValueAsString(payload);
        }
        catch (Exception ex) {
            json = "{\"error\":\"payload no serializable\"}";
        }

        jdbcTemplate.update("""
                INSERT INTO eventos_investigacion (investigacion_id, tipo, payload_json)
                VALUES (?, ?, ?)
                """, investigacionId, tipo.name(), json);
    }

    /**
     * Eventos posteriores a un id, para seguir el stream desde donde quedo.
     *
     * @param desdeId ultimo evento ya recibido; 0 para empezar desde el principio
     */
    public List<ResearchEvent> desde(long investigacionId, long desdeId, int limite) {
        return jdbcTemplate.query("""
                SELECT id, tipo, payload_json, creado_en
                FROM eventos_investigacion
                WHERE investigacion_id = ? AND id > ?
                ORDER BY id
                LIMIT ?
                """, (rs, rowNum) -> new ResearchEvent(
                        rs.getLong("id"),
                        ResearchEventType.valueOf(rs.getString("tipo")),
                        leer(rs.getString("payload_json")),
                        rs.getTimestamp("creado_en").toInstant()),
                investigacionId, desdeId, Math.max(1, limite));
    }

    public long ultimoId(long investigacionId) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM eventos_investigacion WHERE investigacion_id = ?",
                Long.class, investigacionId);
        return id == null ? 0L : id;
    }

    private JsonNode leer(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    /**
     * @param id        identificador monotono, para seguir el stream sin repetir
     * @param creadoEn  momento del evento
     */
    public record ResearchEvent(long id, ResearchEventType tipo, JsonNode payload, Instant creadoEn) {
    }
}
