package com.tugestorai.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO con los datos de un presupuesto extraídos por Claude API a partir de
 * la transcripción de un audio. No es una entidad de BD.
 */
public class DatosPresupuesto {

    private String clienteNombre;
    private String descripcion;
    private List<LineaDetalle> lineas = new ArrayList<>();
    private BigDecimal ivaPorcentaje = BigDecimal.valueOf(21);

    public DatosPresupuesto() {}

    /** Calcula el subtotal sumando los importes de todas las líneas. */
    public BigDecimal calcularSubtotal() {
        return lineas.stream()
                .map(LineaDetalle::getImporte)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Calcula el importe de IVA sobre el subtotal. */
    public BigDecimal calcularIvaImporte() {
        return calcularSubtotal()
                .multiply(ivaPorcentaje)
                .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP);
    }

    /** Calcula el total (subtotal + IVA). */
    public BigDecimal calcularTotal() {
        return calcularSubtotal().add(calcularIvaImporte());
    }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public List<LineaDetalle> getLineas() { return lineas; }
    public void setLineas(List<LineaDetalle> lineas) { this.lineas = lineas; }

    public BigDecimal getIvaPorcentaje() { return ivaPorcentaje; }
    public void setIvaPorcentaje(BigDecimal ivaPorcentaje) { this.ivaPorcentaje = ivaPorcentaje; }
}