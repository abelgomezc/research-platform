package com.abegomez.research.tools;

import java.time.Duration;

/**
 * Resultado de ejecutar una herramienta.
 *
 * <p>Un fallo de tool **no** es una excepcion que tumba la investigacion: se
 * devuelve un {@link ToolResult} con {@code exitoso=false} y un mensaje que el
 * agente puede entender y actuar. Esa es la diferencia entre una tool que falla y
 * una investigacion que se cae.
 *
 * @param exitoso       si la tool completo su trabajo
 * @param contenido     salida para el agente (texto o JSON)
 * @param error         mensaje entendible cuando fallo
 * @param duracionMs    duracion real
 * @param intentos      intentos consumidos
 */
public record ToolResult(
        boolean exitoso,
        String contenido,
        String error,
        Duration duracionMs,
        int intentos) {

    public static ToolResult ok(String contenido, Duration duracionMs, int intentos) {
        return new ToolResult(true, contenido, null, duracionMs, intentos);
    }

    public static ToolResult fallo(String error, Duration duracionMs, int intentos) {
        return new ToolResult(false, null, error, duracionMs, intentos);
    }

    /**
     * El agente solo recibe texto, con exito o con el error.
     */
    public String paraElAgente() {
        return exitoso ? contenido : "[herramienta falló] " + error;
    }
}