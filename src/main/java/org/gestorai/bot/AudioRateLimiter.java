package org.gestorai.bot;

import org.gestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controla los límites de uso de audios por usuario y globalmente,
 * para proteger los costes de las APIs externas (Whisper, Claude).
 *
 * <p>Todos los límites son configurables en {@code config.properties}:
 * <ul>
 *   <li>{@code limites.audio.duracion.max.seg}    — duración máxima en segundos (defecto: 180)</li>
 *   <li>{@code limites.audio.tamano.max.bytes}    — tamaño máximo en bytes (defecto: 1048576 = 1 MB)</li>
 *   <li>{@code limites.audio.usuario.hora}        — audios/hora por usuario (defecto: 10)</li>
 *   <li>{@code limites.audio.usuario.dia}         — audios/día por usuario (defecto: 30)</li>
 *   <li>{@code limites.coste.diario.max.audios}   — audios globales/día antes de bloquear (defecto: 428 ≈ €3/día a €0,007 por audio)</li>
 * </ul>
 */
public class AudioRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AudioRateLimiter.class);

    private static final AudioRateLimiter INSTANCE = new AudioRateLimiter();

    /** Duración máxima admitida (segundos). */
    public final int maxDuracionSeg;
    /** Tamaño máximo admitido (bytes). */
    public final int maxTamanioBytes;
    /** Máximo de audios procesados por usuario en una hora. */
    public final int maxAudiosHoraUsuario;
    /** Máximo de audios procesados por usuario en un día. */
    public final int maxAudiosDiaUsuario;
    /** Máximo de audios procesados globalmente en un día (tope de coste). */
    public final int maxAudiosDiaGlobal;

    // ventanas deslizantes por usuario
    private final ConcurrentHashMap<Long, Deque<Instant>> ventanaHora = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Deque<Instant>> ventanaDia  = new ConcurrentHashMap<>();

    // contador global diario con reset automático al cambiar de día
    private final AtomicInteger contadorGlobal = new AtomicInteger(0);
    private volatile LocalDate fechaContador = LocalDate.now();

    private AudioRateLimiter() {
        maxDuracionSeg       = parseInt(ConfigUtil.get("limites.audio.duracion.max.seg"),  180);
        maxTamanioBytes      = parseInt(ConfigUtil.get("limites.audio.tamano.max.bytes"),  1_048_576);
        maxAudiosHoraUsuario = parseInt(ConfigUtil.get("limites.audio.usuario.hora"),      10);
        maxAudiosDiaUsuario  = parseInt(ConfigUtil.get("limites.audio.usuario.dia"),       30);
        maxAudiosDiaGlobal   = parseInt(ConfigUtil.get("limites.coste.diario.max.audios"), 428);
    }

    public static AudioRateLimiter getInstance() {
        return INSTANCE;
    }

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /** Resultado de una comprobación de límites. */
    public enum Resultado {
        OK,
        DURACION_EXCEDIDA,
        TAMANO_EXCEDIDO,
        LIMITE_HORA_USUARIO,
        LIMITE_DIA_USUARIO,
        LIMITE_COSTE_GLOBAL
    }

    /**
     * Comprueba si el audio puede procesarse sin modificar los contadores.
     * Llamar a {@link #registrar(long)} solo si el procesamiento termina con éxito.
     *
     * @param telegramId  identificador del usuario en Telegram
     * @param duracionSeg duración del audio en segundos
     * @param tamanioBytes tamaño del fichero en bytes
     * @return {@link Resultado#OK} si no se supera ningún límite, o el primer límite superado
     */
    public Resultado comprobar(long telegramId, int duracionSeg, int tamanioBytes) {
        if (duracionSeg > maxDuracionSeg)    return Resultado.DURACION_EXCEDIDA;
        if (tamanioBytes > maxTamanioBytes)  return Resultado.TAMANO_EXCEDIDO;

        Instant ahora = Instant.now();
        if (contarVentana(ventanaHora, telegramId, ahora, 3_600) >= maxAudiosHoraUsuario)
            return Resultado.LIMITE_HORA_USUARIO;
        if (contarVentana(ventanaDia,  telegramId, ahora, 86_400) >= maxAudiosDiaUsuario)
            return Resultado.LIMITE_DIA_USUARIO;
        if (contadorGlobalHoy() >= maxAudiosDiaGlobal)
            return Resultado.LIMITE_COSTE_GLOBAL;

        return Resultado.OK;
    }

    /**
     * Registra un audio procesado con éxito en los contadores de ventana deslizante.
     *
     * @param telegramId identificador del usuario en Telegram
     */
    public void registrar(long telegramId) {
        Instant ahora = Instant.now();
        registrarEnVentana(ventanaHora, telegramId, ahora);
        registrarEnVentana(ventanaDia,  telegramId, ahora);
        contadorGlobalHoy(); // sincroniza reset de fecha antes de incrementar
        int total = contadorGlobal.incrementAndGet();
        log.info("Audio registrado: telegramId={} globalHoy={}", telegramId, total);
    }

    // -------------------------------------------------------------------------
    // Internos
    // -------------------------------------------------------------------------

    private int contarVentana(ConcurrentHashMap<Long, Deque<Instant>> mapa,
                               long telegramId, Instant ahora, long ventanaSeg) {
        Deque<Instant> deque = mapa.computeIfAbsent(telegramId, k -> new ArrayDeque<>());
        synchronized (deque) {
            Instant limite = ahora.minusSeconds(ventanaSeg);
            while (!deque.isEmpty() && deque.peekFirst().isBefore(limite)) {
                deque.pollFirst();
            }
            return deque.size();
        }
    }

    private void registrarEnVentana(ConcurrentHashMap<Long, Deque<Instant>> mapa,
                                    long telegramId, Instant ahora) {
        Deque<Instant> deque = mapa.computeIfAbsent(telegramId, k -> new ArrayDeque<>());
        synchronized (deque) {
            deque.addLast(ahora);
        }
    }

    /** Devuelve el contador global del día y lo resetea si ha cambiado la fecha. */
    private int contadorGlobalHoy() {
        LocalDate hoy = LocalDate.now();
        if (!hoy.equals(fechaContador)) {
            synchronized (this) {
                if (!hoy.equals(fechaContador)) {
                    log.info("Nuevo día ({}): reseteando contador global de audios", hoy);
                    contadorGlobal.set(0);
                    fechaContador = hoy;
                }
            }
        }
        return contadorGlobal.get();
    }

    private static int parseInt(String valor, int defecto) {
        if (valor == null || valor.isBlank()) return defecto;
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException e) {
            return defecto;
        }
    }
}
