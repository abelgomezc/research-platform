package com.abegomez.research.knowledge;

import java.util.List;

/**
 * Puerto de busqueda de conocimiento interno.
 *
 * <p>Define el contrato que cumplen las dos implementaciones intercambiables:
 *
 * <ul>
 *   <li>{@code pgvector}: busqueda semantica simple incluida en este repositorio.</li>
 *   <li>{@code localrag}: adaptador HTTP hacia un LocalRAG externo.</li>
 * </ul>
 *
 * <p>Los agentes y la tool {@code search_knowledge_base} dependen solo de esta
 * interfaz, nunca de una implementacion concreta. Ese es el punto: poder cambiar
 * de motor de busqueda sin tocar el agente.
 */
public interface KnowledgeSearchPort {

    /**
     * Busca fragmentos relevantes para una consulta.
     *
     * @param query texto de la consulta
     * @param topK  numero maximo de resultados
     * @return resultados ordenados de mas a menos relevante; lista vacia si no hay coincidencias
     */
    List<KnowledgeResult> search(String query, int topK);

    /**
     * Identificador del proveedor activo, para diagnostics y health checks.
     */
    String providerId();
}
