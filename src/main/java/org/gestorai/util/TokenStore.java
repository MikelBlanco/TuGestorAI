package org.gestorai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Almacén en memoria de tokens de un solo uso para autenticación en el panel web.
 *
 * <p>El autónomo genera un token desde el bot con /panel. El token es válido durante
 * {@link #TTL_MS} milisegundos y se elimina tras el primer uso.
 *
 * <p>Singleton de aplicación: obtener instancia con {@link #getInstance()}.
 */
public class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    /** Tiempo de vida del token: 10 minutos. */
    private static final long TTL_MS = 10 * 60 * 1000L;

    private static final TokenStore INSTANCE = new TokenStore();

    private TokenStore() {}

    public static TokenStore getInstance() {
        return INSTANCE;
    }

    // token → [autonomoId, expiresAt]
    private final Map<String, long[]> tokens = new ConcurrentHashMap<>();

    /**
     * Genera un nuevo token de un solo uso para el autónomo indicado.
     *
     * @param autonomoId ID interno del autónomo
     * @return token UUID aleatorio
     */
    public String generarToken(long autonomoId) {
        limpiarExpirados();
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, new long[]{autonomoId, System.currentTimeMillis() + TTL_MS});
        log.info("Token generado para autonomoId={}", autonomoId);
        return token;
    }

    /**
     * Valida y consume un token. Solo puede usarse una vez.
     *
     * @param token el token recibido del autónomo
     * @return el autonomoId asociado, o vacío si el token es inválido o ha expirado
     */
    public Optional<Long> validarToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();

        long[] entry = tokens.remove(token);
        if (entry == null) {
            log.warn("Token no encontrado o ya consumido");
            return Optional.empty();
        }
        if (System.currentTimeMillis() > entry[1]) {
            log.warn("Token expirado para autonomoId={}", entry[0]);
            return Optional.empty();
        }
        log.info("Token válido consumido para autonomoId={}", entry[0]);
        return Optional.of(entry[0]);
    }

    private void limpiarExpirados() {
        long ahora = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> ahora > e.getValue()[1]);
    }
}
