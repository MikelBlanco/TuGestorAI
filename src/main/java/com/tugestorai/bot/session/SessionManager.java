package com.tugestorai.bot.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gestiona las sesiones conversacionales de los usuarios del bot.
 * Las sesiones expiran tras 30 minutos de inactividad.
 * Es un singleton — una sola instancia compartida por todos los handlers.
 */
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);
    private static final Duration TTL = Duration.ofMinutes(30);

    private static final SessionManager INSTANCE = new SessionManager();

    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService limpiador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-cleaner");
        t.setDaemon(true);
        return t;
    });

    private SessionManager() {
        // Limpieza cada 10 minutos
        limpiador.scheduleAtFixedRate(this::limpiarExpiradas, 10, 10, TimeUnit.MINUTES);
    }

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    /**
     * Devuelve la sesión del usuario o crea una nueva si no existe.
     */
    public UserSession getOrCreate(long chatId) {
        return sessions.computeIfAbsent(chatId, UserSession::new);
    }

    /**
     * Elimina la sesión de un usuario (logout, cancelación definitiva).
     */
    public void eliminar(long chatId) {
        sessions.remove(chatId);
    }

    private void limpiarExpiradas() {
        Instant ahora = Instant.now();
        int antes = sessions.size();
        sessions.entrySet().removeIf(e ->
                Duration.between(e.getValue().getLastActivity(), ahora).compareTo(TTL) > 0
        );
        int eliminadas = antes - sessions.size();
        if (eliminadas > 0) {
            log.debug("Sesiones expiradas eliminadas: {}", eliminadas);
        }
    }

    /** Llama al apagar la aplicación para liberar el executor. */
    public void shutdown() {
        limpiador.shutdownNow();
    }
}