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
 * Marca como completada la tarea que el agente esta ejecutando.
 *
 * <p>El agente no elige que tarea cierra: la tarea llega en
 * {@link ToolContext#tareaId()} y esa es la unica que puede marcar. Permitirle
 * pasar un id arbitrario le daria forma de cerrar tareas de otros agentes y
 * manipular el recuento de progreso.
 *
 * <p>El estado lo fija el codigo, no el modelo. El LLM aporta el resumen; la
 * transicion a COMPLETADA la decide el framework.
 */
@Component
public class MarkTaskCompleteTool implements ResearchTool {

    private static final Set<String> RESUMENES_MAXIMOS = Set.of("COMPLETADA", "DESCARTADA");

    private final JdbcTemplate jdbcTemplate;

    public MarkTaskCompleteTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.MARK_TASK_COMPLETE,
                "Cierra la tarea que estas ejecutando y guarda tu resumen de lo encontrado. "
                        + "Se llama una sola vez, al terminar. Si no encontraste nada util, "
                        + "marcala como DESCARTADA en lugar de completada.",
                """
                {
                  "type": "object",
                  "properties": {
                    "resumen": {
                      "type": "string",
                      "description": "Que se ocurrio al investigar: que se busco, que se hallo y que quedo sin resolver."
                    },
                    "resultado": {
                      "type": "string",
                      "enum": ["COMPLETADA", "DESCARTADA"],
                      "description": "COMPLETADA si la tarea dio fruto; DESCARTADA si no habia informacion util."
                    },
                    "informacionFaltante": {
                      "type": "array",
                      "items": {"type": "string"},
                      "description": "Lo que no se pudo determinar. Se registra como pregunta abierta."
                    }
                  },
                  "required": ["resumen", "resultado"]
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "tareaId": {"type": "integer"},
                    "estado": {"type": "string"}
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
        Long tareaId = context.tareaId();
        Long investigacionId = context.investigacionId();
        if (tareaId == null || investigacionId == null) {
            return ToolResult.fallo("No hay tarea activa: no se puede cerrar nada",
                    Duration.ZERO, 1);
        }

        String resultado = parametros.path("resultado").asText("").toUpperCase(Locale.ROOT);
        if (!RESUMENES_MAXIMOS.contains(resultado)) {
            return ToolResult.fallo(
                    "El campo 'resultado' debe ser COMPLETADA o DESCARTADA. Recibido: '" + resultado + "'",
                    Duration.ZERO, 1);
        }

        String resumen = parametros.path("resumen").asText("").trim();
        if (resumen.length() < 10) {
            return ToolResult.fallo(
                    "El resumen es demasiado corto para ser util. Describe que se busco y que se encontro.",
                    Duration.ZERO, 1);
        }

        // La condicion sobre investigacion_id evita que un agente cierre una
        // tarea que pertenece a otra investigacion.
        int actualizadas = jdbcTemplate.update("""
                UPDATE tareas_investigacion
                SET estado = ?,
                    resumen_resultado = ?,
                    intentos = intentos + 1,
                    actualizado_en = NOW()
                WHERE id = ? AND investigacion_id = ?
                """, resultado, resumen, tareaId, investigacionId);

        if (actualizadas == 0) {
            return ToolResult.fallo(
                    "La tarea " + tareaId + " no existe en esta investigacion o ya fue cerrada",
                    Duration.ZERO, 1);
        }

        registrarInformacionFaltante(investigacionId, parametros.path("informacionFaltante"));

        return ToolResult.ok("Tarea " + tareaId + " marcada como " + resultado
                + ". El orquestador continuara con el plan.", Duration.ZERO, 1);
    }

    /**
     * Lo que el agente no pudo determinar se guarda como pregunta abierta, no se
     * descarta. Es la diferencia entre un informe honesto y uno con huecos
     * silenciosos.
     *
     * <p>La tabla no tiene columna de tarea ni de origen en el esquema base: solo
     * la pregunta y su estado. No se anaden columnas en esta fase.
     */
    private void registrarInformacionFaltante(Long investigacionId, JsonNode nodo) {
        if (!nodo.isArray()) {
            return;
        }
        for (JsonNode pregunta : nodo) {
            String texto = pregunta.asText("").trim();
            if (texto.length() < 5) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO preguntas_abiertas (investigacion_id, pregunta) VALUES (?, ?)",
                    investigacionId, texto);
        }
    }
}