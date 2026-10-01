package com.abegomez.research.api;

import java.time.Instant;
import java.util.List;

/**
 * Formato unico de error de la API. Los mensajes visibles van en espanol y el
 * codigo es estable para que el frontend pueda reaccionar sin parsear texto.
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String codigo,
        String mensaje,
        String ruta,
        List<String> detalles) {

    public static ApiErrorResponse of(int status, String codigo, String mensaje, String ruta,
                                       List<String> detalles) {
        return new ApiErrorResponse(Instant.now(), status, codigo, mensaje, ruta,
                detalles == null ? List.of() : List.copyOf(detalles));
    }
}
