package com.abegomez.research.api;

import java.util.List;

import com.abegomez.research.common.ErrorCode;
import com.abegomez.research.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Manejo global de errores. Ningun controller decide codigos HTTP ni construye
 * respuestas de error a mano.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        log.warn("Error de negocio: codigo={}, mensaje={}", code.name(), ex.getMessage());
        return build(code.httpStatus(), code.name(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return build(ErrorCode.INVALID_INPUT.httpStatus(), ErrorCode.INVALID_INPUT.name(),
                ErrorCode.INVALID_INPUT.defaultMessage(), request, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiErrorResponse> handleMalformed(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.INVALID_INPUT.httpStatus(), ErrorCode.INVALID_INPUT.name(),
                "La solicitud esta mal formada", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error no controlado en {}", request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR.httpStatus(), ErrorCode.INTERNAL_ERROR.name(),
                ErrorCode.INTERNAL_ERROR.defaultMessage(), request, List.of());
    }

    private ResponseEntity<ApiErrorResponse> build(int status, String codigo, String mensaje,
                                                  HttpServletRequest request, List<String> detalles) {
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .body(ApiErrorResponse.of(status, codigo, mensaje, request.getRequestURI(), detalles));
    }
}
