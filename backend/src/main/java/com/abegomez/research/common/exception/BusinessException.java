package com.abegomez.research.common.exception;

/**
 * Excepcion base del dominio. Todo error de negocio se expresa con esta clase
 * para que el manejo global de errores tenga un unico punto de entrada.
 */
public class BusinessException extends RuntimeException {

    private final transient com.abegomez.research.common.ErrorCode errorCode;

    public BusinessException(com.abegomez.research.common.ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(com.abegomez.research.common.ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public com.abegomez.research.common.ErrorCode errorCode() {
        return errorCode;
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(com.abegomez.research.common.ErrorCode.NOT_FOUND, message);
    }
}
