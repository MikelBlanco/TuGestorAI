package org.gestorai.model;

import java.time.LocalDateTime;

/**
 * Entidad usuario (autónomo). Corresponde a la tabla {@code usuarios}.
 */
public class Usuario {

    public static final String PLAN_FREE = "free";
    public static final String PLAN_PRO  = "pro";

    public static final int LIMITE_PRESUPUESTOS_FREE = 5;

    private Long id;
    private Long telegramId;
    private String nombre;
    private String nif;
    private String direccion;
    private String telefono;
    private String email;
    private String nombreComercial;
    private String logoUrl;
    private String plan;
    private int presupuestosMes;
    private LocalDateTime consentimientoAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Usuario() {}

    /** Devuelve true si el usuario tiene plan de pago. */
    public boolean esPro() {
        return PLAN_PRO.equals(plan);
    }

    /**
     * Devuelve true si el usuario free puede crear más presupuestos este mes.
     * Los usuarios pro siempre pueden.
     */
    public boolean puedeCrearPresupuesto() {
        return esPro() || presupuestosMes < LIMITE_PRESUPUESTOS_FREE;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTelegramId() { return telegramId; }
    public void setTelegramId(Long telegramId) { this.telegramId = telegramId; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getNif() { return nif; }
    public void setNif(String nif) { this.nif = nif; }

    public String getDireccion() { return direccion; }
    public void setDireccion(String direccion) { this.direccion = direccion; }

    public String getTelefono() { return telefono; }
    public void setTelefono(String telefono) { this.telefono = telefono; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getNombreComercial() { return nombreComercial; }
    public void setNombreComercial(String nombreComercial) { this.nombreComercial = nombreComercial; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public int getPresupuestosMes() { return presupuestosMes; }
    public void setPresupuestosMes(int presupuestosMes) { this.presupuestosMes = presupuestosMes; }

    public LocalDateTime getConsentimientoAt() { return consentimientoAt; }
    public void setConsentimientoAt(LocalDateTime consentimientoAt) { this.consentimientoAt = consentimientoAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}