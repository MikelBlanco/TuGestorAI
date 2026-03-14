package org.gestorai.service;

import org.gestorai.dao.FacturaDao;
import org.gestorai.dao.PresupuestoDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.Factura;
import org.gestorai.model.LineaDetalle;
import org.gestorai.model.Presupuesto;
import org.gestorai.model.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Lógica de negocio para facturas.
 * Solo los usuarios con plan PRO pueden generar facturas.
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
     * calcula los totales. La factura queda en estado {@code borrador}.</p>
     *
     * @param presupuestoId ID del presupuesto origen
     * @param usuario       autónomo que emite la factura
     * @param irpfPorcentaje retención de IRPF (usa {@link Factura#IRPF_ESTANDAR} si null)
     * @return factura creada y persistida
     * @throws ServiceException si el usuario no es PRO o el presupuesto no existe
     */
    public Factura crearDesdePresupuesto(long presupuestoId, Usuario usuario,
                                         BigDecimal irpfPorcentaje) {
        if (!usuario.esPro()) {
            throw new ServiceException(
                    "La generación de facturas requiere el plan PRO. Usa /plan para más información.");
        }

        Presupuesto presupuesto = presupuestoDao.findById(presupuestoId)
                .orElseThrow(() -> new ServiceException("Presupuesto no encontrado: " + presupuestoId));

        if (!presupuesto.getUsuarioId().equals(usuario.getId())) {
            throw new ServiceException("No tienes acceso a ese presupuesto.");
        }

        BigDecimal irpf = irpfPorcentaje != null ? irpfPorcentaje : Factura.IRPF_ESTANDAR;

        Factura factura = new Factura();
        factura.setNumero(numeracionService.siguienteNumeroFactura(usuario.getId()));
        factura.setUsuarioId(usuario.getId());
        factura.setPresupuestoId(presupuestoId);
        factura.setClienteId(presupuesto.getClienteId());
        factura.setClienteNombre(presupuesto.getClienteNombre());
        factura.setDescripcion(presupuesto.getDescripcion());
        factura.setEstado(Factura.ESTADO_BORRADOR);

        // Copiar líneas del presupuesto asignándolas a la futura factura
        List<LineaDetalle> lineas = copiarLineas(presupuesto.getLineas());
        factura.setLineas(lineas);

        // Calcular importes
        BigDecimal subtotal = presupuesto.getSubtotal();
        BigDecimal ivaPorcentaje = presupuesto.getIvaPorcentaje();
        BigDecimal ivaImporte = presupuesto.getIvaImporte();
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

        // Generar PDF
        try {
            java.io.File pdf = pdfService.generarFactura(guardada, usuario);
            facturaDao.actualizarPdfPath(guardada.getId(), pdf.getAbsolutePath());
            guardada.setPdfPath(pdf.getAbsolutePath());
        } catch (Exception e) {
            log.error("Error generando PDF factura id={}", guardada.getId(), e);
            // No relanzamos: la factura está guardada, el PDF se puede regenerar
        }

        // Marcar el presupuesto como aceptado
        presupuestoDao.actualizarEstado(presupuestoId, Presupuesto.ESTADO_ACEPTADO);

        log.info("Factura creada id={} numero={} desde presupuesto id={}",
                guardada.getId(), guardada.getNumero(), presupuestoId);
        return guardada;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Copia las líneas del presupuesto para la factura, limpiando el ID
     * y el vínculo con el presupuesto origen.
     */
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