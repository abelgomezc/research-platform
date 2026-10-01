package com.abegomez.research.knowledge.localrag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Respuesta del endpoint de busqueda de LocalRAG.
 *
 * <p>Contrato verificado con un servidor HTTP simulado. LocalRAG **aun no expone**
 * este endpoint: hoy solo tiene {@code POST /api/chat}, que genera respuesta con
 * el LLM, y {@code GET /api/documents/{id}/content}, que devuelve el documento
 * completo sin fragmentar ni puntuar. Ver README.
 *
 * <p>Se documenta el contrato esperado para que el adaptador sea utilizable en
 * cuanto LocalRAG lo exponga, sin modificar LocalRAG desde este proyecto.
 *
 * <p>Contrato: {@code GET /api/search?query=...&topK=...}
 * devuelve una lista de objetos con estos campos.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LocalRagSearchItem(
        String documentId,
        String fileName,
        Integer pageNumber,
        Integer chunkNumber,
        String text,
        Double score) {

    /**
     * Convierte la respuesta del proveedor al record del dominio, que es lo unico
     * que el agente ve.
     */
    public com.abegomez.research.knowledge.KnowledgeResult toResult() {
        String titulo = fileName != null ? fileName : documentId();
        // La referencia debe poder citarse en el informe: documento + fragmento.
        String referencia = documentId() + (chunkNumber != null ? "#chunk-" + chunkNumber : "");
        return new com.abegomez.research.knowledge.KnowledgeResult(
                text,
                documentId(),
                titulo,
                referencia,
                score != null ? score : 0.0);
    }

    static List<LocalRagSearchItem> emptyIfNull(List<LocalRagSearchItem> items) {
        return items == null ? List.of() : items;
    }
}
