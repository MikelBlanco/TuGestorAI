package com.tugestorai.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO con los datos de un presupuesto extraídos por Claude API a partir de
 * la transcripción de un audio. No es una entidad de BD.
 */
public class DatosPresupuesto {

    private String clienteNombre;
    private String clienteTelefono;
    private String clienteEmail;
    private String descripcion;
    private String notas;
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
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Calcula el total (subtotal + IVA). */
    public BigDecimal calcularTotal() {
        return calcularSubtotal().add(calcularIvaImporte());
    }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }

    public String getClienteTelefono() { return clienteTelefono; }
    public void setClienteTelefono(String clienteTelefono) { this.clienteTelefono = clienteTelefono; }

    public String getClienteEmail() { return clienteEmail; }
    public void setClienteEmail(String clienteEmail) { this.clienteEmail = clienteEmail; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getNotas() { return notas; }
    public void setNotas(String notas) { this.notas = notas; }

    public List<LineaDetalle> getLineas() { return lineas; }
    public void setLineas(List<LineaDetalle> lineas) { this.lineas = lineas; }

    public BigDecimal getIvaPorcentaje() { return ivaPorcentaje; }
    public void setIvaPorcentaje(BigDecimal ivaPorcentaje) { this.ivaPorcentaje = ivaPorcentaje; }
}