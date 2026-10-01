package com.abegomez.research.llm;

import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.common.exception.BusinessException;

/**
 * Fallo del proveedor de modelos. Se distingue de los fallos de negocio para
 * poder decidir si conviene reintentar.
 */
public class LlmCallException extends BusinessException {

    private final int attempts;

    public LlmCallException(String message, Throwable cause, int attempts) {
        super(ErrorCode.LLM_ERROR, message, cause);
        this.attempts = attempts;
    }

    public int attempts() {
        return attempts;
    }
}
