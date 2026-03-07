package com.tugestorai.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entidad factura. Corresponde a la tabla {@code facturas}.
 *
 * <p>Total = subtotal + IVA - IRPF</p>
 */
public class Factura {

    public static final String ESTADO_BORRADOR = "borrador";
    public static final String ESTADO_EMITIDA  = "emitida";
    public static final String ESTADO_PAGADA   = "pagada";
    public static final String ESTADO_ANULADA  = "anulada";

    /** IRPF estándar para autónomos con más de 3 años de actividad. */
    public static final BigDecimal IRPF_ESTANDAR = BigDecimal.valueOf(15);
    /** IRPF reducido para nuevos autónomos (primeros 3 años). */
    public static final BigDecimal IRPF_REDUCIDO = BigDecimal.valueOf(7);

    private Long id;
    private String numero;
    private Long usuarioId;
    private Long presupuestoId;
    private Long clienteId;
    private String clienteNombre;
    private String descripcion;
    private BigDecimal subtotal;
    private BigDecimal ivaPorcentaje;
    private BigDecimal ivaImporte;
    private BigDecimal irpfPorcentaje;
    private BigDecimal irpfImporte;
    private BigDecimal total;
    private String estado;
    private String pdfPath;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<LineaDetalle> lineas = new ArrayList<>();

    public Factura() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNumero() { return numero; }
    public void setNumero(String numero) { this.numero = numero; }

    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(Long usuarioId) { this.usuarioId = usuarioId; }

    public Long getPresupuestoId() { return presupuestoId; }
    public void setPresupuestoId(Long presupuestoId) { this.presupuestoId = presupuestoId; }

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

    public BigDecimal getIrpfPorcentaje() { return irpfPorcentaje; }
    public void setIrpfPorcentaje(BigDecimal irpfPorcentaje) { this.irpfPorcentaje = irpfPorcentaje; }

    public BigDecimal getIrpfImporte() { return irpfImporte; }
    public void setIrpfImporte(BigDecimal irpfImporte) { this.irpfImporte = irpfImporte; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public String getPdfPath() { return pdfPath; }
    public void setPdfPath(String pdfPath) { this.pdfPath = pdfPath; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<LineaDetalle> getLineas() { return lineas; }
    public void setLineas(List<LineaDetalle> lineas) { this.lineas = lineas; }
}