package com.abegomez.research.tools.impl;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import com.abegomez.research.tools.ResearchTool;
import com.abegomez.research.tools.RiskLevel;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolDefinition;
import com.abegomez.research.tools.ToolPermission;
import com.abegomez.research.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Devuelve lo que ya se hizo en esta investigacion: tareas, evidencias y preguntas abiertas.
 *
 * <p>Sin esta tool cada agente arranca de cero y el Reviewer no tendria forma de
 * comprobar que el informe refleje lo realmente encontrado. El contexto previo es
 * lo que permite que la verificacion y la revision signifiquen algo.
 *
 * <p>Solo lee la investigacion del contexto. Un agente no puede inspeccionar la
 * de otro: por eso no hay ningun parametro de investigacion en el esquema.
 */
@Component
public class GetPreviousResearchTool implements ResearchTool {

    private static final int MAX_TAREAS = 40;
    private static final int MAX_EVIDENCIAS = 60;

    private final JdbcTemplate jdbcTemplate;

    public GetPreviousResearchTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.GET_PREVIOUS_RESEARCH,
                "Devuelve el estado actual de la investigacion: objetivo, tareas con su estado, "
                        + "evidencias registradas y preguntas abiertas. Usala al empezar tu trabajo "
                        + "para no repetir lo ya hecho y al revisar para comprobar cobertura.",
                """
                {
                  "type": "object",
                  "properties": {}
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "investigacion": {
                      "type": "object",
                      "properties": {
                        "id": {"type": "integer"},
                        "objetivo": {"type": "string"},
                        "estado": {"type": "string"},
                        "ronda": {"type": "integer"},
                        "presupuestoTokens": {"type": "integer"},
                        "tokensConsumidos": {"type": "integer"}
                      }
                    },
                    "tareas": {"type": "array", "items": {"type": "object"}},
                    "evidencias": {"type": "array", "items": {"type": "object"}},
                    "preguntasAbiertas": {"type": "array", "items": {"type": "string"}}
                  }
                }
                """)
                .permission(ToolPermission.READ)
                .riskLevel(RiskLevel.LOW)
                .timeout(Duration.ofSeconds(10))
                .retries(1)
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode parametros) {
        Long investigacionId = context.investigacionId();
        if (investigacionId == null) {
            return ToolResult.fallo("No hay investigacion activa", Duration.ZERO, 1);
        }

        StringBuilder salida = new StringBuilder();

        Cabecera cabecera = jdbcTemplate.queryForObject("""
                SELECT objetivo, estado, ronda_actual, presupuesto_tokens, tokens_consumidos
                FROM investigaciones WHERE id = ?
                """, (rs, rowNum) -> new Cabecera(
                        rs.getString("objetivo"),
                        rs.getString("estado"),
                        rs.getInt("ronda_actual"),
                        rs.getLong("presupuesto_tokens"),
                        rs.getLong("tokens_consumidos")),
                investigacionId);

        if (cabecera == null) {
            return ToolResult.fallo("La investigacion " + investigacionId + " no existe",
                    Duration.ZERO, 1);
        }

        salida.append("""
                Investigacion %d
                Objetivo: %s
                Estado: %s | Ronda %d | Tokens: %d de %d

                """.formatted(investigacionId, cabecera.objetivo(), cabecera.estado(),
                cabecera.ronda(), cabecera.tokensConsumidos(), cabecera.presupuestoTokens()));

        List<Tarea> tareas = jdbcTemplate.query("""
                SELECT id, estado, tipo_fuente, ronda, descripcion,
                       COALESCE(resumen_resultado, '') AS resumen
                FROM tareas_investigacion
                WHERE investigacion_id = ?
                ORDER BY ronda, id
                LIMIT ?
                """, (rs, rowNum) -> new Tarea(
                        rs.getLong("id"),
                        rs.getString("estado"),
                        rs.getString("tipo_fuente"),
                        rs.getInt("ronda"),
                        rs.getString("descripcion"),
                        rs.getString("resumen")),
                investigacionId, MAX_TAREAS);

        salida.append("Tareas (").append(tareas.size()).append("):\n");
        for (Tarea tarea : tareas) {
            salida.append("- [").append(tarea.estado()).append("] tarea ").append(tarea.id())
                    .append(" (").append(tarea.tipoFuente()).append(", ronda ").append(tarea.ronda())
                    .append("): ").append(tarea.descripcion());
            if (!tarea.resumen().isEmpty()) {
                salida.append("\n    Resumen: ").append(tarea.resumen());
            }
            salida.append("\n");
        }

        List<Evidencia> evidencias = jdbcTemplate.query("""
                SELECT e.id, e.afirmacion, e.cita_textual, e.estado_verificacion,
                       COALESCE(e.confianza::text, 'n/d') AS confianza,
                       s.tipo AS tipo_fuente, s.referencia
                FROM evidencias e
                JOIN fuentes s ON s.id = e.fuente_id
                WHERE e.investigacion_id = ?
                ORDER BY e.id
                LIMIT ?
                """, (rs, rowNum) -> new Evidencia(
                        rs.getLong("id"),
                        rs.getString("afirmacion"),
                        rs.getString("cita_textual"),
                        rs.getString("estado_verificacion"),
                        rs.getString("confianza"),
                        rs.getString("tipo_fuente"),
                        rs.getString("referencia")),
                investigacionId, MAX_EVIDENCIAS);

        salida.append("\nEvidencias (").append(evidencias.size()).append("):\n");
        for (Evidencia evidencia : evidencias) {
            salida.append("- ").append(evidencia.id()).append(" [").append(evidencia.estado())
                    .append(", confianza ").append(evidencia.confianza()).append("] ")
                    .append(evidencia.afirmacion())
                    .append("\n    Cita: \"").append(recortar(evidencia.citaTextual()))
                    .append("\"\n    Fuente: ").append(evidencia.tipoFuente()).append(" ")
                    .append(evidencia.referencia())
                    .append("\n");
        }

        List<String> preguntas = jdbcTemplate.queryForList("""
                SELECT pregunta FROM preguntas_abiertas
                WHERE investigacion_id = ? AND estado = 'ABIERTA'
                ORDER BY id
                """, String.class, investigacionId);

        salida.append("\nPreguntas abiertas (").append(preguntas.size()).append("):\n");
        if (preguntas.isEmpty()) {
            salida.append("- ninguna\n");
        }
        else {
            salida.append(preguntas.stream()
                    .map(pregunta -> "- " + pregunta)
                    .collect(Collectors.joining("\n")));
            salida.append("\n");
        }

        return ToolResult.ok(salida.toString(), Duration.ZERO, 1);
    }

    private String recortar(String texto) {
        return texto.length() <= 300 ? texto : texto.substring(0, 300) + "...[truncado]";
    }

    private record Cabecera(String objetivo, String estado, int ronda,
                            long presupuestoTokens, long tokensConsumidos) {
    }

    private record Tarea(long id, String estado, String tipoFuente, int ronda,
                         String descripcion, String resumen) {
    }

    private record Evidencia(long id, String afirmacion, String citaTextual, String estado,
                             String confianza, String tipoFuente, String referencia) {
    }
}