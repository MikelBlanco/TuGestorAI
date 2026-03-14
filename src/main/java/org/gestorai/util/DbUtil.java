package org.gestorai.util;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Gestiona el pool de conexiones a PostgreSQL mediante HikariCP.
 * Configuración leída de {@link ConfigUtil}:
 * <ul>
 *   <li>{@code db.url} — URL JDBC (ej. {@code jdbc:postgresql://localhost:5432/tugestorai})</li>
 *   <li>{@code db.user} — usuario de la base de datos</li>
 *   <li>{@code db.password} — contraseña</li>
 *   <li>{@code db.pool.max} — tamaño máximo del pool (defecto: 10)</li>
 * </ul>
 */
public class DbUtil {

    private static final Logger log = LoggerFactory.getLogger(DbUtil.class);
    private static final HikariDataSource dataSource;

    static {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Driver PostgreSQL no encontrado en el classpath", e);
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ConfigUtil.get("db.url"));
        config.setUsername(ConfigUtil.get("db.user"));
        config.setPassword(ConfigUtil.get("db.password"));
        config.setMaximumPoolSize(Integer.parseInt(ConfigUtil.get("db.pool.max", "10")));
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        dataSource = new HikariDataSource(config);
        log.info("Pool de conexiones inicializado: {}", ConfigUtil.get("db.url"));
    }

    private DbUtil() {}

    /** Devuelve una conexión del pool. El llamador es responsable de cerrarla. */
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** Cierra el pool. Llamar al detener la aplicación. */
    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
