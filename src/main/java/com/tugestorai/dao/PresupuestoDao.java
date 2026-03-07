package com.tugestorai.dao;

import com.tugestorai.exception.DaoException;
import com.tugestorai.model.LineaDetalle;
import com.tugestorai.model.Presupuesto;
import com.tugestorai.util.DbUtil;
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

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca un presupuesto por su ID, incluyendo sus líneas de detalle.
     *
     * @param id clave primaria
     * @return el presupuesto, o {@link Optional#empty()} si no existe
     */
    public Optional<Presupuesto> findById(long id) {
        String sql = """
                SELECT id, numero, usuario_id, cliente_id, cliente_nombre, descripcion,
                       subtotal, iva_porcentaje, iva_importe, total, estado,
                       audio_transcript, pdf_path, created_at, updated_at, enviado_at
                  FROM presupuestos
                 WHERE id = ?
                """;
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
     * Lista todos los presupuestos de un usuario, sin cargar sus líneas de detalle.
     *
     * @param usuarioId ID del autónomo
     * @return lista ordenada por fecha de creación descendente
     */
    public List<Presupuesto> findByUsuarioId(long usuarioId) {
        String sql = """
                SELECT id, numero, usuario_id, cliente_id, cliente_nombre, descripcion,
                       subtotal, iva_porcentaje, iva_importe, total, estado,
                       audio_transcript, pdf_path, created_at, updated_at, enviado_at
                  FROM presupuestos
                 WHERE usuario_id = ?
                 ORDER BY created_at DESC
                """;
        return ejecutarListado(sql, usuarioId);
    }

    /**
     * Lista los presupuestos de un usuario filtrados por estado, sin líneas de detalle.
     *
     * @param usuarioId ID del autónomo
     * @param estado    valor del campo {@code estado} (ver constantes en {@link Presupuesto})
     */
    public List<Presupuesto> findByUsuarioIdAndEstado(long usuarioId, String estado) {
        String sql = """
                SELECT id, numero, usuario_id, cliente_id, cliente_nombre, descripcion,
                       subtotal, iva_porcentaje, iva_importe, total, estado,
                       audio_transcript, pdf_path, created_at, updated_at, enviado_at
                  FROM presupuestos
                 WHERE usuario_id = ? AND estado = ?
                 ORDER BY created_at DESC
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            ps.setString(2, estado);
            return mapearLista(ps);
        } catch (SQLException e) {
            throw new DaoException("Error listando presupuestos usuario=" + usuarioId + " estado=" + estado, e);
        }
    }

    /**
     * Cuenta los presupuestos creados por un usuario en el mes natural actual.
     * Se usa para verificar el límite del plan freemium.
     *
     * @param usuarioId ID del autónomo
     * @return número de presupuestos del mes en curso
     */
    /**
     * Cuenta los presupuestos creados por un usuario en un año concreto.
     * Se usa para generar la numeración correlativa anual (P-2026-0001).
     *
     * @param usuarioId ID del autónomo
     * @param anio      año de cuatro dígitos
     * @return número de presupuestos del año
     */
    public int contarPorUsuarioYAnio(long usuarioId, int anio) {
        String sql = """
                SELECT COUNT(*)
                  FROM presupuestos
                 WHERE usuario_id = ?
                   AND EXTRACT(YEAR FROM created_at) = ?
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            ps.setInt(2, anio);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error contando presupuestos del año " + anio +
                    " para usuario=" + usuarioId, e);
        }
        return 0;
    }

    public int contarPorUsuarioEnMes(long usuarioId) {
        String sql = """
                SELECT COUNT(*)
                  FROM presupuestos
                 WHERE usuario_id = ?
                   AND DATE_TRUNC('month', created_at) = DATE_TRUNC('month', NOW())
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error contando presupuestos del mes para usuario=" + usuarioId, e);
        }
        return 0;
    }

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
        String sqlPresupuesto = """
                INSERT INTO presupuestos
                    (numero, usuario_id, cliente_id, cliente_nombre, descripcion,
                     subtotal, iva_porcentaje, iva_importe, total, estado, audio_transcript)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;

        try (Connection conn = DbUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sqlPresupuesto)) {
                    ps.setString(1, p.getNumero());
                    ps.setLong(2, p.getUsuarioId());
                    setNullableLong(ps, 3, p.getClienteId());
                    ps.setString(4, p.getClienteNombre());
                    ps.setString(5, p.getDescripcion());
                    ps.setBigDecimal(6, p.getSubtotal());
                    ps.setBigDecimal(7, p.getIvaPorcentaje());
                    ps.setBigDecimal(8, p.getIvaImporte());
                    ps.setBigDecimal(9, p.getTotal());
                    ps.setString(10, p.getEstado() != null ? p.getEstado() : Presupuesto.ESTADO_BORRADOR);
                    ps.setString(11, p.getAudioTranscript());

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
     *
     * @param p presupuesto con los nuevos datos (debe tener ID)
     */
    public void actualizar(Presupuesto p) {
        String sql = """
                UPDATE presupuestos
                   SET numero         = ?,
                       cliente_id     = ?,
                       cliente_nombre = ?,
                       descripcion    = ?,
                       subtotal       = ?,
                       iva_porcentaje = ?,
                       iva_importe    = ?,
                       total          = ?,
                       estado         = ?
                 WHERE id = ?
                """;

        try (Connection conn = DbUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, p.getNumero());
                    setNullableLong(ps, 2, p.getClienteId());
                    ps.setString(3, p.getClienteNombre());
                    ps.setString(4, p.getDescripcion());
                    ps.setBigDecimal(5, p.getSubtotal());
                    ps.setBigDecimal(6, p.getIvaPorcentaje());
                    ps.setBigDecimal(7, p.getIvaImporte());
                    ps.setBigDecimal(8, p.getTotal());
                    ps.setString(9, p.getEstado());
                    ps.setLong(10, p.getId());
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
        String sql;
        if (Presupuesto.ESTADO_ENVIADO.equals(estado)) {
            sql = "UPDATE presupuestos SET estado = ?, enviado_at = NOW() WHERE id = ?";
        } else {
            sql = "UPDATE presupuestos SET estado = ? WHERE id = ?";
        }

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
     * Guarda la ruta del PDF generado.
     *
     * @param id      ID del presupuesto
     * @param pdfPath ruta absoluta o relativa al fichero PDF
     */
    public void actualizarPdfPath(long id, String pdfPath) {
        String sql = "UPDATE presupuestos SET pdf_path = ? WHERE id = ?";

        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, pdfPath);
            ps.setLong(2, id);
            ps.executeUpdate();
            log.debug("PDF path actualizado presupuesto id={}", id);

        } catch (SQLException e) {
            throw new DaoException("Error actualizando pdf_path presupuesto id=" + id, e);
        }
    }

    /**
     * Elimina un presupuesto. Las líneas de detalle se eliminan en cascada por la BD.
     *
     * @param id ID del presupuesto a eliminar
     */
    public void eliminar(long id) {
        String sql = "DELETE FROM presupuestos WHERE id = ?";

        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            int filas = ps.executeUpdate();
            if (filas == 0) {
                throw new DaoException("No existe el presupuesto id=" + id);
            }
            log.info("Presupuesto eliminado id={}", id);

        } catch (SQLException e) {
            throw new DaoException("Error eliminando presupuesto id=" + id, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private List<Presupuesto> ejecutarListado(String sql, long usuarioId) {
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            return mapearLista(ps);

        } catch (SQLException e) {
            throw new DaoException("Error listando presupuestos usuario=" + usuarioId, e);
        }
    }

    private List<Presupuesto> mapearLista(PreparedStatement ps) throws SQLException {
        List<Presupuesto> lista = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(mapRow(rs));
            }
        }
        return lista;
    }

    private Presupuesto mapRow(ResultSet rs) throws SQLException {
        Presupuesto p = new Presupuesto();
        p.setId(rs.getLong("id"));
        p.setNumero(rs.getString("numero"));
        p.setUsuarioId(rs.getLong("usuario_id"));

        long clienteId = rs.getLong("cliente_id");
        p.setClienteId(rs.wasNull() ? null : clienteId);

        p.setClienteNombre(rs.getString("cliente_nombre"));
        p.setDescripcion(rs.getString("descripcion"));
        p.setSubtotal(rs.getBigDecimal("subtotal"));
        p.setIvaPorcentaje(rs.getBigDecimal("iva_porcentaje"));
        p.setIvaImporte(rs.getBigDecimal("iva_importe"));
        p.setTotal(rs.getBigDecimal("total"));
        p.setEstado(rs.getString("estado"));
        p.setAudioTranscript(rs.getString("audio_transcript"));
        p.setPdfPath(rs.getString("pdf_path"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) p.setCreatedAt(createdAt.toLocalDateTime());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) p.setUpdatedAt(updatedAt.toLocalDateTime());

        Timestamp enviadoAt = rs.getTimestamp("enviado_at");
        if (enviadoAt != null) p.setEnviadoAt(enviadoAt.toLocalDateTime());

        return p;
    }

    private List<LineaDetalle> findLineas(Connection conn, long presupuestoId) throws SQLException {
        String sql = """
                SELECT id, presupuesto_id, concepto, cantidad, precio_unitario, importe, tipo, orden
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
                    l.setCantidad(rs.getBigDecimal("cantidad"));
                    l.setPrecioUnitario(rs.getBigDecimal("precio_unitario"));
                    l.setImporte(rs.getBigDecimal("importe"));
                    l.setTipo(rs.getString("tipo"));
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
                    (presupuesto_id, concepto, cantidad, precio_unitario, importe, tipo, orden)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < lineas.size(); i++) {
                LineaDetalle l = lineas.get(i);
                ps.setLong(1, presupuestoId);
                ps.setString(2, l.getConcepto());
                ps.setBigDecimal(3, l.getCantidad());
                ps.setBigDecimal(4, l.getPrecioUnitario());
                ps.setBigDecimal(5, l.getImporte());
                ps.setString(6, l.getTipo() != null ? l.getTipo() : LineaDetalle.TIPO_SERVICIO);
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
        if (value != null) {
            ps.setLong(index, value);
        } else {
            ps.setNull(index, Types.BIGINT);
        }
    }
}