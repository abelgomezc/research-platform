package com.abegomez.research.report;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a la tabla de informes.
 */
@Repository
public class ReportRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ReportRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Guarda una version nueva del informe.
     *
     * <p>La version se calcula a partir de la maxima existente, de modo que cada
     * reescritura queda registrada. Un informe corregido no borra el anterior:
     * si el Reviewer pide cambios, interesa poder comparar.
     */
    public long guardarVersion(long investigacionId, String contenido, List<String> advertencias) {
        Integer maxima = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM informes WHERE investigacion_id = ?",
                Integer.class, investigacionId);

        int version = (maxima == null ? 0 : maxima) + 1;

        return jdbcTemplate.queryForObject("""
                INSERT INTO informes (investigacion_id, version, contenido_markdown, advertencias_json)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, investigacionId, version, contenido, serializar(advertencias));
    }

    public void marcarEstado(long informeId, String estado) {
        jdbcTemplate.update("UPDATE informes SET estado_revision = ? WHERE id = ?", estado, informeId);
    }

    /**
     * Ultima version del informe.
     */
    public java.util.Optional<InformeRow> ultimo(long investigacionId) {
        List<InformeRow> informes = jdbcTemplate.query("""
                SELECT id, version, contenido_markdown, estado_revision, advertencias_json
                FROM informes
                WHERE investigacion_id = ?
                ORDER BY version DESC
                LIMIT 1
                """, (rs, rowNum) -> new InformeRow(
                        rs.getLong("id"),
                        rs.getInt("version"),
                        rs.getString("contenido_markdown"),
                        rs.getString("estado_revision"),
                        leerAdvertencias(rs.getString("advertencias_json"))),
                investigacionId);
        return informes.stream().findFirst();
    }

    public List<InformeRow> versiones(long investigacionId) {
        return jdbcTemplate.query("""
                SELECT id, version, contenido_markdown, estado_revision, advertencias_json
                FROM informes
                WHERE investigacion_id = ?
                ORDER BY version
                """, (rs, rowNum) -> new InformeRow(
                        rs.getLong("id"),
                        rs.getInt("version"),
                        rs.getString("contenido_markdown"),
                        rs.getString("estado_revision"),
                        leerAdvertencias(rs.getString("advertencias_json"))),
                investigacionId);
    }

    private String serializar(List<String> advertencias) {
        try {
            return advertencias == null || advertencias.isEmpty()
                    ? null
                    : objectMapper.writeValueAsString(advertencias);
        }
        catch (Exception ex) {
            return null;
        }
    }

    private List<String> leerAdvertencias(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        catch (Exception ex) {
            return List.of();
        }
    }

    /**
     * @param version        numero de version, empezando en 1
     * @param estadoRevision BORRADOR, EN_REVISION o APROBADO
     */
    public record InformeRow(long id, int version, String contenidoMarkdown,
                             String estadoRevision, List<String> advertencias) {
    }
}
