package org.gestorai.dao;

import org.gestorai.exception.DaoException;
import org.gestorai.model.Cliente;
import org.gestorai.util.CryptoUtil;
import org.gestorai.util.DbUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a datos para {@link Cliente}.
 *
 * <p>Todas las consultas filtran por {@code autonomo_id} para garantizar el aislamiento
 * multi-tenant. Los campos sensibles (nif, direccion, telefono, email) se cifran al guardar
 * y se descifran al leer de forma transparente.</p>
 */
public class ClienteDao extends BaseDao {

    private static final Logger log = LoggerFactory.getLogger(ClienteDao.class);

    private static final String SQL_SELECT = """
            SELECT id, autonomo_id, nombre, nif, direccion, telefono, email, created_at, updated_at
              FROM clientes
            """;

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    /**
     * Busca un cliente por su ID, verificando que pertenece al autónomo indicado.
     *
     * @param autonomoId ID del autónomo propietario
     * @param id         ID del cliente a buscar
     * @return el cliente si existe y pertenece al autónomo
     */
    public Optional<Cliente> findById(long autonomoId, long id) {
        validateAutonomoId(autonomoId);
        String sql = SQL_SELECT + " WHERE id = ? AND autonomo_id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, id);
            ps.setLong(2, autonomoId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(mapRow(rs));
            }
        } catch (SQLException e) {
            throw new DaoException("Error buscando cliente id=" + id, e);
        }
        return Optional.empty();
    }

    /**
     * Busca clientes cuyo nombre contiene el término indicado (búsqueda insensible a mayúsculas).
     *
     * <p>Se usa para detectar si un cliente ya existe antes de crear uno nuevo.
     * Devuelve hasta 3 resultados, ordenados por fecha de creación descendente.</p>
     *
     * @param autonomoId ID del autónomo propietario
     * @param nombre     nombre a buscar (se aplica LIKE %nombre%)
     * @return lista de clientes coincidentes (puede estar vacía)
     */
    public List<Cliente> buscarPorNombre(long autonomoId, String nombre) {
        validateAutonomoId(autonomoId);
        if (nombre == null || nombre.isBlank()) return List.of();

        String sql = SQL_SELECT + """
                 WHERE autonomo_id = ?
                   AND LOWER(nombre) LIKE ?
                 ORDER BY created_at DESC
                 LIMIT 3
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, autonomoId);
            ps.setString(2, "%" + nombre.toLowerCase() + "%");

            List<Cliente> lista = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) lista.add(mapRow(rs));
            }
            return lista;

        } catch (SQLException e) {
            throw new DaoException("Error buscando clientes por nombre autonomo_id=" + autonomoId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Escritura
    // -------------------------------------------------------------------------

    /**
     * Inserta un cliente nuevo.
     *
     * @param c cliente a crear (sin ID); los campos sensibles se cifran automáticamente
     * @return el mismo objeto con el ID y {@code createdAt} rellenos
     */
    public Cliente crear(Cliente c) {
        validateAutonomoId(c.getAutonomoId());
        String sql = """
                INSERT INTO clientes (autonomo_id, nombre, nif, direccion, telefono, email)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id, created_at
                """;
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, c.getAutonomoId());
            ps.setString(2, c.getNombre());
            ps.setString(3, CryptoUtil.encrypt(c.getNif()));
            ps.setString(4, CryptoUtil.encrypt(c.getDireccion()));
            ps.setString(5, CryptoUtil.encrypt(c.getTelefono()));
            ps.setString(6, CryptoUtil.encrypt(c.getEmail()));

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                c.setId(rs.getLong("id"));
                c.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            }

            log.info("Cliente creado id={} autonomo_id={}", c.getId(), c.getAutonomoId());
            return c;

        } catch (SQLException e) {
            throw new DaoException("Error creando cliente autonomo_id=" + c.getAutonomoId(), e);
        }
    }

    /**
     * Actualiza el NIF de un cliente existente.
     *
     * <p>Se llama cuando un presupuesto se convierte en factura y se solicita el NIF
     * para los datos fiscales obligatorios.</p>
     *
     * @param autonomoId ID del autónomo propietario (para validar aislamiento)
     * @param clienteId  ID del cliente a actualizar
     * @param nif        NIF del cliente (se cifra automáticamente)
     */
    public void actualizarNif(long autonomoId, long clienteId, String nif) {
        validateAutonomoId(autonomoId);
        String sql = "UPDATE clientes SET nif = ? WHERE id = ? AND autonomo_id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, CryptoUtil.encrypt(nif));
            ps.setLong(2, clienteId);
            ps.setLong(3, autonomoId);
            ps.executeUpdate();
            log.info("NIF actualizado cliente_id={}", clienteId);

        } catch (SQLException e) {
            throw new DaoException("Error actualizando NIF cliente id=" + clienteId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers privados
    // -------------------------------------------------------------------------

    private Cliente mapRow(ResultSet rs) throws SQLException {
        Cliente c = new Cliente();
        c.setId(rs.getLong("id"));
        c.setAutonomoId(rs.getLong("autonomo_id"));
        c.setNombre(rs.getString("nombre"));
        c.setNif(CryptoUtil.decrypt(rs.getString("nif")));
        c.setDireccion(CryptoUtil.decrypt(rs.getString("direccion")));
        c.setTelefono(CryptoUtil.decrypt(rs.getString("telefono")));
        c.setEmail(CryptoUtil.decrypt(rs.getString("email")));

        Timestamp createdAt = rs.getTimestamp("created_at");
        if (createdAt != null) c.setCreatedAt(createdAt.toLocalDateTime());

        Timestamp updatedAt = rs.getTimestamp("updated_at");
        if (updatedAt != null) c.setUpdatedAt(updatedAt.toLocalDateTime());

        return c;
    }
}
