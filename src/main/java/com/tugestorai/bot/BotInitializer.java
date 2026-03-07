package com.tugestorai.bot;

import com.tugestorai.bot.session.SessionManager;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * Arranca el bot de Telegram al inicializar el contexto de Tomcat
 * y lo detiene al apagarlo.
 */
@WebListener
public class BotInitializer implements ServletContextListener {

    private static final Logger log = LoggerFactory.getLogger(BotInitializer.class);

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            botsApi.registerBot(new TuGestorBot());
            log.info("Bot de Telegram iniciado correctamente");
        } catch (TelegramApiException e) {
            log.error("Error iniciando el bot de Telegram", e);
            throw new RuntimeException("No se pudo iniciar el bot de Telegram", e);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        SessionManager.getInstance().shutdown();
        log.info("Bot de Telegram detenido");
    }
}