package com.tugestorai.bot.handlers;

import com.tugestorai.bot.TuGestorBot;
import com.tugestorai.bot.session.SessionManager;
import com.tugestorai.bot.session.SessionState;
import com.tugestorai.bot.session.UserSession;
import com.tugestorai.dao.PresupuestoDao;
import com.tugestorai.dao.UsuarioDao;
import com.tugestorai.model.DatosPresupuesto;
import com.tugestorai.model.Presupuesto;
import com.tugestorai.model.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;

/**
 * Gestiona los callbacks de los botones inline del bot.
 */
public class CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);

    private final SessionManager sessionManager = SessionManager.getInstance();
    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final UsuarioDao usuarioDao = new UsuarioDao();

    public void handle(TuGestorBot bot, CallbackQuery callbackQuery) throws TelegramApiException {
        long chatId = callbackQuery.getMessage().getChatId();
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();

        UserSession session = sessionManager.getOrCreate(chatId);

        switch (data) {
            case "confirmar_presupuesto" -> confirmarPresupuesto(bot, chatId, callbackId, session, callbackQuery);
            case "editar_presupuesto"    -> iniciarEdicion(bot, chatId, callbackId, session, callbackQuery);
            case "cancelar_presupuesto"  -> cancelarPresupuesto(bot, chatId, callbackId, session, callbackQuery);
            default -> {
                log.warn("Callback desconocido: {}", data);
                bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Acciones
    // -------------------------------------------------------------------------

    private void confirmarPresupuesto(TuGestorBot bot, long chatId, String callbackId,
                                      UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        if (session.getState() != SessionState.ESPERANDO_CONFIRMACION
                || session.getBorradorPresupuesto() == null) {
            bot.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text("No hay ningún presupuesto pendiente de confirmar.")
                    .showAlert(false)
                    .build());
            return;
        }

        long telegramId = callbackQuery.getFrom().getId();
        Optional<Usuario> usuarioOpt = usuarioDao.findByTelegramId(telegramId);
        if (usuarioOpt.isEmpty()) {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            return;
        }

        Usuario usuario = usuarioOpt.get();
        DatosPresupuesto datos = session.getBorradorPresupuesto();

        // Construir entidad Presupuesto
        Presupuesto p = new Presupuesto();
        p.setUsuarioId(usuario.getId());
        p.setClienteNombre(datos.getClienteNombre());
        p.setDescripcion(datos.getDescripcion());
        p.setSubtotal(datos.calcularSubtotal());
        p.setIvaPorcentaje(datos.getIvaPorcentaje());
        p.setIvaImporte(datos.calcularIvaImporte());
        p.setTotal(datos.calcularTotal());
        p.setEstado(Presupuesto.ESTADO_BORRADOR);
        p.setAudioTranscript(session.getTranscripcion());
        p.setLineas(datos.getLineas());
        p.setNumero(generarNumero(usuario.getId()));

        Presupuesto guardado = presupuestoDao.crear(p);
        usuarioDao.incrementarContadorPresupuestos(usuario.getId());

        // Eliminar botones del mensaje original
        quitarBotones(bot, callbackQuery);

        session.reset();

        // TODO: en el siguiente paso generará el PDF y lo enviará aquí
        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text(String.format(
                        "✅ Presupuesto <b>%s</b> guardado correctamente.\n\n" +
                        "Próximamente se generará el PDF automáticamente.",
                        guardado.getNumero()))
                .parseMode("HTML")
                .build());

        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text("¡Presupuesto guardado!")
                .build());

        log.info("Presupuesto confirmado id={} usuario={}", guardado.getId(), usuario.getId());
    }

    private void iniciarEdicion(TuGestorBot bot, long chatId, String callbackId,
                                UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {
        // TODO: implementar flujo de edición campo a campo
        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text("La edición manual estará disponible pronto.")
                .showAlert(false)
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Por ahora, cancela y envía un nuevo audio corrigiendo los datos.")
                .build());
    }

    private void cancelarPresupuesto(TuGestorBot bot, long chatId, String callbackId,
                                     UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {
        session.reset();
        quitarBotones(bot, callbackQuery);

        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text("Presupuesto cancelado.")
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Presupuesto cancelado. Envíame un nuevo audio cuando quieras.")
                .build());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Elimina el teclado inline del mensaje original tras pulsar un botón. */
    private void quitarBotones(TuGestorBot bot, CallbackQuery callbackQuery)
            throws TelegramApiException {
        bot.execute(EditMessageReplyMarkup.builder()
                .chatId(callbackQuery.getMessage().getChatId())
                .messageId(callbackQuery.getMessage().getMessageId())
                .replyMarkup(null)
                .build());
    }

    /**
     * Genera un número de presupuesto provisional hasta tener un generador real.
     * Formato: PRES-{usuarioId}-{timestamp}
     */
    private String generarNumero(long usuarioId) {
        return String.format("PRES-%d-%d", usuarioId, System.currentTimeMillis() % 100000);
    }
}