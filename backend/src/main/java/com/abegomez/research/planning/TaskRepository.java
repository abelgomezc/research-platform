package com.abegomez.research.planning;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a la tabla de tareas de investigacion.
 */
@Repository
public class TaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Crea una tarea en estado PENDIENTE.
     */
    public long crear(long investigacionId, String descripcion, String tipoFuente,
                      int prioridad, int ronda) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tareas_investigacion
                    (investigacion_id, descripcion, tipo_fuente, prioridad, estado, ronda)
                VALUES (?, ?, ?, ?, 'PENDIENTE', ?)
                RETURNING id
                """, Long.class, investigacionId, descripcion, tipoFuente, prioridad, ronda);
        return id;
    }

    /**
     * Siguiente tarea pendiente de la ronda actual.
     *
     * <p>Se elige por prioridad y luego por id, no por orden de insercion: las
     * tareas mas prioritarias deben ejecutarse primero aunque se hayan creado
     * despues.
     */
    /**
     * Siguiente tarea pendiente de la ronda indicada.
     *
     * <p>Se elige por prioridad y luego por id, no por orden de insercion: las
     * tareas mas prioritarias deben ejecutarse primero aunque se hayan creado
     * despues.
     */
    public Optional<TaskRow> siguientePendiente(long investigacionId, int ronda) {        List<TaskRow> tareas = jdbcTemplate.query("""
                SELECT id, descripcion, tipo_fuente, prioridad, estado, ronda,
                       intentos, COALESCE(resumen_resultado, '')
                FROM tareas_investigacion
                WHERE investigacion_id = ? AND ronda = ? AND estado = 'PENDIENTE'
                ORDER BY prioridad DESC, id
                LIMIT 1
                """, (rs, rowNum) -> leer(rs), investigacionId, ronda);
        return tareas.stream().findFirst();
    }

    public Optional<TaskRow> find(long tareaId) {
        List<TaskRow> tareas = jdbcTemplate.query("""
                SELECT id, descripcion, tipo_fuente, prioridad, estado, ronda,
                       intentos, COALESCE(resumen_resultado, '')
                FROM tareas_investigacion
                WHERE id = ?
                """, (rs, rowNum) -> leer(rs), tareaId);
        return tareas.stream().findFirst();
    }

    public List<TaskRow> porInvestigacion(long investigacionId) {
        return jdbcTemplate.query("""
                SELECT id, descripcion, tipo_fuente, prioridad, estado, ronda,
                       intentos, COALESCE(resumen_resultado, '')
                FROM tareas_investigacion
                WHERE investigacion_id = ?
                ORDER BY ronda, prioridad DESC, id
                """, (rs, rowNum) -> leer(rs), investigacionId);
    }

    public int contarPendientes(long investigacionId, int ronda) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tareas_investigacion
                WHERE investigacion_id = ? AND ronda = ? AND estado = 'PENDIENTE'
                """, Integer.class, investigacionId, ronda);
        return total == null ? 0 : total;
    }

    /**
     * Marca la tarea como EN_CURSO.
     *
     * <p>La condicion {@code estado = 'PENDIENTE'} evita que dos hebrastomaran la
     * misma tarea. Si no se actualiza ninguna fila, otra ya se llevo la tarea y
     * quien llama debe reintentar con la siguiente.
     */
    public boolean marcarEnCurso(long tareaId) {
        return jdbcTemplate.update("""
                UPDATE tareas_investigacion
                SET estado = 'EN_CURSO', actualizado_en = NOW()
                WHERE id = ? AND estado = 'PENDIENTE'
                """, tareaId) > 0;
    }

    /**
     * Devuelve la tarea a PENDIENTE para poder reintentarla en otra ronda.
     *
     * <p>Solo desde EN_CURSO: una tarea ya cerrada no se reabre, porque reabrirla
     * permitiria repetir trabajo ya verificado.
     */
    public boolean devolverAPendiente(long tareaId) {
        return jdbcTemplate.update("""
                UPDATE tareas_investigacion
                SET estado = 'PENDIENTE', actualizado_en = NOW()
                WHERE id = ? AND estado = 'EN_CURSO'
                """, tareaId) > 0;
    }

    /**
     * Marca la tarea como fallida de forma irrecuperable.
     */
    public void marcarFallida(long tareaId, String motivo) {
        jdbcTemplate.update("""
                UPDATE tareas_investigacion
                SET estado = 'FALLIDA',
                    resumen_resultado = COALESCE(?, resumen_resultado),
                    actualizado_en = NOW()
                WHERE id = ?
                """, motivo, tareaId);
    }

    private TaskRow leer(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TaskRow(
                rs.getLong("id"),
                rs.getString("descripcion"),
                rs.getString("tipo_fuente"),
                rs.getInt("prioridad"),
                rs.getString("estado"),
                rs.getInt("ronda"),
                rs.getInt("intentos"),
                rs.getString("resumen_resultado"));
    }

    /**
     * @param intentos        veces que se ha intentado
     * @param resumenResultado que se obtuvo, si se completo
     */
    public record TaskRow(long id, String descripcion, String tipoFuente, int prioridad,
                          String estado, int ronda, int intentos, String resumenResultado) {

        public boolean pendiente() {
            return "PENDIENTE".equals(estado);
        }

        public boolean enCurso() {
            return "EN_CURSO".equals(estado);
        }

        public boolean cerrada() {
            return "COMPLETADA".equals(estado) || "DESCARTADA".equals(estado)
                    || "FALLIDA".equals(estado);
        }
    }
}
