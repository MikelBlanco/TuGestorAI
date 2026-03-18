package org.gestorai.model;

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

    public static final String ESTADO_BORRADOR = "BORRADOR";
    public static final String ESTADO_EMITIDA  = "EMITIDA";
    public static final String ESTADO_PAGADA   = "PAGADA";
    public static final String ESTADO_ANULADA  = "ANULADA";

    /** IRPF estándar para autónomos con más de 3 años de actividad. */
    public static final BigDecimal IRPF_ESTANDAR = BigDecimal.valueOf(15);
    /** IRPF reducido para nuevos autónomos (primeros 3 años). */
    public static final BigDecimal IRPF_REDUCIDO = BigDecimal.valueOf(7);

    private Long id;
    private Long autonomoId;
    private Long presupuestoId;
    private Long clienteId;
    private String numero;
    private String estado;
    private String clienteNombre;
    private String notas;
    private BigDecimal subtotal;
    private BigDecimal ivaPorcentaje;
    private BigDecimal ivaImporte;
    private BigDecimal irpfPorcentaje;
    private BigDecimal irpfImporte;
    private BigDecimal total;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<LineaDetalle> lineas = new ArrayList<>();

    public Factura() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAutonomoId() { return autonomoId; }
    public void setAutonomoId(Long autonomoId) { this.autonomoId = autonomoId; }

    public Long getPresupuestoId() { return presupuestoId; }
    public void setPresupuestoId(Long presupuestoId) { this.presupuestoId = presupuestoId; }

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

    public BigDecimal getIrpfPorcentaje() { return irpfPorcentaje; }
    public void setIrpfPorcentaje(BigDecimal irpfPorcentaje) { this.irpfPorcentaje = irpfPorcentaje; }

    public BigDecimal getIrpfImporte() { return irpfImporte; }
    public void setIrpfImporte(BigDecimal irpfImporte) { this.irpfImporte = irpfImporte; }

    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public List<LineaDetalle> getLineas() { return lineas; }
    public void setLineas(List<LineaDetalle> lineas) { this.lineas = lineas; }
}
