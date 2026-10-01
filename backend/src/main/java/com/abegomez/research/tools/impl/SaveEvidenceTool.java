package com.abegomez.research.tools.impl;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.abegomez.research.knowledge.pgvector.DocumentChunkRepository;
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
 * Guarda una evidencia: una afirmacion sostenida por una cita textual de una fuente.
 *
 * <p>La tool <b>no verifica</b>. Inserta siempre en estado {@code PENDIENTE}, aunque
 * el agente este convencido de que su cita es correcta. Quien verifica es el
 * Verifier, en la Fase 6. Si el agente pudiera marcar su propia evidencia como
 * verificada, la verificacion no seria una comprobacion sino una decoracion, y
 * el Reviewer no tendria nada que revisar.
 *
 * <p>Es la unica tool con permiso {@code WRITE} de esta fase, y aun asi solo
 * puede insertar en {@code fuentes} y {@code evidencias}, nunca actualizar ni
 * borrar. El alcance lo define {@link ToolPermission}.
 */
@Component
public class SaveEvidenceTool implements ResearchTool {

    private static final Set<String> TIPOS_FUENTE = Set.of("DOCUMENTO", "WEB", "BASE_DATOS", "LOCALRAG");

    private final JdbcTemplate jdbcTemplate;
    private final DocumentChunkRepository documentRepository;

    public SaveEvidenceTool(JdbcTemplate jdbcTemplate, DocumentChunkRepository documentRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentRepository = documentRepository;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.SAVE_EVIDENCE,
                "Guarda una evidencia: una afirmacion y la cita textual exacta que la sostiene. "
                        + "La evidencia se registra como PENDIENTE de verificacion; tu opinion no "
                        + "la marca como verificada. Usa solo citas copiadas literalmente de la fuente.",
                """
                {
                  "type": "object",
                  "properties": {
                    "tipoFuente": {
                      "type": "string",
                      "enum": ["DOCUMENTO", "WEB", "BASE_DATOS", "LOCALRAG"],
                      "description": "De donde procede la fuente."
                    },
                    "referencia": {
                      "type": "string",
                      "description": "URL completa, o referencia del documento (por ejemplo 'documento 12')."
                    },
                    "titulo": {
                      "type": "string",
                      "description": "Titulo de la fuente. Opcional."
                    },
                    "documentId": {
                      "type": "integer",
                      "description": "Id del documento, si tipoFuente es DOCUMENTO."
                    },
                    "citaTextual": {
                      "type": "string",
                      "description": "Fragmento citado, copiado literalmente de la fuente sin parafrasear."
                    },
                    "afirmacion": {
                      "type": "string",
                      "description": "La afirmacion que la cita sostiene. Una por evidencia."
                    },
                    "confianza": {
                      "type": "number",
                      "description": "Confianza de 0 a 1 en que la cita sostiene la afirmacion.",
                      "minimum": 0,
                      "maximum": 1
                    }
                  },
                  "required": ["tipoFuente", "referencia", "citaTextual", "afirmacion"]
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "fuenteId": {"type": "integer"},
                    "evidenciaId": {"type": "integer"},
                    "estadoVerificacion": {"type": "string"}
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
            return ToolResult.fallo("No hay investigacion activa: la evidencia no se puede guardar",
                    Duration.ZERO, 1);
        }

        String tipo = parametros.path("tipoFuente").asText("").toUpperCase(Locale.ROOT);
        if (!TIPOS_FUENTE.contains(tipo)) {
            return ToolResult.fallo(
                    "tipoFuente debe ser uno de " + TIPOS_FUENTE + ". Recibido: '" + tipo + "'",
                    Duration.ZERO, 1);
        }

        String referencia = parametros.path("referencia").asText("").trim();
        String cita = parametros.path("citaTextual").asText("").trim();
        String afirmacion = parametros.path("afirmacion").asText("").trim();

        if (referencia.isEmpty() || cita.isEmpty() || afirmacion.isEmpty()) {
            return ToolResult.fallo(
                    "referencia, citaTextual y afirmacion son obligatorias y no pueden estar vacias",
                    Duration.ZERO, 1);
        }

        // Una cita de menos de una frase casi nunca es una cita: suele ser un
        // titulo o un encabezado capturado por error.
        if (cita.length() < 20) {
            return ToolResult.fallo(
                    "La cita textual es demasiado corta (" + cita.length()
                            + " caracteres). Cita el texto que sostiene la afirmacion, no un titulo.",
                    Duration.ZERO, 1);
        }

        JsonNode nodoConfianza = parametros.path("confianza");
        Double confianza = null;
        if (nodoConfianza.isNumber()) {
            confianza = Math.max(0.0, Math.min(1.0, nodoConfianza.asDouble()));
        }

        long fuenteId;
        try {
            // Se comprueba que el documento existe para no dejar una fuente
            // huerfana apuntando a un id que nadie podria recuperar.
            if ("DOCUMENTO".equals(tipo) && parametros.path("documentId").isNumber()) {
                Optional<String> nombre =
                        documentRepository.findNombreById(parametros.path("documentId").asLong());
                if (nombre.isEmpty()) {
                    return ToolResult.fallo(
                            "No existe el documento " + parametros.path("documentId").asLong(),
                            Duration.ZERO, 1);
                }
            }

            fuenteId = resolverFuente(investigacionId, tipo, referencia,
                    parametros.path("titulo").asText("").trim());
        }
        catch (RuntimeException ex) {
            return ToolResult.fallo("No se pudo registrar la fuente: " + ex.getMessage(),
                    Duration.ZERO, 1);
        }

        Long evidenciaId = jdbcTemplate.queryForObject("""
                INSERT INTO evidencias
                    (investigacion_id, tarea_id, fuente_id, cita_textual, afirmacion,
                     estado_verificacion, confianza)
                VALUES (?, ?, ?, ?, ?, 'PENDIENTE', ?)
                RETURNING id
                """, Long.class, investigacionId, context.tareaId(), fuenteId, cita,
                afirmacion, confianza);

        return ToolResult.ok("""
                Evidencia registrada como PENDIENTE de verificacion.
                  evidenciaId: %d
                  fuenteId: %d
                La verificacion la realizara el agente verificador. No la des por verificada.
                """.formatted(evidenciaId, fuenteId), Duration.ZERO, 1);
    }

    /**
     * Devuelve el id de la fuente, reutilizandola si la misma referencia ya
     * existe en esta investigacion.
     *
     * <p>Reutilizar por referencia evita crear una fuente distinta por cada
     * cita del mismo documento, que haria el recuento de fuentes falso.
     */
    private long resolverFuente(Long investigacionId, String tipo, String referencia, String titulo) {
        List<Long> existentes = jdbcTemplate.queryForList("""
                SELECT id FROM fuentes
                WHERE investigacion_id = ? AND tipo = ? AND referencia = ?
                LIMIT 1
                """, Long.class, investigacionId, tipo, referencia);

        if (!existentes.isEmpty()) {
            return existentes.get(0);
        }

        return jdbcTemplate.queryForObject("""
                INSERT INTO fuentes (investigacion_id, tipo, referencia, titulo)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, investigacionId, tipo, referencia,
                titulo.isEmpty() ? null : titulo);
    }
}