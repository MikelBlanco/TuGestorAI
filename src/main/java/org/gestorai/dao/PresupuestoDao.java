package org.gestorai.dao;

import org.gestorai.exception.DaoException;
import org.gestorai.model.LineaDetalle;
import org.gestorai.model.Presupuesto;
import org.gestorai.util.CryptoUtil;
import org.gestorai.util.DbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a datos para {@link Presupuesto} y sus {@link LineaDetalle}.
 *
 * <p>Todas las operaciones de escritura que involucren tanto {@code presupuestos}
 * como {@code lineas_detalle} se realizan en una misma transacción.</p>
 */
public class PresupuestoDao extends BaseDao {

    private static final Logger log = LoggerFactory.getLogger(PresupuestoDao.class);

    private static final String COLUMNAS = """
            id, autonomo_id, cliente_id, numero, estado, cliente_nombre,
            subtotal, iva_porcentaje, iva_importe, total,
            audio_transcript, notas, created_at, updated_at
            """;

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca un presupuesto por su ID, incluyendo sus líneas de detalle.
     */
    public Optional<Presupuesto> findById(long id) {
        String sql = "SELECT " + COLUMNAS + " FROM presupuestos WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Presupuesto p = mapRow(rs);
                    p.setLineas(findLineas(conn, p.getId()));
                    return Optional.of(p);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error buscando presupuesto id=" + id, e);
        }
        return Optional.empty();
    }

    /**
     * Lista todos los presupuestos de un autónomo, sin cargar sus líneas de detalle.
     *
     * @param autonomoId ID del autónomo
     * @return lista ordenada por fecha de creación descendente
     */
    public List<Presupuesto> findByAutonomoId(long autonomoId) {
        validateAutonomoId(autonomoId);
        String sql = "SELECT " + COLUMNAS + " FROM presupuestos WHERE autonomo_id = ? ORDER BY created_at DESC";
        return ejecutarListado(sql, autonomoId);
    }

    /**
     * Lista los presupuestos de un autónomo filtrados por estado, sin líneas de detalle.
     */
    public List<Presupuesto> findByAutonomoIdAndEstado(long autonomoId, String estado) {
        validateAutonomoId(autonomoId);
        String sql = "SELECT " + COLUMNAS +
                     " FROM presupuestos WHERE autonomo_id = ? AND estado = ? ORDER BY created_at DESC";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            ps.setString(2, estado);
            return mapearLista(ps);
        } catch (SQLException e) {
            throw new DaoException("Error listando presupuestos autonomo=" + autonomoId + " estado=" + estado, e);
        }
    }

    /**
     * Lista los presupuestos de un autónomo en un mes y año concreto.
     */
    public List<Presupuesto> listarPorMes(long autonomoId, int year, int month) {
        validateAutonomoId(autonomoId);
        String sql = "SELECT " + COLUMNAS + """
                 FROM presupuestos
                WHERE autonomo_id = ?
                  AND EXTRACT(YEAR  FROM created_at) = ?
                  AND EXTRACT(MONTH FROM created_at) = ?
                ORDER BY created_at DESC
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            ps.setInt(2, year);
            ps.setInt(3, month);
            return mapearLista(ps);
        } catch (SQLException e) {
            throw new DaoException("Error listando presupuestos autonomo=" + autonomoId, e);
        }
    }

    /**
     * Lista los presupuestos en estado ACEPTADO pendientes de facturar.
     */
    public List<Presupuesto> listarPendientes(long autonomoId) {
        return findByAutonomoIdAndEstado(autonomoId, Presupuesto.ESTADO_ACEPTADO);
    }

    /**
     * Busca un presupuesto por su número de referencia (ej: P-2026-0001) para un autónomo concreto.
     * Incluye las líneas de detalle.
     *
     * @param autonomoId ID del autónomo (para aislamiento multi-tenant)
     * @param numero     número de presupuesto (insensible a mayúsculas)
     * @return el presupuesto con sus líneas, o vacío si no existe o no pertenece al autónomo
     */
    public Optional<Presupuesto> findByNumeroYAutonomo(long autonomoId, String numero) {
        validateAutonomoId(autonomoId);
        String sql = "SELECT " + COLUMNAS +
                     " FROM presupuestos WHERE autonomo_id = ? AND UPPER(numero) = UPPER(?)";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            ps.setString(2, numero);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Presupuesto p = mapRow(rs);
                    p.setLineas(findLineas(conn, p.getId()));
                    return Optional.of(p);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error buscando presupuesto numero=" + numero, e);
        }
        return Optional.empty();
    }

    /**
     * Cuenta los presupuestos creados por un autónomo en un año concreto.
     * Se usa para generar la numeración correlativa anual (P-2026-0001).
     */
    public int contarPorAutonomoYAnio(long autonomoId, int anio) {
        validateAutonomoId(autonomoId);
        String sql = """
                SELECT COUNT(*)
                  FROM presupuestos
                 WHERE autonomo_id = ?
                   AND EXTRACT(YEAR FROM created_at) = ?
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            ps.setInt(2, anio);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new DaoException("Error contando presupuestos del año " + anio +
                    " para autonomo=" + autonomoId, e);
        }
        return 0;
    }

    /**
     * Cuenta los presupuestos creados por un autónomo en el mes natural actual.
     * Se usa para verificar el límite del plan freemium.
     */
    public int contarPorAutonomoEnMes(long autonomoId) {
        validateAutonomoId(autonomoId);
        String sql = """
                SELECT COUNT(*)
                  FROM presupuestos
                 WHERE autonomo_id = ?
                   AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', NOW())
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new DaoException("Error contando presupuestos del mes para autonomo=" + autonomoId, e);
        }
        return 0;
    }

    /**
     * Calcula un resumen de presupuestos de un autónomo en un mes concreto,
     * agrupado por estado.
     *
     * @param autonomoId ID del autónomo
     * @param year       año (ej: 2026)
     * @param month      mes (1-12)
     */
    public ResumenMensual resumenMensual(long autonomoId, int year, int month) {
        validateAutonomoId(autonomoId);
        String sql = """
                SELECT estado, COUNT(*) AS cantidad, COALESCE(SUM(total), 0) AS total_suma
                  FROM presupuestos
                 WHERE autonomo_id = ?
                   AND EXTRACT(YEAR  FROM created_at) = ?
                   AND EXTRACT(MONTH FROM created_at) = ?
                 GROUP BY estado
                """;
        int generados = 0, enviados = 0, aceptados = 0, rechazados = 0, facturados = 0, cancelados = 0;
        java.math.BigDecimal totalPresupuestado = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalAceptado      = java.math.BigDecimal.ZERO;
        java.math.BigDecimal totalFacturado     = java.math.BigDecimal.ZERO;

        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            ps.setInt(2, year);
            ps.setInt(3, month);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String estado = rs.getString("estado");
                    int cantidad = rs.getInt("cantidad");
                    java.math.BigDecimal suma = rs.getBigDecimal("total_suma");

                    generados += cantidad;
                    totalPresupuestado = totalPresupuestado.add(suma);

                    switch (estado) {
                        case Presupuesto.ESTADO_ENVIADO   -> enviados   = cantidad;
                        case Presupuesto.ESTADO_ACEPTADO  -> { aceptados  = cantidad; totalAceptado  = totalAceptado.add(suma); }
                        case Presupuesto.ESTADO_RECHAZADO -> rechazados = cantidad;
                        case Presupuesto.ESTADO_FACTURADO -> { facturados = cantidad; totalFacturado = totalFacturado.add(suma); }
                        case Presupuesto.ESTADO_CANCELADO -> cancelados = cantidad;
                        default -> {} // BORRADOR u otros estados futuros
                    }
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error calculando resumen mensual autonomo=" + autonomoId, e);
        }

        return new ResumenMensual(generados, enviados, aceptados, rechazados, facturados, cancelados,
                totalPresupuestado, totalAceptado, totalFacturado);
    }

    /**
     * Resumen de presupuestos de un mes, agrupado por estado.
     *
     * @param generados          total de presupuestos creados en el mes
     * @param enviados           presupuestos confirmados y enviados al cliente
     * @param aceptados          presupuestos aceptados por el cliente (pendientes de facturar)
     * @param rechazados         presupuestos rechazados por el cliente
     * @param facturados         presupuestos ya facturados en TicketBAI
     * @param cancelados         presupuestos cancelados antes de enviar
     * @param totalPresupuestado suma total de todos los presupuestos del mes
     * @param totalAceptado      suma de los presupuestos en estado ACEPTADO
     * @param totalFacturado     suma de los presupuestos en estado FACTURADO
     */
    public record ResumenMensual(
            int generados, int enviados, int aceptados, int rechazados, int facturados, int cancelados,
            java.math.BigDecimal totalPresupuestado,
            java.math.BigDecimal totalAceptado,
            java.math.BigDecimal totalFacturado
    ) {}

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    /**
     * Inserta un presupuesto nuevo junto con sus líneas de detalle en una transacción.
     *
     * @param p presupuesto a crear (sin ID; se asigna automáticamente)
     * @return el mismo objeto con el ID y {@code createdAt} rellenos
     */
    public Presupuesto crear(Presupuesto p) {
        validateAutonomoId(p.getAutonomoId());
        String sqlPresupuesto = """
                INSERT INTO presupuestos
                    (autonomo_id, cliente_id, numero, estado, cliente_nombre,
                     subtotal, iva_porcentaje, iva_importe, total, audio_transcript, notas)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;

        try (Connection conn = DbUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sqlPresupuesto)) {
                    ps.setLong(1, p.getAutonomoId());
                    setNullableLong(ps, 2, p.getClienteId());
                    ps.setString(3, p.getNumero());
                    ps.setString(4, p.getEstado() != null ? p.getEstado() : Presupuesto.ESTADO_BORRADOR);
                    ps.setString(5, p.getClienteNombre());
                    ps.setBigDecimal(6, p.getSubtotal());
                    ps.setBigDecimal(7, p.getIvaPorcentaje());
                    ps.setBigDecimal(8, p.getIvaImporte());
                    ps.setBigDecimal(9, p.getTotal());
                    ps.setString(10, CryptoUtil.encrypt(p.getAudioTranscript()));
                    ps.setString(11, p.getNotas());

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        p.setId(rs.getLong("id"));
                        p.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    }
                }

                insertarLineas(conn, p.getId(), p.getLineas());
                conn.commit();
                log.info("Presupuesto creado id={} numero={}", p.getId(), p.getNumero());
                return p;

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DaoException("Error creando presupuesto numero=" + p.getNumero(), e);
        }
    }

    /**
     * Actualiza un presupuesto existente y reemplaza todas sus líneas de detalle.
     * La operación es transaccional.
     */
    public void actualizar(Presupuesto p) {
        validateAutonomoId(p.getAutonomoId());
        String sql = """
                UPDATE presupuestos
                   SET cliente_id     = ?,
                       cliente_nombre = ?,
                       notas          = ?,
                       subtotal       = ?,
                       iva_porcentaje = ?,
                       iva_importe    = ?,
                       total          = ?,
                       estado         = ?
                 WHERE id = ? AND autonomo_id = ?
                """;

        try (Connection conn = DbUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    setNullableLong(ps, 1, p.getClienteId());
                    ps.setString(2, p.getClienteNombre());
                    ps.setString(3, p.getNotas());
                    ps.setBigDecimal(4, p.getSubtotal());
                    ps.setBigDecimal(5, p.getIvaPorcentaje());
                    ps.setBigDecimal(6, p.getIvaImporte());
                    ps.setBigDecimal(7, p.getTotal());
                    ps.setString(8, p.getEstado());
                    ps.setLong(9, p.getId());
                    ps.setLong(10, p.getAutonomoId());
                    ps.executeUpdate();
                }

                eliminarLineas(conn, p.getId());
                insertarLineas(conn, p.getId(), p.getLineas());
                conn.commit();
                log.info("Presupuesto actualizado id={}", p.getId());

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DaoException("Error actualizando presupuesto id=" + p.getId(), e);
        }
    }

    /**
     * Cambia el estado de un presupuesto.
     *
     * @param id     ID del presupuesto
     * @param estado nuevo estado (ver constantes en {@link Presupuesto})
     */
    public void actualizarEstado(long id, String estado) {
        String sql = "UPDATE presupuestos SET estado = ? WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, estado);
            ps.setLong(2, id);
            ps.executeUpdate();
            log.info("Estado presupuesto id={} → {}", id, estado);

        } catch (SQLException e) {
            throw new DaoException("Error actualizando estado presupuesto id=" + id, e);
        }
    }

    /**
     * Elimina un presupuesto. Las líneas de detalle se eliminan en cascada por la BD.
     */
    public void eliminar(long id) {
        String sql = "DELETE FROM presupuestos WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            int filas = ps.executeUpdate();
            if (filas == 0) throw new DaoException("No existe el presupuesto id=" + id);
            log.info("Presupuesto eliminado id={}", id);

        } catch (SQLException e) {
            throw new DaoException("Error eliminando presupuesto id=" + id, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private List<Presupuesto> ejecutarListado(String sql, long autonomoId) {
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            return mapearLista(ps);

        } catch (SQLException e) {
            throw new DaoException("Error listando presupuestos autonomo=" + autonomoId, e);
        }
    }

    private List<Presupuesto> mapearLista(PreparedStatement ps) throws SQLException {
        List<Presupuesto> lista = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) lista.add(mapRow(rs));
        }
        return lista;
    }

    private Presupuesto mapRow(ResultSet rs) throws SQLException {
        Presupuesto p = new Presupuesto();
        p.setId(rs.getLong("id"));
        p.setAutonomoId(rs.getLong("autonomo_id"));

        long clienteId = rs.getLong("cliente_id");
        p.setClienteId(rs.wasNull() ? null : clienteId);

        p.setNumero(rs.getString("numero"));
        p.setEstado(rs.getString("estado"));
        p.setClienteNombre(rs.getString("cliente_nombre"));
        p.setSubtotal(rs.getBigDecimal("subtotal"));
        p.setIvaPorcentaje(rs.getBigDecimal("iva_porcentaje"));
        p.setIvaImporte(rs.getBigDecimal("iva_importe"));
        p.setTotal(rs.getBigDecimal("total"));
        p.setAudioTranscript(CryptoUtil.decrypt(rs.getString("audio_transcript")));
        p.setNotas(rs.getString("notas"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) p.setCreatedAt(createdAt.toLocalDateTime());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) p.setUpdatedAt(updatedAt.toLocalDateTime());

        return p;
    }

    private List<LineaDetalle> findLineas(Connection conn, long presupuestoId) throws SQLException {
        String sql = """
                SELECT id, presupuesto_id, concepto, tipo, cantidad, precio_unitario, importe, orden
                  FROM lineas_detalle
                 WHERE presupuesto_id = ?
                 ORDER BY orden, id
                """;
        List<LineaDetalle> lineas = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, presupuestoId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LineaDetalle l = new LineaDetalle();
                    l.setId(rs.getLong("id"));
                    l.setPresupuestoId(rs.getLong("presupuesto_id"));
                    l.setConcepto(rs.getString("concepto"));
                    l.setTipo(rs.getString("tipo"));
                    l.setCantidad(rs.getBigDecimal("cantidad"));
                    l.setPrecioUnitario(rs.getBigDecimal("precio_unitario"));
                    l.setImporte(rs.getBigDecimal("importe"));
                    l.setOrden(rs.getInt("orden"));
                    lineas.add(l);
                }
            }
        }
        return lineas;
    }

    private void insertarLineas(Connection conn, long presupuestoId, List<LineaDetalle> lineas)
            throws SQLException {
        if (lineas == null || lineas.isEmpty()) return;

        String sql = """
                INSERT INTO lineas_detalle
                    (presupuesto_id, concepto, tipo, cantidad, precio_unitario, importe, orden)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < lineas.size(); i++) {
                LineaDetalle l = lineas.get(i);
                ps.setLong(1, presupuestoId);
                ps.setString(2, l.getConcepto());
                ps.setString(3, l.getTipo() != null ? l.getTipo() : LineaDetalle.TIPO_SERVICIO);
                ps.setBigDecimal(4, l.getCantidad());
                ps.setBigDecimal(5, l.getPrecioUnitario());
                ps.setBigDecimal(6, l.getImporte());
                ps.setInt(7, i);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void eliminarLineas(Connection conn, long presupuestoId) throws SQLException {
        String sql = "DELETE FROM lineas_detalle WHERE presupuesto_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, presupuestoId);
            ps.executeUpdate();
        }
    }

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) ps.setLong(index, value);
        else ps.setNull(index, Types.BIGINT);
    }
}
