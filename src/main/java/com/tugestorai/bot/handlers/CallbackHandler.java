package com.tugestorai.bot.handlers;

import com.tugestorai.bot.TuGestorBot;
import com.tugestorai.bot.session.SessionManager;
import com.tugestorai.bot.session.SessionState;
import com.tugestorai.bot.session.UserSession;
import com.tugestorai.dao.PresupuestoDao;
import com.tugestorai.dao.UsuarioDao;
import com.tugestorai.exception.ServiceException;
import com.tugestorai.model.DatosPresupuesto;
import com.tugestorai.model.Presupuesto;
import com.tugestorai.model.Usuario;
import com.tugestorai.service.EmailService;
import com.tugestorai.service.NumeracionService;
import com.tugestorai.service.PdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Gestiona los callbacks de los botones inline del bot.
 *
 * <p>Callbacks que maneja:</p>
 * <ul>
 *   <li>{@code confirmar_presupuesto} — guarda en BD, genera PDF, envía por Telegram y pregunta por email</li>
 *   <li>{@code editar_presupuesto}    — placeholder (fase posterior)</li>
 *   <li>{@code cancelar_presupuesto}  — descarta el borrador</li>
 *   <li>{@code email_si}             — envía el PDF por email al autónomo</li>
 *   <li>{@code email_no}             — descarta la opción de email</li>
 * </ul>
 */
public class CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);

    private final SessionManager sessionManager = SessionManager.getInstance();
    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final UsuarioDao usuarioDao = new UsuarioDao();
    private final NumeracionService numeracionService = new NumeracionService();
    private final PdfService pdfService = new PdfService();
    private final EmailService emailService = new EmailService();

    public void handle(TuGestorBot bot, CallbackQuery callbackQuery) throws TelegramApiException {
        long chatId = callbackQuery.getMessage().getChatId();
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();

        UserSession session = sessionManager.getOrCreate(chatId);

        switch (data) {
            case "confirmar_presupuesto" -> confirmarPresupuesto(bot, chatId, callbackId, session, callbackQuery);
            case "editar_presupuesto"    -> iniciarEdicion(bot, chatId, callbackId, session, callbackQuery);
            case "cancelar_presupuesto"  -> cancelarPresupuesto(bot, chatId, callbackId, session, callbackQuery);
            case "email_si"              -> enviarEmailSi(bot, chatId, callbackId, session, callbackQuery);
            case "email_no"              -> enviarEmailNo(bot, chatId, callbackId, session, callbackQuery);
            default -> {
                log.warn("Callback desconocido: {}", data);
                bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Acción: confirmar presupuesto
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

        // Construir y persistir el presupuesto
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
        p.setNumero(numeracionService.siguienteNumeroPresupuesto(usuario.getId()));

        Presupuesto guardado = presupuestoDao.crear(p);
        usuarioDao.incrementarContadorPresupuestos(usuario.getId());

        // Quitar botones del mensaje original y confirmar
        quitarBotones(bot, callbackQuery);
        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text("¡Presupuesto guardado!")
                .build());

        // Generar PDF y enviarlo por Telegram
        try {
            File pdf = pdfService.generarPresupuesto(guardado, usuario);
            presupuestoDao.actualizarPdfPath(guardado.getId(), pdf.getAbsolutePath());

            bot.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(pdf))
                    .caption("✅ Presupuesto <b>" + guardado.getNumero() + "</b> generado.")
                    .parseMode("HTML")
                    .build());

            // Guardar PDF en sesión y preguntar si lo quiere también por email
            session.setPendingPdfFile(pdf);
            session.setState(SessionState.ESPERANDO_CONFIRMACION_EMAIL);

            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("¿Quieres enviártelo también por email?")
                    .replyMarkup(crearTecladoEmail())
                    .build());

        } catch (Exception e) {
            log.error("Error generando PDF presupuesto id={}", guardado.getId(), e);
            session.reset();
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("✅ Presupuesto <b>" + guardado.getNumero() + "</b> guardado, " +
                          "pero no se pudo generar el PDF. Inténtalo de nuevo más tarde.")
                    .parseMode("HTML")
                    .build());
        }

        log.info("Presupuesto confirmado id={} usuario={}", guardado.getId(), usuario.getId());
    }

    // -------------------------------------------------------------------------
    // Acción: editar
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Acción: cancelar presupuesto
    // -------------------------------------------------------------------------

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
    // Acciones: enviar por email
    // -------------------------------------------------------------------------

    private void enviarEmailSi(TuGestorBot bot, long chatId, String callbackId,
                                UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        File pdf = session.getPendingPdfFile();
        session.reset();
        quitarBotones(bot, callbackQuery);

        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .build());

        if (pdf == null || !pdf.exists()) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("No encontré el PDF. Descárgalo desde el mensaje anterior.")
                    .build());
            return;
        }

        long telegramId = callbackQuery.getFrom().getId();
        Optional<Usuario> usuarioOpt = usuarioDao.findByTelegramId(telegramId);
        if (usuarioOpt.isEmpty()) return;

        Usuario usuario = usuarioOpt.get();

        if (usuario.getEmail() == null || usuario.getEmail().isBlank()) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("No tienes email configurado. Actualízalo con /perfil para poder " +
                          "enviarte documentos por correo.")
                    .build());
            return;
        }

        try {
            String asunto = "Presupuesto " + pdf.getName().replace(".pdf", "")
                    .replace("presupuesto_", "").replace("-", "/");
            String cuerpo = "Hola,\n\nAdjuntamos el presupuesto generado con TuGestorAI.\n\n" +
                            "Saludos,\nTuGestorAI";

            emailService.enviarConAdjunto(usuario.getEmail(), asunto, cuerpo, pdf);

            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("✉️ Presupuesto enviado a <b>" + usuario.getEmail() + "</b>.")
                    .parseMode("HTML")
                    .build());

            log.info("Presupuesto enviado por email a {} (fichero: {})",
                    usuario.getEmail(), pdf.getName());

        } catch (ServiceException e) {
            log.error("Error enviando email a {}: {}", usuario.getEmail(), e.getMessage());
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("No se pudo enviar el email. El presupuesto está disponible " +
                          "en el mensaje de arriba.")
                    .build());
        }
    }

    private void enviarEmailNo(TuGestorBot bot, long chatId, String callbackId,
                                UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {
        session.reset();
        quitarBotones(bot, callbackQuery);

        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("De acuerdo. El presupuesto queda disponible en el mensaje anterior.")
                .build());
    }

    // -------------------------------------------------------------------------
    // Teclados inline
    // -------------------------------------------------------------------------

    private InlineKeyboardMarkup crearTecladoEmail() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📧 Sí, enviar por email")
                                .callbackData("email_si")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❌ No")
                                .callbackData("email_no")
                                .build()
                ))
                .build();
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
}
