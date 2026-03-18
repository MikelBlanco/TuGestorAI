package org.gestorai.dao;

import org.gestorai.exception.DaoException;
import org.gestorai.model.Autonomo;
import org.gestorai.util.CryptoUtil;
import org.gestorai.util.DbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;

/**
 * Acceso a datos para {@link Autonomo}.
 */
public class AutonomoDao extends BaseDao {

    private static final Logger log = LoggerFactory.getLogger(AutonomoDao.class);

    private static final String SQL_SELECT = """
            SELECT id, telegram_id, nombre, nif, direccion, telefono, email,
                   nombre_comercial, plan, rgpd_aceptado_at, created_at, updated_at
              FROM autonomos
            """;

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca un autónomo por su ID interno de base de datos.
     */
    public Optional<Autonomo> findById(long id) {
        String sql = SQL_SELECT + " WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            return ejecutarConsultaUnica(ps);

        } catch (SQLException e) {
            throw new DaoException("Error buscando autónomo id=" + id, e);
        }
    }

    /**
     * Busca un autónomo por su ID de Telegram.
     * Es la búsqueda principal al recibir un mensaje del bot.
     */
    public Optional<Autonomo> findByTelegramId(long telegramId) {
        String sql = SQL_SELECT + " WHERE telegram_id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, telegramId);
            return ejecutarConsultaUnica(ps);

        } catch (SQLException e) {
            throw new DaoException("Error buscando autónomo telegram_id=" + telegramId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    /**
     * Inserta un autónomo nuevo. Se usa al registrar por primera vez.
     *
     * @param a autónomo a crear (sin ID)
     * @return el mismo objeto con el ID y {@code createdAt} rellenos
     */
    public Autonomo crear(Autonomo a) {
        String sql = """
                INSERT INTO autonomos
                    (telegram_id, nombre, nif, direccion, telefono, email,
                     nombre_comercial, plan, rgpd_aceptado_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
                RETURNING id, created_at, rgpd_aceptado_at
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, a.getTelegramId());
            ps.setString(2, a.getNombre());
            ps.setString(3, CryptoUtil.encrypt(a.getNif()));
            ps.setString(4, CryptoUtil.encrypt(a.getDireccion()));
            ps.setString(5, CryptoUtil.encrypt(a.getTelefono()));
            ps.setString(6, CryptoUtil.encrypt(a.getEmail()));
            ps.setString(7, a.getNombreComercial());
            ps.setString(8, a.getPlan() != null ? a.getPlan() : Autonomo.PLAN_FREE);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                a.setId(rs.getLong("id"));
                a.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                Timestamp rgpd = rs.getTimestamp("rgpd_aceptado_at");
                if (rgpd != null) a.setRgpdAceptadoAt(rgpd.toLocalDateTime());
            }

            log.info("Autónomo creado id={}", a.getId());
            return a;

        } catch (SQLException e) {
            throw new DaoException("Error creando autónomo telegramId=" + a.getTelegramId(), e);
        }
    }

    /**
     * Actualiza los datos de perfil del autónomo (nombre, NIF, dirección, etc.).
     * No modifica el plan.
     */
    public void actualizarPerfil(Autonomo a) {
        String sql = """
                UPDATE autonomos
                   SET nombre           = ?,
                       nif              = ?,
                       direccion        = ?,
                       telefono         = ?,
                       email            = ?,
                       nombre_comercial = ?
                 WHERE id = ?
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, a.getNombre());
            ps.setString(2, CryptoUtil.encrypt(a.getNif()));
            ps.setString(3, CryptoUtil.encrypt(a.getDireccion()));
            ps.setString(4, CryptoUtil.encrypt(a.getTelefono()));
            ps.setString(5, CryptoUtil.encrypt(a.getEmail()));
            ps.setString(6, a.getNombreComercial());
            ps.setLong(7, a.getId());
            ps.executeUpdate();
            log.info("Perfil actualizado autónomo id={}", a.getId());

        } catch (SQLException e) {
            throw new DaoException("Error actualizando perfil autónomo id=" + a.getId(), e);
        }
    }

    /**
     * Cambia el plan del autónomo ({@code free} o {@code pro}).
     */
    public void actualizarPlan(long id, String plan) {
        String sql = "UPDATE autonomos SET plan = ? WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, plan);
            ps.setLong(2, id);
            ps.executeUpdate();
            log.info("Plan actualizado autónomo id={} → {}", id, plan);

        } catch (SQLException e) {
            throw new DaoException("Error actualizando plan autónomo id=" + id, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private Optional<Autonomo> ejecutarConsultaUnica(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    private Autonomo mapRow(ResultSet rs) throws SQLException {
        Autonomo a = new Autonomo();
        a.setId(rs.getLong("id"));
        a.setTelegramId(rs.getLong("telegram_id"));
        a.setNombre(rs.getString("nombre"));
        a.setNif(CryptoUtil.decrypt(rs.getString("nif")));
        a.setDireccion(CryptoUtil.decrypt(rs.getString("direccion")));
        a.setTelefono(CryptoUtil.decrypt(rs.getString("telefono")));
        a.setEmail(CryptoUtil.decrypt(rs.getString("email")));
        a.setNombreComercial(rs.getString("nombre_comercial"));
        a.setPlan(rs.getString("plan"));

        Timestamp rgpdAceptadoAt = rs.getTimestamp("rgpd_aceptado_at");
        if (rgpdAceptadoAt != null) a.setRgpdAceptadoAt(rgpdAceptadoAt.toLocalDateTime());

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) a.setCreatedAt(createdAt.toLocalDateTime());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) a.setUpdatedAt(updatedAt.toLocalDateTime());

        return a;
    }
}
