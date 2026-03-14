package org.gestorai.bot;

import org.gestorai.bot.handlers.CallbackHandler;
import org.gestorai.bot.handlers.TextHandler;
import org.gestorai.bot.handlers.VoiceHandler;
import org.gestorai.dao.UsuarioAutorizadoDao;
import org.gestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;

/**
 * Clase principal del bot de Telegram. Recibe los updates y los delega
 * al handler correspondiente según el tipo de mensaje.
 *
 * <p>Control de acceso: antes de procesar cualquier update se verifica que
 * el {@code telegram_id} del remitente esté en la tabla {@code usuarios_autorizados}.
 * Los IDs se cachean en memoria con un TTL de 5 minutos para minimizar consultas a la BD.</p>
 */
public class TuGestorBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(TuGestorBot.class);

    private static final Duration TTL_AUTH = Duration.ofMinutes(5);

    private final VoiceHandler voiceHandler = new VoiceHandler();
    private final TextHandler textHandler = new TextHandler();
    private final CallbackHandler callbackHandler = new CallbackHandler();
    private final UsuarioAutorizadoDao usuarioAutorizadoDao = new UsuarioAutorizadoDao();

    /** Conjunto de telegram_ids autorizados (caché en memoria). */
    private volatile Set<Long> autorizados = Collections.emptySet();
    private volatile Instant ultimaCargaAuth = Instant.EPOCH;

    @Override
    public String getBotUsername() {
        return ConfigUtil.get("telegram.bot.username");
    }

    @Override
    public String getBotToken() {
        return ConfigUtil.get("telegram.bot.token");
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            Long telegramId = extraerTelegramId(update);

            if (telegramId != null && !estaAutorizado(telegramId)) {
                notificarAccesoDenegado(telegramId, update);
                return;
            }

            if (update.hasMessage()) {
                Message msg = update.getMessage();
                if (msg.hasVoice()) {
                    voiceHandler.handle(this, msg);
                } else if (msg.hasText()) {
                    textHandler.handle(this, msg);
                }
            } else if (update.hasCallbackQuery()) {
                callbackHandler.handle(this, update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Error procesando update {}", update.getUpdateId(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Control de acceso
    // -------------------------------------------------------------------------

    private boolean estaAutorizado(long telegramId) {
        if (Instant.now().isAfter(ultimaCargaAuth.plus(TTL_AUTH))) {
            recargarAutorizados();
        }
        return autorizados.contains(telegramId);
    }

    private synchronized void recargarAutorizados() {
        // Double-checked: otro hilo pudo haber recargado mientras esperábamos el lock
        if (!Instant.now().isAfter(ultimaCargaAuth.plus(TTL_AUTH))) return;
        try {
            autorizados = usuarioAutorizadoDao.findAllTelegramIds();
            ultimaCargaAuth = Instant.now();
            log.info("Cache de usuarios autorizados recargada: {} usuarios", autorizados.size());
        } catch (Exception e) {
            // Mantener la caché anterior; fijar ultimaCargaAuth para no hacer flood a la BD
            ultimaCargaAuth = Instant.now();
            log.error("Error recargando usuarios autorizados — manteniendo caché anterior", e);
        }
    }

    private void notificarAccesoDenegado(long telegramId, Update update) {
        Long chatId = null;
        if (update.hasMessage()) {
            chatId = update.getMessage().getChatId();
        } else if (update.hasCallbackQuery()) {
            chatId = update.getCallbackQuery().getMessage().getChatId();
        }
        if (chatId == null) return;

        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⛔ Este bot es privado.\n" +
                          "Tu ID de Telegram es: " + telegramId + "\n" +
                          "Envía este número al administrador para solicitar acceso.")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("No se pudo notificar acceso denegado a chatId={}", chatId, e);
        }
        log.warn("Acceso denegado a telegramId={}", telegramId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Long extraerTelegramId(Update update) {
        if (update.hasMessage() && update.getMessage().getFrom() != null) {
            return update.getMessage().getFrom().getId();
        }
        if (update.hasCallbackQuery() && update.getCallbackQuery().getFrom() != null) {
            return update.getCallbackQuery().getFrom().getId();
        }
        return null;
    }
}
