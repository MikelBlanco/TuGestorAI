package org.gestorai.dao;

import org.gestorai.exception.DaoException;
import org.gestorai.model.Factura;
import org.gestorai.model.LineaDetalle;
import org.gestorai.util.DbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a datos para {@link Factura} y sus {@link LineaDetalle}.
 * Las operaciones de escritura que afectan a ambas tablas son transaccionales.
 */
public class FacturaDao extends BaseDao {

    private static final Logger log = LoggerFactory.getLogger(FacturaDao.class);

    private static final String SQL_SELECT = """
            SELECT id, numero, usuario_id, presupuesto_id, cliente_id, cliente_nombre,
                   descripcion, subtotal, iva_porcentaje, iva_importe,
                   irpf_porcentaje, irpf_importe, total, estado, pdf_path,
                   created_at, updated_at
              FROM facturas
            """;

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca una factura por su ID, incluyendo sus líneas de detalle.
     */
    public Optional<Factura> findById(long id) {
        String sql = SQL_SELECT + " WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Factura f = mapRow(rs);
                    f.setLineas(findLineas(conn, f.getId()));
                    return Optional.of(f);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error buscando factura id=" + id, e);
        }
        return Optional.empty();
    }

    /**
     * Lista todas las facturas de un usuario, sin líneas de detalle.
     */
    public List<Factura> findByUsuarioId(long usuarioId) {
        String sql = SQL_SELECT + " WHERE usuario_id = ? ORDER BY created_at DESC";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            return mapearLista(ps);
        } catch (SQLException e) {
            throw new DaoException("Error listando facturas usuario=" + usuarioId, e);
        }
    }

    /**
     * Lista las facturas de un usuario filtradas por estado.
     */
    public List<Factura> findByUsuarioIdAndEstado(long usuarioId, String estado) {
        String sql = SQL_SELECT + " WHERE usuario_id = ? AND estado = ? ORDER BY created_at DESC";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            ps.setString(2, estado);
            return mapearLista(ps);
        } catch (SQLException e) {
            throw new DaoException("Error listando facturas usuario=" + usuarioId + " estado=" + estado, e);
        }
    }

    /**
     * Cuenta las facturas de un usuario en un año. Usado para numeración correlativa.
     */
    public int contarPorUsuarioYAnio(long usuarioId, int anio) {
        String sql = """
                SELECT COUNT(*)
                  FROM facturas
                 WHERE usuario_id = ?
                   AND EXTRACT(YEAR FROM created_at) = ?
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, usuarioId);
            ps.setInt(2, anio);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new DaoException("Error contando facturas del año " + anio +
                    " para usuario=" + usuarioId, e);
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    /**
     * Inserta una factura nueva junto con sus líneas de detalle en una transacción.
     *
     * @param f factura a crear (sin ID)
     * @return la misma factura con ID y {@code createdAt} rellenos
     */
    public Factura crear(Factura f) {
        String sql = """
                INSERT INTO facturas
                    (numero, usuario_id, presupuesto_id, cliente_id, cliente_nombre,
                     descripcion, subtotal, iva_porcentaje, iva_importe,
                     irpf_porcentaje, irpf_importe, total, estado)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;

        try (Connection conn = DbUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, f.getNumero());
                    ps.setLong(2, f.getUsuarioId());
                    setNullableLong(ps, 3, f.getPresupuestoId());
                    setNullableLong(ps, 4, f.getClienteId());
                    ps.setString(5, f.getClienteNombre());
                    ps.setString(6, f.getDescripcion());
                    ps.setBigDecimal(7, f.getSubtotal());
                    ps.setBigDecimal(8, f.getIvaPorcentaje());
                    ps.setBigDecimal(9, f.getIvaImporte());
                    ps.setBigDecimal(10, f.getIrpfPorcentaje());
                    ps.setBigDecimal(11, f.getIrpfImporte());
                    ps.setBigDecimal(12, f.getTotal());
                    ps.setString(13, f.getEstado() != null ? f.getEstado() : Factura.ESTADO_BORRADOR);

                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        f.setId(rs.getLong("id"));
                        f.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                    }
                }

                insertarLineas(conn, f.getId(), f.getLineas());
                conn.commit();
                log.info("Factura creada id={} numero={}", f.getId(), f.getNumero());
                return f;

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new DaoException("Error creando factura numero=" + f.getNumero(), e);
        }
    }

    /**
     * Cambia el estado de una factura.
     */
    public void actualizarEstado(long id, String estado) {
        String sql = "UPDATE facturas SET estado = ? WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, estado);
            ps.setLong(2, id);
            ps.executeUpdate();
            log.info("Estado factura id={} → {}", id, estado);

        } catch (SQLException e) {
            throw new DaoException("Error actualizando estado factura id=" + id, e);
        }
    }

    /**
     * Guarda la ruta del PDF generado.
     */
    public void actualizarPdfPath(long id, String pdfPath) {
        String sql = "UPDATE facturas SET pdf_path = ? WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, pdfPath);
            ps.setLong(2, id);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new DaoException("Error actualizando pdf_path factura id=" + id, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private List<Factura> mapearLista(PreparedStatement ps) throws SQLException {
        List<Factura> lista = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) lista.add(mapRow(rs));
        }
        return lista;
    }

    private Factura mapRow(ResultSet rs) throws SQLException {
        Factura f = new Factura();
        f.setId(rs.getLong("id"));
        f.setNumero(rs.getString("numero"));
        f.setUsuarioId(rs.getLong("usuario_id"));

        long presupuestoId = rs.getLong("presupuesto_id");
        f.setPresupuestoId(rs.wasNull() ? null : presupuestoId);

        long clienteId = rs.getLong("cliente_id");
        f.setClienteId(rs.wasNull() ? null : clienteId);

        f.setClienteNombre(rs.getString("cliente_nombre"));
        f.setDescripcion(rs.getString("descripcion"));
        f.setSubtotal(rs.getBigDecimal("subtotal"));
        f.setIvaPorcentaje(rs.getBigDecimal("iva_porcentaje"));
        f.setIvaImporte(rs.getBigDecimal("iva_importe"));
        f.setIrpfPorcentaje(rs.getBigDecimal("irpf_porcentaje"));
        f.setIrpfImporte(rs.getBigDecimal("irpf_importe"));
        f.setTotal(rs.getBigDecimal("total"));
        f.setEstado(rs.getString("estado"));
        f.setPdfPath(rs.getString("pdf_path"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) f.setCreatedAt(createdAt.toLocalDateTime());
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) f.setUpdatedAt(updatedAt.toLocalDateTime());

        return f;
    }

    private List<LineaDetalle> findLineas(Connection conn, long facturaId) throws SQLException {
        String sql = """
                SELECT id, factura_id, concepto, cantidad, precio_unitario, importe, tipo, orden
                  FROM lineas_detalle
                 WHERE factura_id = ?
                 ORDER BY orden, id
                """;
        List<LineaDetalle> lineas = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, facturaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LineaDetalle l = new LineaDetalle();
                    l.setId(rs.getLong("id"));
                    l.setFacturaId(rs.getLong("factura_id"));
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

    private void insertarLineas(Connection conn, long facturaId, List<LineaDetalle> lineas)
            throws SQLException {
        if (lineas == null || lineas.isEmpty()) return;

        String sql = """
                INSERT INTO lineas_detalle
                    (factura_id, concepto, cantidad, precio_unitario, importe, tipo, orden)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < lineas.size(); i++) {
                LineaDetalle l = lineas.get(i);
                ps.setLong(1, facturaId);
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

    private void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value != null) ps.setLong(index, value);
        else ps.setNull(index, Types.BIGINT);
    }
}