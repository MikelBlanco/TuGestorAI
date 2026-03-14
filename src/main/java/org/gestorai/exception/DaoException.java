package org.gestorai.exception;

/**
 * Excepción de acceso a datos. Envuelve {@link java.sql.SQLException} con contexto de negocio.
 */
public class DaoException extends RuntimeException {

    public DaoException(String message, Throwable cause) {
        super(message, cause);
    }

    public DaoException(String message) {
        super(message);
    }
}