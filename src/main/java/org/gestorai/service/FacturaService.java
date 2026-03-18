package org.gestorai.service;

import org.gestorai.dao.FacturaDao;
import org.gestorai.dao.PresupuestoDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.Autonomo;
import org.gestorai.model.Factura;
import org.gestorai.model.LineaDetalle;
import org.gestorai.model.Presupuesto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Lógica de negocio para facturas.
 * Solo los autónomos con plan PRO pueden generar facturas.
 */
public class FacturaService {

    private static final Logger log = LoggerFactory.getLogger(FacturaService.class);

    private final FacturaDao facturaDao = new FacturaDao();
    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final NumeracionService numeracionService = new NumeracionService();
    private final PdfService pdfService = new PdfService();

    /**
     * Convierte un presupuesto aceptado en una factura.
     *
     * <p>Copia todas las líneas de detalle del presupuesto, aplica IRPF y
     * calcula los totales. La factura queda en estado {@code BORRADOR}.</p>
     *
     * @param presupuestoId  ID del presupuesto origen
     * @param autonomo       autónomo que emite la factura
     * @param irpfPorcentaje retención de IRPF (usa {@link Factura#IRPF_ESTANDAR} si null)
     * @return factura creada y persistida
     * @throws ServiceException si el autónomo no es PRO o el presupuesto no existe
     */
    public Factura crearDesdePresupuesto(long presupuestoId, Autonomo autonomo,
                                         BigDecimal irpfPorcentaje) {
        if (!autonomo.esPro()) {
            throw new ServiceException(
                    "La generación de facturas requiere el plan PRO. Usa /plan para más información.");
        }

        Presupuesto presupuesto = presupuestoDao.findById(presupuestoId)
                .orElseThrow(() -> new ServiceException("Presupuesto no encontrado: " + presupuestoId));

        if (!presupuesto.getAutonomoId().equals(autonomo.getId())) {
            throw new ServiceException("No tienes acceso a ese presupuesto.");
        }

        BigDecimal irpf = irpfPorcentaje != null ? irpfPorcentaje : Factura.IRPF_ESTANDAR;

        Factura factura = new Factura();
        factura.setNumero(numeracionService.siguienteNumeroFactura(autonomo.getId()));
        factura.setAutonomoId(autonomo.getId());
        factura.setPresupuestoId(presupuestoId);
        factura.setClienteId(presupuesto.getClienteId());
        factura.setClienteNombre(presupuesto.getClienteNombre());
        factura.setNotas(presupuesto.getNotas());
        factura.setEstado(Factura.ESTADO_BORRADOR);

        List<LineaDetalle> lineas = copiarLineas(presupuesto.getLineas());
        factura.setLineas(lineas);

        BigDecimal subtotal    = presupuesto.getSubtotal();
        BigDecimal ivaPorcentaje = presupuesto.getIvaPorcentaje();
        BigDecimal ivaImporte  = presupuesto.getIvaImporte();
        BigDecimal irpfImporte = subtotal
                .multiply(irpf)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(ivaImporte).subtract(irpfImporte);

        factura.setSubtotal(subtotal);
        factura.setIvaPorcentaje(ivaPorcentaje);
        factura.setIvaImporte(ivaImporte);
        factura.setIrpfPorcentaje(irpf);
        factura.setIrpfImporte(irpfImporte);
        factura.setTotal(total);

        Factura guardada = facturaDao.crear(factura);

        try {
            pdfService.generarFactura(guardada, autonomo);
        } catch (Exception e) {
            log.error("Error generando PDF factura id={}", guardada.getId(), e);
            // No relanzamos: la factura está guardada, el PDF se puede regenerar
        }

        presupuestoDao.actualizarEstado(presupuestoId, Presupuesto.ESTADO_FACTURADO);

        log.info("Factura creada id={} numero={} desde presupuesto id={}",
                guardada.getId(), guardada.getNumero(), presupuestoId);
        return guardada;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<LineaDetalle> copiarLineas(List<LineaDetalle> origen) {
        List<LineaDetalle> copia = new ArrayList<>();
        for (LineaDetalle l : origen) {
            LineaDetalle nueva = new LineaDetalle();
            nueva.setConcepto(l.getConcepto());
            nueva.setCantidad(l.getCantidad());
            nueva.setPrecioUnitario(l.getPrecioUnitario());
            nueva.setImporte(l.getImporte());
            nueva.setTipo(l.getTipo());
            nueva.setOrden(l.getOrden());
            copia.add(nueva);
        }
        return copia;
    }
}
