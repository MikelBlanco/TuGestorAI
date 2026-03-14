package org.gestorai.model;

import java.math.BigDecimal;

/**
 * Línea de detalle de un presupuesto o factura.
 * Corresponde a la tabla {@code lineas_detalle}.
 */
public class LineaDetalle {

    public static final String TIPO_SERVICIO = "servicio";
    public static final String TIPO_MATERIAL = "material";

    private Long id;
    private Long presupuestoId;
    private Long facturaId;
    private String concepto;
    private BigDecimal cantidad;
    private BigDecimal precioUnitario;
    private BigDecimal importe;
    private String tipo;
    private int orden;

    public LineaDetalle() {}

    /**
     * Constructor de conveniencia. Calcula el importe automáticamente.
     */
    public LineaDetalle(String concepto, BigDecimal cantidad, BigDecimal precioUnitario, String tipo) {
        this.concepto = concepto;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.importe = cantidad.multiply(precioUnitario);
        this.tipo = tipo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPresupuestoId() { return presupuestoId; }
    public void setPresupuestoId(Long presupuestoId) { this.presupuestoId = presupuestoId; }

    public Long getFacturaId() { return facturaId; }
    public void setFacturaId(Long facturaId) { this.facturaId = facturaId; }

    public String getConcepto() { return concepto; }
    public void setConcepto(String concepto) { this.concepto = concepto; }

    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }

    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }

    public BigDecimal getImporte() { return importe; }
    public void setImporte(BigDecimal importe) { this.importe = importe; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public int getOrden() { return orden; }
    public void setOrden(int orden) { this.orden = orden; }
}