package com.tugestorai.exception;

/**
 * Excepción propia para errores en el procesamiento del bot de Telegram.
 */
public class BotException extends RuntimeException {

    public BotException(String message) {
        super(message);
    }

    public BotException(String message, Throwable cause) {
        super(message, cause);
    }
}