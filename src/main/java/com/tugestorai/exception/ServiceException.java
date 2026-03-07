package com.tugestorai.exception;

/**
 * Excepción propia para errores en la capa de servicio
 * (integraciones externas: Whisper, Claude, PDF, etc.).
 */
public class ServiceException extends RuntimeException {

    public ServiceException(String message) {
        super(message);
    }

    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}