package com.abegomez.research.evaluation;

import java.util.List;

import com.abegomez.research.evidence.EvidenceRepository;
import com.abegomez.research.report.ReportRepository;
import com.abegomez.research.tools.ToolExecutionLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Metricas de una investigacion, calculadas de lo que ocurrio de verdad.
 *
 * <p>Regla de esta fase: <b>no se inventa ningun resultado</b>. Cada numero sale
 * de una consulta sobre {@code investigaciones}, {@code evidencias},
 * {@code informes} o {@code ejecuciones_herramienta}. Si un dato no se puede
 * calcular, se devuelve null y el consumidor lo sabe, en lugar de devolver 0,
 * que es un valor real con significado distinto.
 */
@Service
public class MetricsService {

    private final JdbcTemplate jdbcTemplate;
    private final EvidenceRepository evidencias;
    private final ReportRepository informes;

    public MetricsService(JdbcTemplate jdbcTemplate, EvidenceRepository evidencias,
                          ReportRepository informes) {
        this.jdbcTemplate = jdbcTemplate;
        this.evidencias = evidencias;
        this.informes = informes;
    }

    /**
     * Metricas de una investigacion.
     */
    public InvestigationMetrics de(long investigacionId) {
        var estado = jdbcTemplate.queryForList("""
                SELECT estado, presupuesto_tokens, tokens_consumidos, ronda_actual, max_rondas
                FROM investigaciones WHERE id = ?
                """, investigacionId);

        if (estado.isEmpty()) {
            throw new IllegalArgumentException("No existe la investigacion " + investigacionId);
        }

        var fila = estado.get(0);

        String investigacionEstado = (String) fila.get("estado");
        long presupuesto = ((Number) fila.get("presupuesto_tokens")).longValue();
        long consumidos = ((Number) fila.get("tokens_consumidos")).longValue();
        int ronda = ((Number) fila.get("ronda_actual")).intValue();
        int maxRondas = ((Number) fila.get("max_rondas")).intValue();

        var verificacion = evidencias.resumen(investigacionId);

        Integer totalTareas = contar(investigacionId, "tareas_investigacion");
        Integer tareasCompletadas = contarConEstado(investigacionId, "tareas_investigacion", "COMPLETADA");
        Integer tareasDescartadas = contarConEstado(investigacionId, "tareas_investigacion", "DESCARTADA");

        Integer totalFuentes = contar(investigacionId, "fuentes");
        Integer totalEjecuciones = contar(investigacionId, "ejecuciones_agente");
        Integer ejecucionesFallidas = contarConEstado(investigacionId, "ejecuciones_agente", "FALLIDA");

        Integer totalLlamadasTool = contarLlamadasTool(investigacionId);
        Integer toolsFallidas = contarToolsFallidas(investigacionId);
        Integer toolsEnTimeout = contarToolsEnTimeout(investigacionId);

        var informe = informes.ultimo(investigacionId);

        // Se cuenta la cobertura de citas solo si hay informe con contenido.
        Integer citas = null;
        Integer lineasSinCita = null;
        if (informe.isPresent() && informe.get().contenidoMarkdown() != null) {
            var validacion = com.abegomez.research.report.ReportValidator.validar(
                    informe.get().contenidoMarkdown(),
                    evidencias.verificadas(investigacionId).stream()
                            .map(EvidenceRepository.EvidenciaRow::id)
                            .collect(java.util.stream.Collectors.toSet()));

            citas = validacion.totalCitas();
            lineasSinCita = validacion.lineasSinCita().size();
        }

        List<ToolUsage> usoTools = jdbcTemplate.query("""
                SELECT herramienta,
                       COUNT(*) AS llamadas,
                       COUNT(*) FILTER (WHERE estado = 'FALLIDA') AS fallidas,
                       AVG(duracion_ms)::bigint AS duracion_media
                FROM ejecuciones_herramienta eh
                JOIN ejecuciones_agente ea ON ea.id = eh.ejecucion_agente_id
                WHERE ea.investigacion_id = ?
                GROUP BY herramienta
                ORDER BY llamadas DESC
                """, (rs, rowNum) -> new ToolUsage(
                        rs.getString("herramienta"),
                        rs.getLong("llamadas"),
                        rs.getLong("fallidas"),
                        rs.getLong("duracion_media")),
                investigacionId);

        return new InvestigationMetrics(
                investigacionId,
                investigacionEstado,
                presupuesto,
                consumidos,
                presupuesto == 0 ? null : (double) consumidos / presupuesto,
                ronda,
                maxRondas,
                verificacion,
                totalTareas,
                tareasCompletadas,
                tareasDescartadas,
                totalFuentes,
                totalEjecuciones,
                ejecucionesFallidas,
                totalLlamadasTool,
                toolsFallidas,
                toolsEnTimeout,
                citas,
                lineasSinCita,
                informe.map(ReportRepository.InformeRow::estadoRevision).orElse(null),
                usoTools);
    }

    /**
     * Comparativa entre investigaciones, para el panel de evaluacion.
     */
    public List<AggregateMetrics> agregado(int limite) {
        return jdbcTemplate.query("""
                SELECT i.id, i.objetivo, i.estado, i.tokens_consumidos, i.presupuesto_tokens,
                       (SELECT COUNT(*) FROM evidencias e
                        WHERE e.investigacion_id = i.id) AS total_evidencias,
                       (SELECT COUNT(*) FROM evidencias e
                        WHERE e.investigacion_id = i.id AND e.estado_verificacion = 'VERIFICADA')
                           AS verificadas,
                       (SELECT COUNT(*) FROM tareas_investigacion t
                        WHERE t.investigacion_id = i.id) AS total_tareas,
                       (SELECT COUNT(*) FROM informes inf
                        WHERE inf.investigacion_id = i.id AND inf.estado_revision = 'APROBADO')
                           AS informes_aprobados
                FROM investigaciones i
                ORDER BY i.creado_en DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            long totalEvidencias = rs.getLong("total_evidencias");
            long verificadas = rs.getLong("verificadas");

            return new AggregateMetrics(
                    rs.getLong("id"),
                    rs.getString("objetivo"),
                    rs.getString("estado"),
                    rs.getLong("tokens_consumidos"),
                    rs.getLong("presupuesto_tokens"),
                    totalEvidencias,
                    verificadas,
                    totalEvidencias == 0 ? 0 : (double) verificadas / totalEvidencias,
                    rs.getLong("total_tareas"),
                    rs.getLong("informes_aprobados"));
        }, limite);
    }

    private Integer contar(long investigacionId, String tabla) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tabla + " WHERE investigacion_id = ?",
                Integer.class, investigacionId);
        return total;
    }

    private Integer contarConEstado(long investigacionId, String tabla, String estado) {
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tabla + " WHERE investigacion_id = ? AND estado = ?",
                Integer.class, investigacionId, estado);
        return total;
    }

    private Integer contarLlamadasTool(long investigacionId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ejecuciones_herramienta eh
                JOIN ejecuciones_agente ea ON ea.id = eh.ejecucion_agente_id
                WHERE ea.investigacion_id = ?
                """, Integer.class, investigacionId);
        return total;
    }

    private Integer contarToolsFallidas(long investigacionId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ejecuciones_herramienta eh
                JOIN ejecuciones_agente ea ON ea.id = eh.ejecucion_agente_id
                WHERE ea.investigacion_id = ? AND eh.estado = 'FALLIDA'
                """, Integer.class, investigacionId);
        return total;
    }

    private Integer contarToolsEnTimeout(long investigacionId) {
        Integer total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM ejecuciones_herramienta eh
                JOIN ejecuciones_agente ea ON ea.id = eh.ejecucion_agente_id
                WHERE ea.investigacion_id = ? AND eh.estado = 'TIMEOUT'
                """, Integer.class, investigacionId);
        return total;
    }

    /**
     * @param usoPresupuesto fraccion consumida, null si el presupuesto era 0
     * @param citas          referencias citadas en el informe, null si no hay informe
     * @param estadoInforme  estado de revision del ultimo informe, null si no hay
     */
    public record InvestigationMetrics(
            long investigacionId,
            String estado,
            long presupuestoTokens,
            long tokensConsumidos,
            Double usoPresupuesto,
            int ronda,
            int maxRondas,
            EvidenceRepository.VerificationSummary verificacion,
            Integer totalTareas,
            Integer tareasCompletadas,
            Integer tareasDescartadas,
            Integer totalFuentes,
            Integer totalEjecucionesAgente,
            Integer ejecucionesAgenteFallidas,
            Integer totalLlamadasTool,
            Integer toolsFallidas,
            Integer toolsEnTimeout,
            Integer citasInforme,
            Integer lineasSinCita,
            String estadoInforme,
            List<ToolUsage> usoPorTool) {

        /**
         * Tasa de descartes del agente.
         *
         * <p>Un valor alto no es automaticamente malo: una tarea que no encuentra
         * nada y se descarta es mejor que una que se completa con relleno. Es una
         * medida del plan, no del modelo.
         */
        public Double tasaDescarte() {
            if (totalTareas == null || totalTareas == 0) {
                return null;
            }
            if (tareasDescartadas == null) {
                return null;
            }
            return (double) tareasDescartadas / totalTareas;
        }

        public Double tasaFalloTools() {
            if (totalLlamadasTool == null || totalLlamadasTool == 0) {
                return null;
            }
            if (toolsFallidas == null) {
                return null;
            }
            return (double) toolsFallidas / totalLlamadasTool;
        }
    }

    /**
     * Uso de una herramienta concreta.
     *
     * @param duracionMediaMs media de duracion en milisegundos
     */
    public record ToolUsage(String herramienta, long llamadas, long fallidas, long duracionMediaMs) {
    }

    /**
     * Fila agregada del comparativo.
     *
     * @param tasaVerificacion fraccion de evidencias verificadas
     */
    public record AggregateMetrics(
            long id,
            String objetivo,
            String estado,
            long tokensConsumidos,
            long presupuestoTokens,
            long totalEvidencias,
            long verificadas,
            double tasaVerificacion,
            long totalTareas,
            long informesAprobados) {
    }
}
