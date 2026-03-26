package org.gestorai.model;

import java.time.LocalDateTime;

/**
 * Entidad cliente. Corresponde a la tabla {@code clientes}.
 *
 * <p>Cada cliente pertenece a un autónomo ({@code autonomo_id}).
 * Los campos sensibles (nif, direccion, telefono, email) se cifran en BD con AES-256-GCM.</p>
 *
 * <p>No existe restricción UNIQUE sobre el nombre: puede haber varios clientes con el mismo
 * nombre. Cuando se detecta una coincidencia al crear un presupuesto, el bot pregunta al
 * autónomo si es el mismo cliente o uno nuevo.</p>
 */
public class Cliente {

    private Long id;
    private Long autonomoId;
    private String nombre;
    private String nif;           // cifrado AES-256-GCM
    private String direccion;     // cifrado AES-256-GCM
    private String telefono;      // cifrado AES-256-GCM
    private String email;         // cifrado AES-256-GCM
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Cliente() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAutonomoId() { return autonomoId; }
    public void setAutonomoId(Long autonomoId) { this.autonomoId = autonomoId; }

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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}