package com.abegomez.research.knowledge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Entrada de carga de un documento de texto.
 *
 * <p>La carga por texto es la via principal para el corpus de demostracion.
 * La carga de PDF se resuelve con el mismo servicio en la Fase 3.
 */
public record DocumentUploadRequest(

        @NotBlank(message = "El nombre del documento es obligatorio")
        @Size(max = 255, message = "El nombre no puede superar 255 caracteres")
        @Pattern(regexp = "[^\\\\/:*?\"<>|]+",
                message = "El nombre no puede contener \\ / : * ? \" < > |")
        String nombre,

        @NotBlank(message = "El contenido es obligatorio")
        @Pattern(regexp = "(?i)^(TXT|MD)$", message = "El tipo debe ser TXT o MD")
        String tipo,

        @NotBlank(message = "El contenido del documento es obligatorio")
        String contenido) {
}
