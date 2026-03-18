package org.gestorai.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad presupuesto. Corresponde a la tabla {@code presupuestos}.
 */
public class Presupuesto {

    public static final String ESTADO_BORRADOR   = "BORRADOR";
    public static final String ESTADO_ENVIADO    = "ENVIADO";
    public static final String ESTADO_ACEPTADO   = "ACEPTADO";
    public static final String ESTADO_RECHAZADO  = "RECHAZADO";
    public static final String ESTADO_FACTURADO  = "FACTURADO";
    public static final String ESTADO_CANCELADO  = "CANCELADO";

    private Long id;
    private Long autonomoId;
    private Long clienteId;
    private String numero;
    private String estado;
    private String clienteNombre;
    private String notas;
    private BigDecimal subtotal;
    private BigDecimal ivaPorcentaje;
    private BigDecimal ivaImporte;
    private BigDecimal total;
    private String audioTranscript;  // cifrado AES-256-GCM
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<LineaDetalle> lineas = new ArrayList<>();

    public Presupuesto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAutonomoId() { return autonomoId; }
    public void setAutonomoId(Long autonomoId) { this.autonomoId = autonomoId; }

    public Long getClienteId() { return clienteId; }
    public void setClienteId(Long clienteId) { this.clienteId = clienteId; }

    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }

    public String getNotas() { return notas; }
    public void setNotas(String notas) { this.notas = notas; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getIvaPorcentaje() { return ivaPorcentaje; }
    public void setIvaPorcentaje(BigDecimal ivaPorcentaje) { this.ivaPorcentaje = ivaPorcentaje; }

    public BigDecimal getIvaImporte() { return ivaImporte; }
    public void setIvaImporte(BigDecimal ivaImporte) { this.ivaImporte = ivaImporte; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getAudioTranscript() { return audioTranscript; }
    public void setAudioTranscript(String audioTranscript) { this.audioTranscript = audioTranscript; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<LineaDetalle> getLineas() { return lineas; }
    public void setLineas(List<LineaDetalle> lineas) { this.lineas = lineas; }
}
