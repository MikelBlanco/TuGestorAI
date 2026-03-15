package org.gestorai.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad presupuesto. Corresponde a la tabla {@code presupuestos}.
 */
public class Presupuesto {

    public static final String ESTADO_BORRADOR   = "borrador";
    public static final String ESTADO_ENVIADO    = "enviado";
    public static final String ESTADO_ACEPTADO   = "aceptado";
    public static final String ESTADO_RECHAZADO  = "rechazado";

    private Long id;
    private String numero;
    private Long usuarioId;
    private Long clienteId;
    private String clienteNombre;
    private String descripcion;
    private BigDecimal subtotal;
    private BigDecimal ivaPorcentaje;
    private BigDecimal ivaImporte;
    private BigDecimal total;
    private String estado;
    private String audioTranscript;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime enviadoAt;

    private List<LineaDetalle> lineas = new ArrayList<>();

    public Presupuesto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }

    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(Long usuarioId) { this.usuarioId = usuarioId; }

    public Long getClienteId() { return clienteId; }
    public void setClienteId(Long clienteId) { this.clienteId = clienteId; }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getIvaPorcentaje() { return ivaPorcentaje; }
    public void setIvaPorcentaje(BigDecimal ivaPorcentaje) { this.ivaPorcentaje = ivaPorcentaje; }

    public BigDecimal getIvaImporte() { return ivaImporte; }
    public void setIvaImporte(BigDecimal ivaImporte) { this.ivaImporte = ivaImporte; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getAudioTranscript() { return audioTranscript; }
    public void setAudioTranscript(String audioTranscript) { this.audioTranscript = audioTranscript; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getEnviadoAt() { return enviadoAt; }
    public void setEnviadoAt(LocalDateTime enviadoAt) { this.enviadoAt = enviadoAt; }

    public List<LineaDetalle> getLineas() { return lineas; }
    public void setLineas(List<LineaDetalle> lineas) { this.lineas = lineas; }
}