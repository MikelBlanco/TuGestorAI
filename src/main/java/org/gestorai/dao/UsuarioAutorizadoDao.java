package org.gestorai.dao;

import org.gestorai.exception.DaoException;
import org.gestorai.util.DbUtil;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Acceso a la tabla {@code usuarios_autorizados}.
 *
 * <p>Usada por {@link org.gestorai.bot.TuGestorBot} para el control de acceso al bot.
 * Los IDs se cachean en memoria con TTL de 5 minutos para no consultar la BD en cada mensaje.</p>
 *
 * <p>Esquema mínimo esperado:</p>
 * <pre>
 * CREATE TABLE usuarios_autorizados (
 *     telegram_id   BIGINT PRIMARY KEY,
 *     autorizado_por VARCHAR(200),
 *     created_at    TIMESTAMP DEFAULT NOW()
 * );
 * </pre>
 */
public class UsuarioAutorizadoDao extends BaseDao {

    /**
     * Devuelve el conjunto de todos los {@code telegram_id} autorizados.
     *
     * @return conjunto de IDs (nunca {@code null}, puede estar vacío)
     */
    public Set<Long> findAllTelegramIds() {
        String sql = "SELECT telegram_id FROM usuarios_autorizados";
        Set<Long> ids = new HashSet<>();
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong("telegram_id"));
            }
        } catch (SQLException e) {
            throw new DaoException("Error cargando usuarios autorizados", e);
        }
        return ids;
    }
}
