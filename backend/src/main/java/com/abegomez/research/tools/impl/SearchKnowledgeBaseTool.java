package com.abegomez.research.tools.impl;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import com.abegomez.research.knowledge.KnowledgeResult;
import com.abegomez.research.knowledge.KnowledgeSearchPort;
import com.abegomez.research.tools.ResearchTool;
import com.abegomez.research.tools.RiskLevel;
import com.abegomez.research.tools.ToolContext;
import com.abegomez.research.tools.ToolDefinition;
import com.abegomez.research.tools.ToolPermission;
import com.abegomez.research.tools.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Busca fragmentos en el conocimiento interno a traves del puerto.
 *
 * <p>No sabe si detras hay pgvector o LocalRAG. Esa es la razon de existir del
 * puerto: el agente no necesita conocer la tecnologia.
 */
@Component
public class SearchKnowledgeBaseTool implements ResearchTool {

    private static final int MAX_TOP_K = 20;

    private final KnowledgeSearchPort knowledgeSearchPort;

    public SearchKnowledgeBaseTool(KnowledgeSearchPort knowledgeSearchPort) {
        this.knowledgeSearchPort = knowledgeSearchPort;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition.Builder(
                ToolDefinition.Names.SEARCH_KNOWLEDGE_BASE,
                "Busca fragmentos relevantes en el conocimiento interno por similitud semantica. "
                        + "Usala cuando necesites definiciones, caracteristicas de tecnologias o "
                        + "cifras concrete interna. Devuelve texto del fragmento y su referencia.",
                """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "Consulta en lenguaje natural. Describe el concepto, no la pregunta completa."
                    },
                    "topK": {
                      "type": "integer",
                      "description": "Numero de fragmentos a devolver. Maximo 20.",
                      "minimum": 1,
                      "maximum": 20
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
                          "texto": {"type": "string"},
                          "documento": {"type": "string"},
                          "referencia": {"type": "string"},
                          "puntaje": {"type": "number"}
                        }
                      }
                    }
                  }
                }
                """)
                .permission(ToolPermission.READ)
                .riskLevel(RiskLevel.LOW)
                .timeout(Duration.ofSeconds(20))
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
        int topK = Math.min(MAX_TOP_K, Math.max(1, parametros.path("topK").asInt(5)));

        List<KnowledgeResult> resultados = knowledgeSearchPort.search(query, topK);

        if (resultados.isEmpty()) {
            return ToolResult.ok(
                    "No se encontraron fragmentos para: " + query
                            + ". Si el corpus no cubre el tema, declaralo como informacion faltante.",
                    Duration.ZERO, 1);
        }

        String contenido = resultados.stream()
                .map(resultado -> "[" + resultado.referencia() + "] (puntaje "
                        + String.format("%.3f", resultado.puntaje()) + ")\n" + resultado.texto())
                .collect(Collectors.joining("\n\n---\n\n"));

        return ToolResult.ok(contenido, Duration.ZERO, 1);
    }
}