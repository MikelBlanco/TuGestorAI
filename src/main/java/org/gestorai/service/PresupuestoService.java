package org.gestorai.service;

import org.gestorai.dao.PresupuestoDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.Autonomo;
import org.gestorai.model.DatosPresupuesto;
import org.gestorai.model.Presupuesto;
import org.gestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lógica de negocio para {@link Presupuesto}.
 *
 * <p>Centraliza: creación con numeración automática, validación de límites freemium,
 * validación de transiciones de estado y consultas con aislamiento multi-tenant.</p>
 */
public class PresupuestoService {

    private static final Logger log = LoggerFactory.getLogger(PresupuestoService.class);

    /** Transiciones de estado válidas: estadoOrigen → {estadosDestino}. */
    private static final Map<String, Set<String>> TRANSICIONES_VALIDAS = Map.of(
            Presupuesto.ESTADO_BORRADOR, Set.of(Presupuesto.ESTADO_ENVIADO, Presupuesto.ESTADO_CANCELADO),
            Presupuesto.ESTADO_ENVIADO,  Set.of(Presupuesto.ESTADO_ACEPTADO, Presupuesto.ESTADO_RECHAZADO),
            Presupuesto.ESTADO_ACEPTADO, Set.of(Presupuesto.ESTADO_FACTURADO)
    );

    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final NumeracionService numeracionService = new NumeracionService();

    // -------------------------------------------------------------------------
    // Control freemium
    // -------------------------------------------------------------------------

    /**
     * Lanza {@link ServiceException} si el autónomo ha superado el límite mensual del plan gratuito.
     *
     * @param autonomoId ID del autónomo
     * @param plan       plan del autónomo ("free" o "pro")
     */
    public void verificarLimiteFreemium(long autonomoId, String plan) {
        if (!"free".equalsIgnoreCase(plan)) return;
        int limite = leerLimiteFreemium();
        int usados = presupuestoDao.contarPorAutonomoEnMes(autonomoId);
        if (usados >= limite) {
            throw new ServiceException(String.format(
                    "Has alcanzado el límite de %d presupuestos este mes con el plan gratuito.\n\n" +
                    "Usa /plan para ver cómo actualizar a PRO.", limite));
        }
    }

    /**
     * Devuelve el número de presupuestos creados por el autónomo en el mes natural actual.
     * Se usa para mostrar el contador en el comando /plan.
     */
    public int contarPresupuestosMes(long autonomoId) {
        return presupuestoDao.contarPorAutonomoEnMes(autonomoId);
    }

    // -------------------------------------------------------------------------
    // Creación
    // -------------------------------------------------------------------------

    /**
     * Crea un presupuesto con numeración automática a partir de los datos del borrador.
     * No verifica el límite freemium: llamar {@link #verificarLimiteFreemium} antes.
     *
     * @param autonomoId    ID del autónomo (multi-tenant)
     * @param clienteId     ID del cliente vinculado, puede ser {@code null}
     * @param datos         datos estructurados del presupuesto (resultado de Claude)
     * @param transcripcion transcripción del audio original, puede ser {@code null}
     * @return presupuesto persistido con ID y número asignados
     */
    public Presupuesto crear(long autonomoId, Long clienteId, DatosPresupuesto datos, String transcripcion) {
        Presupuesto p = new Presupuesto();
        p.setAutonomoId(autonomoId);
        p.setClienteId(clienteId);
        p.setClienteNombre(datos.getClienteNombre());
        p.setNotas(datos.getDescripcion());
        p.setSubtotal(datos.calcularSubtotal());
        p.setIvaPorcentaje(datos.getIvaPorcentaje());
        p.setIvaImporte(datos.calcularIvaImporte());
        p.setTotal(datos.calcularTotal());
        p.setEstado(Presupuesto.ESTADO_BORRADOR);
        p.setAudioTranscript(transcripcion);
        p.setLineas(datos.getLineas());
        p.setNumero(numeracionService.siguienteNumeroPresupuesto(autonomoId));
        Presupuesto guardado = presupuestoDao.crear(p);
        log.info("Presupuesto creado id={} numero={} autonomo={}", guardado.getId(), guardado.getNumero(), autonomoId);
        return guardado;
    }

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca un presupuesto por ID garantizando que pertenece al autónomo (multi-tenant).
     *
     * @param autonomoId ID del autónomo
     * @param id         ID del presupuesto
     * @return el presupuesto con sus líneas, o vacío si no existe o no pertenece al autónomo
     */
    public Optional<Presupuesto> findById(long autonomoId, long id) {
        return presupuestoDao.findById(id)
                .filter(p -> autonomoId == p.getAutonomoId());
    }

    /**
     * Busca un presupuesto por su número de referencia para un autónomo concreto.
     * Incluye las líneas de detalle.
     */
    public Optional<Presupuesto> findByNumero(long autonomoId, String numero) {
        return presupuestoDao.findByNumeroYAutonomo(autonomoId, numero);
    }

    /**
     * Lista presupuestos del autónomo, opcionalmente filtrados por estado.
     *
     * @param estado si es {@code null} o vacío, devuelve todos los estados
     */
    public List<Presupuesto> listar(long autonomoId, String estado) {
        if (estado != null && !estado.isBlank()) {
            return presupuestoDao.findByAutonomoIdAndEstado(autonomoId, estado);
        }
        return presupuestoDao.findByAutonomoId(autonomoId);
    }

    /** Lista presupuestos de un mes y año concreto. */
    public List<Presupuesto> listarPorMes(long autonomoId, int year, int month) {
        return presupuestoDao.listarPorMes(autonomoId, year, month);
    }

    /** Lista presupuestos en estado ACEPTADO pendientes de facturar. */
    public List<Presupuesto> listarPendientes(long autonomoId) {
        return presupuestoDao.listarPendientes(autonomoId);
    }

    /** Resumen de presupuestos de un mes agrupado por estado. */
    public PresupuestoDao.ResumenMensual resumenMensual(long autonomoId, int year, int month) {
        return presupuestoDao.resumenMensual(autonomoId, year, month);
    }

    // -------------------------------------------------------------------------
    // Cambio de estado
    // -------------------------------------------------------------------------

    /**
     * Cambia el estado de un presupuesto identificado por su número, validando la transición.
     *
     * @param autonomoId  ID del autónomo (multi-tenant)
     * @param numero      número de presupuesto (ej: P-2026-0001)
     * @param nuevoEstado estado destino (ver constantes en {@link Presupuesto})
     * @throws ServiceException si el presupuesto no existe o la transición no está permitida
     */
    public void cambiarEstadoPorNumero(long autonomoId, String numero, String nuevoEstado) {
        Presupuesto p = presupuestoDao.findByNumeroYAutonomo(autonomoId, numero)
                .orElseThrow(() -> new ServiceException("No he encontrado el presupuesto " + numero + "."));
        validarYEjecutarTransicion(p, nuevoEstado);
    }

    /**
     * Cambia el estado de un presupuesto identificado por su ID, validando la transición.
     * Garantiza que el presupuesto pertenece al autónomo (multi-tenant).
     *
     * @param autonomoId  ID del autónomo (multi-tenant)
     * @param id          ID del presupuesto
     * @param nuevoEstado estado destino (ver constantes en {@link Presupuesto})
     * @throws ServiceException si el presupuesto no existe, no pertenece al autónomo
     *                          o la transición no está permitida
     */
    public void cambiarEstadoPorId(long autonomoId, long id, String nuevoEstado) {
        Presupuesto p = findById(autonomoId, id)
                .orElseThrow(() -> new ServiceException("No se encontró el presupuesto id=" + id));
        validarYEjecutarTransicion(p, nuevoEstado);
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private void validarYEjecutarTransicion(Presupuesto p, String nuevoEstado) {
        String estadoActual = p.getEstado();
        Set<String> permitidos = TRANSICIONES_VALIDAS.getOrDefault(estadoActual, Set.of());
        if (!permitidos.contains(nuevoEstado)) {
            throw new ServiceException(String.format(
                    "No se puede cambiar el estado de %s a %s.\nEl presupuesto está en estado: %s.",
                    p.getNumero(), nuevoEstado.toLowerCase(), estadoActual));
        }
        presupuestoDao.actualizarEstado(p.getId(), nuevoEstado);
        log.info("Estado presupuesto {} {} → {}", p.getNumero(), estadoActual, nuevoEstado);
    }

    private static int leerLimiteFreemium() {
        try {
            String val = ConfigUtil.get("plan.free.limite.presupuestos");
            return (val != null) ? Integer.parseInt(val.trim()) : Autonomo.LIMITE_PRESUPUESTOS_FREE;
        } catch (NumberFormatException e) {
            return Autonomo.LIMITE_PRESUPUESTOS_FREE;
        }
    }
}
