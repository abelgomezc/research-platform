package com.abegomez.research.tools;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Registro de ejecuciones de herramienta en {@code ejecuciones_herramienta}.
 *
 * <p>Escribo con {@link JdbcTemplate} y no con JPA porque la escritura ocurre
 * desde hilos virtuales en paralelo y asi se evita arrastrar una sesion de
 * Hibernate a traves del contexto. Ademas, una tool que falla nunca debe dejar
 * una transaccion abierta: cada registro es su propia sentencia.
 */
@Repository
public class ToolExecutionLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public ToolExecutionLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Registra una ejecucion terminada.
     *
     * @param estado EN_CURSO, EXITOSA, FALLIDA o TIMEOUT
     */
    public void registrar(Long agentExecutionId, String herramienta, String parametrosJson,
                          String resultadoResumen, String estado, long duracionMs, String error) {
        if (agentExecutionId == null) {
            // Sin ejecucion de agente no hay donde colgar el registro. Ocurre
            // cuando una tool se invoca fuera de un agente, por ejemplo en una
            // prueba de diagnostico.
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO ejecuciones_herramienta
                    (ejecucion_agente_id, herramienta, parametros_json, resultado_resumen,
                     estado, duracion_ms, error)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                agentExecutionId, herramienta, parametrosJson, resultadoResumen,
                estado, duracionMs, error);
    }

    public List<ToolExecutionRow> listarPorAgentExecution(Long agentExecutionId) {
        return jdbcTemplate.query("""
                SELECT id, herramienta, parametros_json, resultado_resumen, estado, duracion_ms, error
                FROM ejecuciones_herramienta
                WHERE ejecucion_agente_id = ?
                ORDER BY id
                """, (rs, rowNum) -> new ToolExecutionRow(
                rs.getLong("id"),
                rs.getString("herramienta"),
                rs.getString("parametros_json"),
                rs.getString("resultado_resumen"),
                rs.getString("estado"),
                rs.getLong("duracion_ms"),
                rs.getString("error")),
                agentExecutionId);
    }

    /**
     * Porcentaje de ejecuciones exitosas, usado por la metrica de la evaluacion.
     */
    public double tasaDeExito(Long investigacionId) {
        Double tasa = jdbcTemplate.queryForObject("""
                SELECT CASE WHEN COUNT(*) = 0 THEN 0.0
                            ELSE SUM(CASE WHEN estado = 'EXITOSA' THEN 1 ELSE 0 END)::numeric / COUNT(*)
                       END
                FROM ejecuciones_herramienta e
                JOIN ejecuciones_agente a ON a.id = e.ejecucion_agente_id
                WHERE a.investigacion_id = ?
                """, Double.class, investigacionId);
        return tasa == null ? 0.0 : tasa;
    }

    public ToolExecutionRow findById(long id) {
        List<ToolExecutionRow> rows = jdbcTemplate.query("""
                SELECT id, herramienta, parametros_json, resultado_resumen, estado, duracion_ms, error
                FROM ejecuciones_herramienta WHERE id = ?
                """, (rs, rowNum) -> new ToolExecutionRow(
                rs.getLong("id"),
                rs.getString("herramienta"),
                rs.getString("parametros_json"),
                rs.getString("resultado_resumen"),
                rs.getString("estado"),
                rs.getLong("duracion_ms"),
                rs.getString("error")), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Fila de ejecucion de una herramienta.
     */
    public record ToolExecutionRow(Long id, String herramienta, String parametrosJson,
                                   String resultadoResumen, String estado, long duracionMs, String error) {
    }
}