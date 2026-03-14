package org.gestorai.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controla los límites de uso de peticiones (audio y texto) por usuario y globalmente,
 * para proteger los costes de las APIs externas (Whisper, Claude).
 *
 * <p>Los contadores de rate limit son <b>compartidos</b> entre audio y texto (mismo contador).</p>
 *
 * <p>Configuración en {@code config.properties}:</p>
 * <ul>
 *   <li>{@code limit.audio.max.duration}  — duración máxima de audio en segundos (def: 180)</li>
 *   <li>{@code limit.audio.max.size}      — tamaño máximo de audio en bytes (def: 1048576)</li>
 *   <li>{@code limit.requests.per.hour}   — peticiones/hora por usuario, audio+texto (def: 10)</li>
 *   <li>{@code limit.requests.per.day}    — peticiones/día por usuario, audio+texto (def: 30)</li>
 *   <li>{@code limit.cost.daily.max}      — coste diario global máximo en euros (def: 3.00)</li>
 *   <li>{@code limit.email.per.day}       — emails/día por usuario (def: 20)</li>
 * </ul>
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private static final RateLimiter INSTANCE = new RateLimiter();

    /** Coste estimado en millieuros por petición de audio (€0.007 = 7 mE). */
    private static final long COSTE_AUDIO_MILLIEUROS = 7;
    /** Coste estimado en millieuros por petición de texto (€0.001 = 1 mE). */
    private static final long COSTE_TEXTO_MILLIEUROS = 1;

    /** Duración máxima admitida para un audio (segundos). */
    public final int maxDuracionSeg;
    /** Tamaño máximo admitido para un audio (bytes). */
    public final int maxTamanioBytes;
    /** Máximo de peticiones (audio+texto) por usuario en una hora. */
    public final int maxPeticionesHora;
    /** Máximo de peticiones (audio+texto) por usuario en un día. */
    public final int maxPeticionesDia;
    /** Máximo de emails por usuario en un día. */
    public final int maxEmailsDia;

    private final long limiteGlobalMillieuros;

    // Ventanas deslizantes por usuario (compartidas audio + texto)
    private final ConcurrentHashMap<Long, Deque<Instant>> ventanaHora  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Deque<Instant>> ventanaDia   = new ConcurrentHashMap<>();
    // Ventana deslizante 24h para emails por usuario
    private final ConcurrentHashMap<Long, Deque<Instant>> ventanaEmail = new ConcurrentHashMap<>();

    // Coste acumulado hoy en millieuros con reset automático al cambiar de día
    private final AtomicLong costeDiario = new AtomicLong(0);
    private volatile LocalDate fechaCosteDiario = LocalDate.now();

    private RateLimiter() {
        maxDuracionSeg        = parseInt(ConfigUtil.get("limit.audio.max.duration"),  180);
        maxTamanioBytes       = parseInt(ConfigUtil.get("limit.audio.max.size"),      1_048_576);
        maxPeticionesHora     = parseInt(ConfigUtil.get("limit.requests.per.hour"),   10);
        maxPeticionesDia      = parseInt(ConfigUtil.get("limit.requests.per.day"),    30);
        maxEmailsDia          = parseInt(ConfigUtil.get("limit.email.per.day"),        20);
        limiteGlobalMillieuros = parseMillieuros(ConfigUtil.get("limit.cost.daily.max"), 3_000);
    }

    public static RateLimiter getInstance() {
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
        LIMITE_HORA,
        LIMITE_DIA,
        LIMITE_COSTE_GLOBAL,
        LIMITE_EMAIL_DIA
    }

    /**
     * Comprueba límites para una petición de audio (duración, tamaño y rate limit compartido).
     * No modifica contadores; llamar a {@link #registrarAudio(long)} tras procesar con éxito.
     *
     * @param telegramId   identificador del usuario
     * @param duracionSeg  duración del audio en segundos
     * @param tamanioBytes tamaño del fichero de audio en bytes
     */
    public Resultado comprobarAudio(long telegramId, int duracionSeg, int tamanioBytes) {
        if (duracionSeg > maxDuracionSeg)   return Resultado.DURACION_EXCEDIDA;
        if (tamanioBytes > maxTamanioBytes) return Resultado.TAMANO_EXCEDIDO;
        return comprobarComun(telegramId, COSTE_AUDIO_MILLIEUROS);
    }

    /**
     * Comprueba límites para una petición de texto (solo rate limit compartido).
     * No modifica contadores; llamar a {@link #registrarTexto(long)} tras procesar con éxito.
     *
     * @param telegramId identificador del usuario
     */
    public Resultado comprobarTexto(long telegramId) {
        return comprobarComun(telegramId, COSTE_TEXTO_MILLIEUROS);
    }

    /**
     * Registra una petición de audio procesada con éxito en los contadores compartidos.
     *
     * @param telegramId identificador del usuario
     */
    public void registrarAudio(long telegramId) {
        registrar(telegramId, COSTE_AUDIO_MILLIEUROS);
    }

    /**
     * Registra una petición de texto procesada con éxito en los contadores compartidos.
     *
     * @param telegramId identificador del usuario
     */
    public void registrarTexto(long telegramId) {
        registrar(telegramId, COSTE_TEXTO_MILLIEUROS);
    }

    /**
     * Comprueba si el usuario puede enviar un email (ventana deslizante 24h).
     * No modifica contadores; llamar a {@link #registrarEmail(long)} tras enviar con éxito.
     *
     * @param telegramId identificador del usuario
     * @return {@link Resultado#OK} o {@link Resultado#LIMITE_EMAIL_DIA}
     */
    public Resultado comprobarEmail(long telegramId) {
        if (contarVentana(ventanaEmail, telegramId, Instant.now(), 86_400) >= maxEmailsDia)
            return Resultado.LIMITE_EMAIL_DIA;
        return Resultado.OK;
    }

    /**
     * Registra un email enviado con éxito en la ventana deslizante del usuario.
     *
     * @param telegramId identificador del usuario
     */
    public void registrarEmail(long telegramId) {
        registrarEnVentana(ventanaEmail, telegramId, Instant.now());
        log.debug("Email registrado telegramId={}", telegramId);
    }

    // -------------------------------------------------------------------------
    // Internos
    // -------------------------------------------------------------------------

    private Resultado comprobarComun(long telegramId, long costeMillieuros) {
        Instant ahora = Instant.now();
        if (contarVentana(ventanaHora, telegramId, ahora, 3_600) >= maxPeticionesHora)
            return Resultado.LIMITE_HORA;
        if (contarVentana(ventanaDia,  telegramId, ahora, 86_400) >= maxPeticionesDia)
            return Resultado.LIMITE_DIA;
        if (costeDiarioHoy() + costeMillieuros > limiteGlobalMillieuros)
            return Resultado.LIMITE_COSTE_GLOBAL;
        return Resultado.OK;
    }

    private void registrar(long telegramId, long costeMillieuros) {
        Instant ahora = Instant.now();
        registrarEnVentana(ventanaHora, telegramId, ahora);
        registrarEnVentana(ventanaDia,  telegramId, ahora);
        costeDiarioHoy(); // sincroniza reset de fecha antes de incrementar
        long total = costeDiario.addAndGet(costeMillieuros);
        log.debug("Petición registrada: telegramId={} costeDiario={}mE (limite={}mE)",
                telegramId, total, limiteGlobalMillieuros);
    }

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

    /** Devuelve el coste acumulado hoy en millieuros y resetea si cambió el día. */
    private long costeDiarioHoy() {
        LocalDate hoy = LocalDate.now();
        if (!hoy.equals(fechaCosteDiario)) {
            synchronized (this) {
                if (!hoy.equals(fechaCosteDiario)) {
                    log.info("Nuevo día ({}): reseteando contador de coste diario", hoy);
                    costeDiario.set(0);
                    fechaCosteDiario = hoy;
                }
            }
        }
        return costeDiario.get();
    }

    private static int parseInt(String valor, int defecto) {
        if (valor == null || valor.isBlank()) return defecto;
        try { return Integer.parseInt(valor.trim()); }
        catch (NumberFormatException e) { return defecto; }
    }

    /** Parsea euros (ej: "3.00") y devuelve millieuros (ej: 3000). */
    private static long parseMillieuros(String valor, long defecto) {
        if (valor == null || valor.isBlank()) return defecto;
        try { return Math.round(Double.parseDouble(valor.trim()) * 1000); }
        catch (NumberFormatException e) { return defecto; }
    }
}
