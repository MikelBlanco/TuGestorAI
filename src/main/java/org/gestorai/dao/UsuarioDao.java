package org.gestorai.dao;

import org.gestorai.exception.DaoException;
import org.gestorai.model.Usuario;
import org.gestorai.util.DbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.Optional;

/**
 * Acceso a datos para {@link Usuario}.
 */
public class UsuarioDao extends BaseDao {

    private static final Logger log = LoggerFactory.getLogger(UsuarioDao.class);

    private static final String SQL_SELECT = """
            SELECT id, telegram_id, nombre, nif, direccion, telefono, email,
                   nombre_comercial, logo_url, plan, presupuestos_mes, created_at, updated_at
              FROM usuarios
            """;

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca un usuario por su ID interno de base de datos.
     */
    public Optional<Usuario> findById(long id) {
        String sql = SQL_SELECT + " WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            return ejecutarConsultaUnica(ps);

        } catch (SQLException e) {
            throw new DaoException("Error buscando usuario id=" + id, e);
        }
    }

    /**
     * Busca un usuario por su ID de Telegram.
     * Es la búsqueda principal al recibir un mensaje del bot.
     */
    public Optional<Usuario> findByTelegramId(long telegramId) {
        String sql = SQL_SELECT + " WHERE telegram_id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, telegramId);
            return ejecutarConsultaUnica(ps);

        } catch (SQLException e) {
            throw new DaoException("Error buscando usuario telegram_id=" + telegramId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    /**
     * Inserta un usuario nuevo. Se usa al registrar un autónomo por primera vez.
     *
     * @param u usuario a crear (sin ID)
     * @return el mismo objeto con el ID y {@code createdAt} rellenos
     */
    public Usuario crear(Usuario u) {
        String sql = """
                INSERT INTO usuarios
                    (telegram_id, nombre, nif, direccion, telefono, email,
                     nombre_comercial, logo_url, plan, presupuestos_mes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, u.getTelegramId());
            ps.setString(2, u.getNombre());
            ps.setString(3, u.getNif());
            ps.setString(4, u.getDireccion());
            ps.setString(5, u.getTelefono());
            ps.setString(6, u.getEmail());
            ps.setString(7, u.getNombreComercial());
            ps.setString(8, u.getLogoUrl());
            ps.setString(9, u.getPlan() != null ? u.getPlan() : Usuario.PLAN_FREE);
            ps.setInt(10, 0);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                u.setId(rs.getLong("id"));
                u.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }

            log.info("Usuario creado id={} telegramId={}", u.getId(), u.getTelegramId());
            return u;

        } catch (SQLException e) {
            throw new DaoException("Error creando usuario telegramId=" + u.getTelegramId(), e);
        }
    }

    /**
     * Actualiza los datos de perfil del usuario (nombre, NIF, dirección, etc.).
     * No modifica plan ni contadores.
     */
    public void actualizarPerfil(Usuario u) {
        String sql = """
                UPDATE usuarios
                   SET nombre          = ?,
                       nif             = ?,
                       direccion       = ?,
                       telefono        = ?,
                       email           = ?,
                       nombre_comercial = ?,
                       logo_url        = ?
                 WHERE id = ?
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, u.getNombre());
            ps.setString(2, u.getNif());
            ps.setString(3, u.getDireccion());
            ps.setString(4, u.getTelefono());
            ps.setString(5, u.getEmail());
            ps.setString(6, u.getNombreComercial());
            ps.setString(7, u.getLogoUrl());
            ps.setLong(8, u.getId());
            ps.executeUpdate();
            log.info("Perfil actualizado usuario id={}", u.getId());

        } catch (SQLException e) {
            throw new DaoException("Error actualizando perfil usuario id=" + u.getId(), e);
        }
    }

    /**
     * Cambia el plan del usuario ({@code free} o {@code pro}).
     */
    public void actualizarPlan(long id, String plan) {
        String sql = "UPDATE usuarios SET plan = ? WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, plan);
            ps.setLong(2, id);
            ps.executeUpdate();
            log.info("Plan actualizado usuario id={} → {}", id, plan);

        } catch (SQLException e) {
            throw new DaoException("Error actualizando plan usuario id=" + id, e);
        }
    }

    /**
     * Incrementa en 1 el contador de presupuestos del mes en curso.
     * Se llama tras crear un presupuesto satisfactoriamente.
     */
    public void incrementarContadorPresupuestos(long id) {
        String sql = "UPDATE usuarios SET presupuestos_mes = presupuestos_mes + 1 WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new DaoException("Error incrementando contador presupuestos usuario id=" + id, e);
        }
    }

    /**
     * Resetea a 0 el contador mensual de presupuestos de todos los usuarios.
     * Se debe llamar el día 1 de cada mes (tarea programada o manualmente).
     */
    public void resetearContadoresMensuales() {
        String sql = "UPDATE usuarios SET presupuestos_mes = 0";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            int filas = ps.executeUpdate();
            log.info("Contadores mensuales reseteados ({} usuarios)", filas);

        } catch (SQLException e) {
            throw new DaoException("Error reseteando contadores mensuales", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private Optional<Usuario> ejecutarConsultaUnica(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return Optional.of(mapRow(rs));
            }
        }
        return Optional.empty();
    }

    private Usuario mapRow(ResultSet rs) throws SQLException {
        Usuario u = new Usuario();
        u.setId(rs.getLong("id"));
        u.setTelegramId(rs.getLong("telegram_id"));
        u.setNombre(rs.getString("nombre"));
        u.setNif(rs.getString("nif"));
        u.setDireccion(rs.getString("direccion"));
        u.setTelefono(rs.getString("telefono"));
        u.setEmail(rs.getString("email"));
        u.setNombreComercial(rs.getString("nombre_comercial"));
        u.setLogoUrl(rs.getString("logo_url"));
        u.setPlan(rs.getString("plan"));
        u.setPresupuestosMes(rs.getInt("presupuestos_mes"));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) u.setCreatedAt(createdAt.toLocalDateTime());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) u.setUpdatedAt(updatedAt.toLocalDateTime());

        return u;
    }
}