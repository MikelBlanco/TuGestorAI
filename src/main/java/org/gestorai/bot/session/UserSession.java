package org.gestorai.bot.session;

import org.gestorai.model.DatosPresupuesto;

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
    /** Bytes del PDF pendiente de enviar por email tras confirmar un presupuesto. */
    private byte[] pendingPdfBytes;
    /** Nombre del fichero PDF pendiente (ej: {@code presupuesto_P-2026-0001.pdf}). */
    private String pendingPdfNombre;
    /** Bytes del Excel (.xlsx) pendiente de enviar por email. */
    private byte[] pendingXlsxBytes;
    /** Nombre del fichero Excel pendiente (ej: {@code presupuesto_P-2026-0001.xlsx}). */
    private String pendingXlsxNombre;
    private Instant lastActivity = Instant.now();

    public UserSession(long chatId) {
        this.chatId = chatId;
    }

    /** Actualiza la marca de tiempo de última actividad. */
    public void touch() {
        this.lastActivity = Instant.now();
    }

    /** Resetea la sesión al estado inicial, limpiando el borrador y el PDF pendiente. */
    public void reset() {
        this.state = SessionState.IDLE;
        this.borradorPresupuesto = null;
        this.transcripcion = null;
        this.pendingPdfBytes = null;
        this.pendingPdfNombre = null;
        this.pendingXlsxBytes = null;
        this.pendingXlsxNombre = null;
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

    public byte[] getPendingPdfBytes() { return pendingPdfBytes; }
    public void setPendingPdfBytes(byte[] pendingPdfBytes) {
        this.pendingPdfBytes = pendingPdfBytes;
        touch();
    }

    public String getPendingPdfNombre() { return pendingPdfNombre; }
    public void setPendingPdfNombre(String pendingPdfNombre) {
        this.pendingPdfNombre = pendingPdfNombre;
        touch();
    }

    public byte[] getPendingXlsxBytes() { return pendingXlsxBytes; }
    public void setPendingXlsxBytes(byte[] pendingXlsxBytes) {
        this.pendingXlsxBytes = pendingXlsxBytes;
        touch();
    }

    public String getPendingXlsxNombre() { return pendingXlsxNombre; }
    public void setPendingXlsxNombre(String pendingXlsxNombre) {
        this.pendingXlsxNombre = pendingXlsxNombre;
        touch();
    }

    public Instant getLastActivity() { return lastActivity; }
}
