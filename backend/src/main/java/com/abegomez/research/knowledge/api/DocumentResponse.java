package com.abegomez.research.knowledge.api;

import java.time.Instant;

/**
 * Vista de un documento almacenado en el conocimiento interno.
 * DTO separado de la entidad, como exige la convencion del proyecto.
 */
public record DocumentResponse(
        Long id,
        String nombre,
        String tipo,
        int totalFragmentos,
        Instant creadoEn) {
}
