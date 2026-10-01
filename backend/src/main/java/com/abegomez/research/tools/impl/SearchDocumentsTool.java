package com.abegomez.research.tools.impl;

import java.time.Duration;
import java.util.stream.Collectors;

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
 * Busqueda por palabras clave sobre los documentos cargados.
 *
 * <p>Complementa a la busqueda semantica: cuando se busca un termino exacto, un
 * nombre propio o una cifra, el coincidencia literal es mas fiable que la
 * similitud. A proposito no es semantica, para que el agente tenga dos caminos
 * distintos y no dependa de uno solo.
 */
@Component
public class SearchDocumentsTool implements ResearchTool {

    private static final int MAX_FRAGMENTOS = 8;
    private static final int MAX_CARACTERES_FRAGMENTO = 1200;

    private final DocumentChunkRepository repository;

    public SearchDocumentsTool(DocumentChunkRepository repository) {
        this.repository = repository;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.SEARCH_DOCUMENTS,
                "Busca por palabras clave en los documentos cargados. Usala para terminos exactos, "
                        + "nombres propios o cifras donde la coincidencia literal importa mas que la "
                        + "similitud semantica.",
                """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "Término o frase exacta a buscar en los fragmentos."
                    }
                  },
                  "required": ["query"]
                }
                """,
                """
                {
                  "type": "object",
                  "properties": {
                    "resultados": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "documento": {"type": "string"},
                          "fragmento": {"type": "integer"},
                          "texto": {"type": "string"}
                        }
                      }
                    }
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
        String query = parametros.path("query").asText("").trim();
        if (query.isEmpty()) {
            return ToolResult.fallo("El parametro 'query' es obligatorio y no puede estar vacio",
                    Duration.ZERO, 1);
        }
        return ToolResult.ok(repository.buscarPorPalabraClave(query, MAX_FRAGMENTOS)
                .stream()
                .map(matched -> "[doc " + matched.documentoId() + " | " + matched.nombre()
                        + " | fragmento " + matched.indice() + "]\n"
                        + recortar(matched.texto()))
                .collect(Collectors.joining("\n\n---\n\n")), Duration.ZERO, 1);
    }

    private String recortar(String texto) {
        if (texto == null) {
            return "";
        }
        return texto.length() <= MAX_CARACTERES_FRAGMENTO
                ? texto
                : texto.substring(0, MAX_CARACTERES_FRAGMENTO) + "...[truncado]";
    }

}