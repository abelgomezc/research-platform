package com.abegomez.research.tools.impl;

import java.time.Duration;

import com.abegomez.research.knowledge.pgvector.DocumentChunkRepository;
import com.abegomez.research.tools.ResearchTool;
import com.abegomez.research.tools.RiskLevel;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolDefinition;
import com.abegomez.research.tools.ToolPermission;
import com.abegomez.research.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Devuelve el texto de un documento, completo o desde un fragmento concreto.
 *
 * <p>Existe por una razon de verificacion: la comprobacion determinista de la
 * Fase 6 necesita el texto exacto de la fuente para confirmar que una cita
 * aparece literalmente. Recuperar el documento entero es lo que hace posible esa
 * garantia.
 */
@Component
public class ReadDocumentTool implements ResearchTool {

    private static final int MAX_CARACTERES = 20000;
    private static final int MAX_FRAGMENTOS = 40;

    private final DocumentChunkRepository repository;

    public ReadDocumentTool(DocumentChunkRepository repository) {
        this.repository = repository;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.READ_DOCUMENT,
                "Devuelve el texto de un documento, completo o a partir de un fragmento concreto. "
                        + "Usala cuando necesites el texto exacto de una fuente para citarlo con "
                        + "precision, no un resumen.",
                """
                {
                  "type": "object",
                  "properties": {
                    "documentId": {
                      "type": "integer",
                      "description": "Id del documento, tal como lo devuelve search_documents o search_knowledge_base."
                    },
                    "fragmento": {
                      "type": "integer",
                      "description": "Indice del primer fragmento a devolver. Omitir para el documento completo.",
                      "minimum": 0
                    }
                  },
                  "required": ["documentId"]
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "documento": {"type": "string"},
                    "texto": {"type": "string"},
                    "truncado": {"type": "boolean"}
                  }
                }
                """)
                .permission(ToolPermission.READ)
                .riskLevel(RiskLevel.LOW)
                .timeout(Duration.ofSeconds(15))
                .retries(1)
                .build();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode parametros) {
        JsonNode nodoId = parametros.path("documentId");
        if (!nodoId.isNumber()) {
            return ToolResult.fallo("El parametro 'documentId' es obligatorio y debe ser un numero entero",
                    Duration.ZERO, 1);
        }
        long documentoId = nodoId.asLong();

        var nombreOpt = repository.findNombreById(documentoId);
        if (nombreOpt.isEmpty()) {
            return ToolResult.fallo("No existe el documento con id " + documentoId,
                    Duration.ZERO, 1);
        }
        String nombre = nombreOpt.get();

        JsonNode nodoFragmento = parametros.path("fragmento");
        String texto;
        boolean truncado = false;

        if (nodoFragmento.isNumber() && nodoFragmento.asInt() >= 0) {
            int desde = nodoFragmento.asInt();
            StringBuilder sb = new StringBuilder();
            for (DocumentChunkRepository.FragmentoTexto fragmento
                    : repository.fragmentosDe(documentoId, desde, MAX_FRAGMENTOS)) {
                sb.append("\n[fragmento ").append(fragmento.indice()).append("]\n")
                        .append(fragmento.texto());
            }
            if (sb.isEmpty()) {
                return ToolResult.fallo("El documento " + documentoId + " no tiene fragmentos desde el indice "
                        + desde, Duration.ZERO, 1);
            }
            texto = sb.toString();
            if (texto.length() > MAX_CARACTERES) {
                texto = texto.substring(0, MAX_CARACTERES);
                truncado = true;
            }
        }
        else {
            String completo = repository.textoDeDocumento(documentoId).orElse("");
            texto = completo.length() > MAX_CARACTERES
                    ? completo.substring(0, MAX_CARACTERES)
                    : completo;
            truncado = completo.length() > MAX_CARACTERES;
        }

        String cabecera = "Documento: " + nombre + " (id " + documentoId + ")\n"
                + (truncado ? "[texto truncado en " + MAX_CARACTERES + " caracteres]\n" : "");

        return ToolResult.ok(cabecera + texto, Duration.ZERO, 1);
    }
}