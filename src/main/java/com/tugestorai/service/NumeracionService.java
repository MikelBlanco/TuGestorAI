package com.tugestorai.service;

import com.tugestorai.dao.PresupuestoDao;

import java.time.Year;

/**
 * Genera los números de documento correlativos para presupuestos y facturas.
 *
 * <p>Formato presupuesto: {@code P-2026-0001}<br>
 * Formato factura:     {@code F-2026-0001}</p>
 *
 * <p>Las facturas deben ser estrictamente correlativas sin saltos, requisito fiscal español.</p>
 */
public class NumeracionService {

    private final PresupuestoDao presupuestoDao = new PresupuestoDao();

    /**
     * Devuelve el siguiente número de presupuesto para un usuario en el año en curso.
     *
     * @param usuarioId ID del autónomo
     * @return número con formato {@code P-AAAA-NNNN}
     */
    public String siguienteNumeroPresupuesto(long usuarioId) {
        int anio = Year.now().getValue();
        int siguiente = presupuestoDao.contarPorUsuarioYAnio(usuarioId, anio) + 1;
        return String.format("P-%d-%04d", anio, siguiente);
    }
}