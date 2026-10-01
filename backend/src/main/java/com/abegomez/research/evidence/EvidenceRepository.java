package com.abegomez.research.evidence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a evidencias, fuentes y contradicciones.
 */
@Repository
public class EvidenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public EvidenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Evidencias de una investigacion pendientes de verificar.
     */
    public List<EvidenciaRow> pendientesDeVerificar(long investigacionId, int limite) {
        return jdbcTemplate.query("""
                SELECT e.id, e.afirmacion, e.cita_textual, e.estado_verificacion,
                       e.confianza, f.tipo AS fuente_tipo, f.referencia, f.texto_extraido
                FROM evidencias e
                JOIN fuentes f ON f.id = e.fuente_id
                WHERE e.investigacion_id = ?
                  AND e.estado_verificacion = 'PENDIENTE'
                ORDER BY e.id
                LIMIT ?
                """, (rs, rowNum) -> new EvidenciaRow(
                        rs.getLong("id"),
                        rs.getString("afirmacion"),
                        rs.getString("cita_textual"),
                        VerificationStatus.valueOf(rs.getString("estado_verificacion")),
                        nullableDouble(rs, "confianza"),
                        rs.getString("fuente_tipo"),
                        rs.getString("referencia"),
                        rs.getString("texto_extraido")),
                investigacionId, Math.max(1, limite));
    }

    /**
     * Guarda el veredicto de una evidencia.
     */
    public void registrarVeredicto(long evidenciaId, VerificationStatus estado,
                                   Double confianza, String justificacion) {
        jdbcTemplate.update("""
                UPDATE evidencias
                SET estado_verificacion = ?,
                    confianza = COALESCE(?, confianza),
                    justificacion = ?
                WHERE id = ?
                """, estado.name(), confianza, justificacion, evidenciaId);
    }

    /**
     * Actualiza el texto extraido de una fuente.
     *
     * <p>Necesario porque una fuente creada por {@code save_evidence} puede no
     * tener el texto completo: entonces la verificacion determinista no tendria
     * contra que comparar y solo podria usar la capa semantica.
     */
    public void actualizarTextoFuente(long fuenteId, String texto) {
        jdbcTemplate.update("UPDATE fuentes SET texto_extraido = ? WHERE id = ?", texto, fuenteId);
    }

    public Optional<FuenteRow> fuente(long fuenteId) {
        List<FuenteRow> fuentes = jdbcTemplate.query("""
                SELECT id, investigacion_id, tipo, referencia, titulo, texto_extraido
                FROM fuentes WHERE id = ?
                """, (rs, rowNum) -> new FuenteRow(
                        rs.getLong("id"),
                        rs.getLong("investigacion_id"),
                        rs.getString("tipo"),
                        rs.getString("referencia"),
                        rs.getString("titulo"),
                        rs.getString("texto_extraido")),
                fuenteId);
        return fuentes.stream().findFirst();
    }

    /**
     * Resuelve el texto de una fuente contra el que comparar la cita.
     *
     * <p>Para una fuente de documento busca el documento referenciado; para una
     * web usa el texto ya extraido. Si no hay texto disponible, se devuelve
     * vacio y la primera capa no puede pronunciarse, que es un resultado
     * distinto de "la cita es falsa".
     */
    public String textoDeFuente(FuenteRow fuente) {
        if (fuente.textoExtraido() != null && !fuente.textoExtraido().isBlank()) {
            return fuente.textoExtraido();
        }
        return "";
    }

    /**
     * Evidencias verificadas de una investigacion, para el informe.
     */
    public List<EvidenciaRow> verificadas(long investigacionId) {
        return jdbcTemplate.query("""
                SELECT e.id, e.afirmacion, e.cita_textual, e.estado_verificacion,
                       e.confianza, f.tipo AS fuente_tipo, f.referencia, f.texto_extraido
                FROM evidencias e
                JOIN fuentes f ON f.id = e.fuente_id
                WHERE e.investigacion_id = ? AND e.estado_verificacion = 'VERIFICADA'
                ORDER BY e.id
                """, (rs, rowNum) -> new EvidenciaRow(
                        rs.getLong("id"),
                        rs.getString("afirmacion"),
                        rs.getString("cita_textual"),
                        VerificationStatus.valueOf(rs.getString("estado_verificacion")),
                        nullableDouble(rs, "confianza"),
                        rs.getString("fuente_tipo"),
                        rs.getString("referencia"),
                        rs.getString("texto_extraido")),
                investigacionId);
    }

    /**
     * Guarda una contradiccion entre dos evidencias.
     *
     * <p>Se ignora si ya existe la misma pareja: la verificacion recorre varias
     * rondas y registrarla cada vez inflaria el recuento.
     */
    public void registrarContradiccion(long investigacionId, long evidenciaA, long evidenciaB,
                                       String descripcion) {
        Integer existe = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM contradicciones
                WHERE investigacion_id = ? AND evidencia_a_id = ? AND evidencia_b_id = ?
                """, Integer.class, investigacionId, evidenciaA, evidenciaB);

        if (existe != null && existe > 0) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO contradicciones (investigacion_id, evidencia_a_id, evidencia_b_id, descripcion)
                VALUES (?, ?, ?, ?)
                """, investigacionId, evidenciaA, evidenciaB, descripcion);
    }

    public List<ContradiccionRow> contradicciones(long investigacionId) {
        return jdbcTemplate.query("""
                SELECT id, evidencia_a_id, evidencia_b_id, descripcion, resuelta
                FROM contradicciones
                WHERE investigacion_id = ?
                ORDER BY id
                """, (rs, rowNum) -> new ContradiccionRow(
                        rs.getLong("id"),
                        rs.getLong("evidencia_a_id"),
                        rs.getLong("evidencia_b_id"),
                        rs.getString("descripcion"),
                        rs.getBoolean("resuelta")),
                investigacionId);
    }

    /**
     * Resumen del estado de verificacion, para el panel y la evaluacion.
     */
    public VerificationSummary resumen(long investigacionId) {
        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*) AS total,
                    COUNT(*) FILTER (WHERE estado_verificacion = 'VERIFICADA') AS verificadas,
                    COUNT(*) FILTER (WHERE estado_verificacion = 'NO_VERIFICADA') AS no_verificadas,
                    COUNT(*) FILTER (WHERE estado_verificacion = 'PARCIAL') AS parciales,
                    COUNT(*) FILTER (WHERE estado_verificacion = 'PENDIENTE') AS pendientes
                FROM evidencias WHERE investigacion_id = ?
                """, (rs, rowNum) -> new VerificationSummary(
                        rs.getLong("total"),
                        rs.getLong("verificadas"),
                        rs.getLong("no_verificadas"),
                        rs.getLong("parciales"),
                        rs.getLong("pendientes")),
                investigacionId);
    }

    private Double nullableDouble(java.sql.ResultSet rs, String columna) throws java.sql.SQLException {
        double valor = rs.getDouble(columna);
        return rs.wasNull() ? null : valor;
    }

    /**
     * @param confianza   confianza declarada por el agente, si la dio
     * @param textoExtraido texto de la fuente, si se guardo
     */
    public record EvidenciaRow(long id, String afirmacion, String citaTextual,
                               VerificationStatus estado, Double confianza,
                               String fuenteTipo, String referencia, String textoExtraido) {
    }

    public record FuenteRow(long id, long investigacionId, String tipo, String referencia,
                            String titulo, String textoExtraido) {
    }

    public record ContradiccionRow(long id, long evidenciaAId, long evidenciaBId,
                                   String descripcion, boolean resuelta) {
    }

    /**
     * Recuento por estado de verificacion.
     *
     * @param total         evidencias registradas
     * @param tasaVerificada fraccion verificada sobre el total, 0-1
     */
    public record VerificationSummary(long total, long verificadas, long noVerificadas,
                                      long parciales, long pendientes) {

        public double tasaVerificada() {
            return total == 0 ? 0 : (double) verificadas / (double) total;
        }
    }
}
