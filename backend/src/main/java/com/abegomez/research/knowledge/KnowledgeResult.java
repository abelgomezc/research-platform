package com.abegomez.research.knowledge;

/**
 * Resultado de una busqueda de conocimiento interno.
 *
 * <p>Este es el contrato comun a las dos implementaciones (pgvector y LocalRAG).
 * El agente solo ve este record: no sabe de donde salio el fragmento ni que
 * tecnologia se uso para recuperarlo.
 *
 * @param texto        contenido del fragmento recuperado
 * @param documentoId  identificador del documento dentro del proveedor
 * @param documentoTitulo nombre legible del documento
 * @param referencia   como citar la fuente (url, ruta o id del proveedor)
 * @param puntaje      puntaje de similitud, mayor es mas relevante
 */
public record KnowledgeResult(
        String texto,
        String documentoId,
        String documentoTitulo,
        String referencia,
        double puntaje) {
}
