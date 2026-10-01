package com.abegomez.research.tools.impl;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

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
 * Anade una tarea de seguimiento que el Planner no previo.
 *
 * <p>Existe porque el plan inicial nunca es completo: si al investigar aparece
 * una linea de investigacion que no estaba prevista, descartarla seria perder
 * informacion. Pero la tool esta limitada a un maximo de tareas por ronda: sin
 * ese tope, un agente podria inflar el plan hasta agotar el presupuesto de la
 * investigacion, y el presupuesto es una decision del sistema, no del modelo.
 */
@Component
public class CreateResearchTaskTool implements ResearchTool {

    /** Tope de tareas nuevas por ronda e investigacion. */
    private static final int MAX_TAREAS_POR_RONDA = 3;

    private static final Set<String> TIPOS_FUENTE = Set.of("INTERNA", "WEB", "BASE_DATOS");

    private final JdbcTemplate jdbcTemplate;

    public CreateResearchTaskTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.CREATE_RESEARCH_TASK,
                "Propone una tarea de seguimiento que el plan inicial no cubria. "
                        + "Usala solo si al investigar aparece una linea nueva de trabajo. "
                        + "No la uses para repetir algo que ya se hizo. Maximo "
                        + MAX_TAREAS_POR_RONDA + " tareas nuevas por ronda.",
                """
                {
                  "type": "object",
                  "properties": {
                    "descripcion": {
                      "type": "string",
                      "description": "Que hay que investigar y con que criterio se da por terminada."
                    },
                    "tipoFuente": {
                      "type": "string",
                      "enum": ["INTERNA", "WEB", "BASE_DATOS"],
                      "description": "Donde se buscara principalmente."
                    },
                    "prioridad": {
                      "type": "integer",
                      "description": "Prioridad: mayor numero, mayor prioridad.",
                      "minimum": 0,
                      "maximum": 100
                    }
                  },
                  "required": ["descripcion", "tipoFuente"]
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "tareaId": {"type": "integer"},
                    "tareasPendientesEnLaRonda": {"type": "integer"}
                  }
                }
                """)
                .permission(ToolPermission.WRITE_INTERNAL)
                .riskLevel(RiskLevel.MEDIUM)
                .timeout(Duration.ofSeconds(10))
                .retries(0)
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode parametros) {
        Long investigacionId = context.investigacionId();
        if (investigacionId == null) {
            return ToolResult.fallo("No hay investigacion activa: no se puede crear la tarea",
                    Duration.ZERO, 1);
        }

        String descripcion = parametros.path("descripcion").asText("").trim();
        if (descripcion.length() < 15) {
            return ToolResult.fallo(
                    "La descripcion es demasiado corta. Describe que hay que investigar y con que criterio "
                            + "se da por terminada.", Duration.ZERO, 1);
        }

        String tipo = parametros.path("tipoFuente").asText("").toUpperCase(Locale.ROOT);
        if (!TIPOS_FUENTE.contains(tipo)) {
            return ToolResult.fallo(
                    "tipoFuente debe ser uno de " + TIPOS_FUENTE + ". Recibido: '" + tipo + "'",
                    Duration.ZERO, 1);
        }

        Integer prioridad = Math.max(0, Math.min(100, parametros.path("prioridad").asInt(0)));

        Integer ronda = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(ronda), 0) FROM tareas_investigacion
                WHERE investigacion_id = ?
                """, Integer.class, investigacionId);

        Integer nuevasEnRonda = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tareas_investigacion
                WHERE investigacion_id = ? AND ronda = ?
                """, Integer.class, investigacionId, ronda);

        if (nuevasEnRonda != null && nuevasEnRonda >= MAX_TAREAS_POR_RONDA) {
            return ToolResult.fallo(
                    "Ya se alcanzaron las " + MAX_TAREAS_POR_RONDA + " tareas nuevas de la ronda "
                            + ronda + ". Propón la informacion faltante en el resumen de tu tarea "
                            + "en lugar de crear mas tareas.", Duration.ZERO, 1);
        }

        Long tareaId = jdbcTemplate.queryForObject("""
                INSERT INTO tareas_investigacion
                    (investigacion_id, descripcion, tipo_fuente, prioridad, estado, ronda)
                VALUES (?, ?, ?, ?, 'PENDIENTE', ?)
                RETURNING id
                """, Long.class, investigacionId, descripcion, tipo, prioridad, ronda);

        Integer totalRonda = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tareas_investigacion
                WHERE investigacion_id = ? AND ronda = ? AND estado = 'PENDIENTE'
                """, Integer.class, investigacionId, ronda);

        return ToolResult.ok("Tarea " + tareaId + " creada en la ronda " + ronda + ". "
                + "Quedan " + totalRonda + " tareas pendientes en esta ronda.",
                Duration.ZERO, 1);
    }
}