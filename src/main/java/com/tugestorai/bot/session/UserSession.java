package com.tugestorai.bot.session;

import com.tugestorai.model.DatosPresupuesto;

import java.time.Instant;

/**
 * Estado conversacional de un usuario con el bot.
 * Se almacena en memoria con TTL de 30 minutos.
 */
public class UserSession {

    private final long chatId;
    private SessionState state = SessionState.IDLE;
    private DatosPresupuesto borradorPresupuesto;
    private String transcripcion;
    private Instant lastActivity = Instant.now();

    public UserSession(long chatId) {
        this.chatId = chatId;
    }

    /** Actualiza la marca de tiempo de última actividad. */
    public void touch() {
        this.lastActivity = Instant.now();
    }

    /** Resetea la sesión al estado inicial, limpiando el borrador. */
    public void reset() {
        this.state = SessionState.IDLE;
        this.borradorPresupuesto = null;
        this.transcripcion = null;
        touch();
    }

    public long getChatId() { return chatId; }

    public SessionState getState() { return state; }
    public void setState(SessionState state) {
        this.state = state;
        touch();
    }

    public DatosPresupuesto getBorradorPresupuesto() { return borradorPresupuesto; }
    public void setBorradorPresupuesto(DatosPresupuesto borradorPresupuesto) {
        this.borradorPresupuesto = borradorPresupuesto;
        touch();
    }

    public String getTranscripcion() { return transcripcion; }
    public void setTranscripcion(String transcripcion) {
        this.transcripcion = transcripcion;
        touch();
    }

    public Instant getLastActivity() { return lastActivity; }
}