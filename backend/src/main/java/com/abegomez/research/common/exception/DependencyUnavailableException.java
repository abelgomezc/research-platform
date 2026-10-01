package com.abegomez.research.common.exception;

import com.abegomez.research.common.ErrorCode;

/**
 * Fallo de una dependencia externa (LocalRAG, SearXNG, base de datos del
 * proveedor de conocimiento).
 *
 * <p>Existe para que un fallo de una implementacion alternativa no se confunda con
 * un fallo de la plataforma: la tool recibe un mensaje entendible y el agente
 * continua con otras fuentes.
 */
public class DependencyUnavailableException extends BusinessException {

    public DependencyUnavailableException(String message) {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, message);
    }

    public DependencyUnavailableException(String message, Throwable cause) {
        super(ErrorCode.DEPENDENCY_UNAVAILABLE, message, cause);
    }
}
