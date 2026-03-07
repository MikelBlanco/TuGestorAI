package com.tugestorai.bot;

import com.tugestorai.bot.handlers.CallbackHandler;
import com.tugestorai.bot.handlers.TextHandler;
import com.tugestorai.bot.handlers.VoiceHandler;
import com.tugestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Clase principal del bot de Telegram. Recibe los updates y los delega
 * al handler correspondiente según el tipo de mensaje.
 */
public class TuGestorBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(TuGestorBot.class);

    private final VoiceHandler voiceHandler = new VoiceHandler();
    private final TextHandler textHandler = new TextHandler();
    private final CallbackHandler callbackHandler = new CallbackHandler();

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
}