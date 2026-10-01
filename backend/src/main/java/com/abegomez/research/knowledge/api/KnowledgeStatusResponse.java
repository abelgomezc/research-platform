package com.abegomez.research.knowledge.api;

import java.util.List;

/**
 * Estado del proveedor de conocimiento activo: cual es, si responde y con que
 * parametros opera. No expone secretos.
 */
public record KnowledgeStatusResponse(
        String proveedor,
        String descripcion,
        boolean operativo,
        String mensaje,
        Integer topKPorDefecto,
        Integer dimensionEmbeddings,
        String baseUrl,
        List<String> advertencias) {
}
