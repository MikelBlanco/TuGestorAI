package org.gestorai.model;

import java.time.LocalDateTime;

/**
 * Entidad autónomo. Corresponde a la tabla {@code autonomos}.
 */
public class Autonomo {

    public static final String PLAN_FREE = "free";
    public static final String PLAN_PRO  = "pro";

    public static final int LIMITE_PRESUPUESTOS_FREE = 5;

    private Long id;
    private Long telegramId;
    private String nombre;
    private String nif;            // cifrado AES-256-GCM
    private String direccion;      // cifrado AES-256-GCM
    private String telefono;       // cifrado AES-256-GCM
    private String email;          // cifrado AES-256-GCM
    private String nombreComercial;
    private String plan;
    /** Número de presupuestos del mes en curso. No persiste en BD; se carga con una consulta. */
    private int presupuestosMes;
    private LocalDateTime rgpdAceptadoAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Autonomo() {}

    /** Devuelve true si el autónomo tiene plan de pago. */
    public boolean esPro() {
        return PLAN_PRO.equals(plan);
    }

    /**
     * Devuelve true si el autónomo puede crear más presupuestos este mes.
     * Requiere que {@code presupuestosMes} haya sido cargado previamente mediante
     * {@link org.gestorai.dao.PresupuestoDao#contarPorAutonomoEnMes(long)}.
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

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public int getPresupuestosMes() { return presupuestosMes; }
    public void setPresupuestosMes(int presupuestosMes) { this.presupuestosMes = presupuestosMes; }

    public LocalDateTime getRgpdAceptadoAt() { return rgpdAceptadoAt; }
    public void setRgpdAceptadoAt(LocalDateTime rgpdAceptadoAt) { this.rgpdAceptadoAt = rgpdAceptadoAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
