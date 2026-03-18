package org.gestorai.bot.handlers;

import org.gestorai.bot.TuGestorBot;
import org.gestorai.bot.session.SessionManager;
import org.gestorai.bot.session.SessionState;
import org.gestorai.bot.session.UserSession;
import org.gestorai.dao.AutonomoDao;
import org.gestorai.dao.PresupuestoDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.Autonomo;
import org.gestorai.model.DatosPresupuesto;
import org.gestorai.model.Presupuesto;
import org.gestorai.service.EmailService;
import org.gestorai.service.ExcelService;
import org.gestorai.service.NumeracionService;
import org.gestorai.service.PdfService;
import org.gestorai.util.ConfigUtil;
import org.gestorai.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gestiona los callbacks de los botones inline del bot.
 */
public class CallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CallbackHandler.class);

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            2, r -> { Thread t = new Thread(r, "doc-cleanup"); t.setDaemon(true); return t; });

    private static final int TTL_DOCS_SEG = leerTtlConfig();

    private static int leerTtlConfig() {
        try {
            String val = ConfigUtil.get("limit.telegram.doc.ttl");
            return (val != null) ? Integer.parseInt(val.trim()) : 300;
        } catch (NumberFormatException e) {
            return 300;
        }
    }

    private final SessionManager sessionManager = SessionManager.getInstance();
    private final PresupuestoDao presupuestoDao = new PresupuestoDao();
    private final AutonomoDao autonomoDao = new AutonomoDao();
    private final NumeracionService numeracionService = new NumeracionService();
    private final PdfService pdfService = new PdfService();
    private final ExcelService excelService = new ExcelService();
    private final EmailService emailService = new EmailService();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();

    public void handle(TuGestorBot bot, CallbackQuery callbackQuery) throws TelegramApiException {
        long chatId = callbackQuery.getMessage().getChatId();
        String data = callbackQuery.getData();
        String callbackId = callbackQuery.getId();

        UserSession session = sessionManager.getOrCreate(chatId);

        switch (data) {
            case "rgpd_acepto"           -> aceptarRgpd(bot, chatId, callbackId, session, callbackQuery);
            case "rgpd_rechazo"          -> rechazarRgpd(bot, chatId, callbackId, session, callbackQuery);
            case "confirmar_presupuesto" -> confirmarPresupuesto(bot, chatId, callbackId, session, callbackQuery);
            case "editar_presupuesto"    -> iniciarEdicion(bot, chatId, callbackId, session, callbackQuery);
            case "cancelar_presupuesto"  -> cancelarPresupuesto(bot, chatId, callbackId, session, callbackQuery);
            case "doc_telegram"          -> enviarDocTelegram(bot, chatId, callbackId, session, callbackQuery);
            case "doc_email"             -> enviarDocEmail(bot, chatId, callbackId, session, callbackQuery);
            case "doc_ambos"             -> enviarDocAmbos(bot, chatId, callbackId, session, callbackQuery);
            default -> {
                log.warn("Callback desconocido: {}", data);
                bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Consentimiento RGPD
    // -------------------------------------------------------------------------

    private void aceptarRgpd(TuGestorBot bot, long chatId, String callbackId,
                              UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        if (session.getState() != SessionState.PENDIENTE_CONSENTIMIENTO) {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            return;
        }

        reemplazarBorrador(bot, callbackQuery, "✅ Términos aceptados.");
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());

        session.setState(SessionState.REGISTRO_NOMBRE);
        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("¡Perfecto! Para empezar, ¿cuál es tu nombre o el de tu negocio?")
                .build());
    }

    private void rechazarRgpd(TuGestorBot bot, long chatId, String callbackId,
                               UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        session.reset();
        reemplazarBorrador(bot, callbackQuery, "❌ Términos rechazados.");
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Has rechazado el tratamiento de datos. No es posible usar el bot sin aceptarlos.\n\n" +
                      "Si cambias de opinión, usa /start.")
                .build());
    }

    // -------------------------------------------------------------------------
    // Confirmar presupuesto
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
        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            return;
        }

        Autonomo autonomo = autonomoOpt.get();
        DatosPresupuesto datos = session.getBorradorPresupuesto();

        Presupuesto p = new Presupuesto();
        p.setAutonomoId(autonomo.getId());
        p.setClienteNombre(datos.getClienteNombre());
        p.setNotas(datos.getDescripcion());
        p.setSubtotal(datos.calcularSubtotal());
        p.setIvaPorcentaje(datos.getIvaPorcentaje());
        p.setIvaImporte(datos.calcularIvaImporte());
        p.setTotal(datos.calcularTotal());
        p.setEstado(Presupuesto.ESTADO_BORRADOR);
        p.setAudioTranscript(session.getTranscripcion());
        p.setLineas(datos.getLineas());
        p.setNumero(numeracionService.siguienteNumeroPresupuesto(autonomo.getId()));

        Presupuesto guardado = presupuestoDao.crear(p);

        String resumen = String.format("✅ Presupuesto <b>%s</b> generado (%.2f€)",
                guardado.getNumero(), guardado.getTotal());
        reemplazarBorrador(bot, callbackQuery, resumen);
        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text("¡Presupuesto guardado!")
                .build());

        try {
            String base = "presupuesto_" + guardado.getNumero().replace("/", "-");
            String pdfNombre  = base + ".pdf";
            String xlsxNombre = base + ".xlsx";

            byte[] pdfBytes  = pdfService.generarPresupuesto(guardado, autonomo);
            byte[] xlsxBytes = excelService.generarPresupuesto(guardado, autonomo);

            session.setPendingPdfBytes(pdfBytes);
            session.setPendingPdfNombre(pdfNombre);
            session.setPendingXlsxBytes(xlsxBytes);
            session.setPendingXlsxNombre(xlsxNombre);
            session.setState(SessionState.ESPERANDO_OPCION_ENVIO);

            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("¿Cómo quieres recibir los documentos?")
                    .replyMarkup(crearTecladoEnvio())
                    .build());

        } catch (Exception e) {
            log.error("Error generando documentos presupuesto id={}", guardado.getId(), e);
            session.reset();
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("✅ Presupuesto <b>" + guardado.getNumero() + "</b> guardado, " +
                          "pero no se pudieron generar los documentos. Inténtalo de nuevo más tarde.")
                    .parseMode("HTML")
                    .build());
        }

        log.info("Presupuesto confirmado id={} autonomo={}", guardado.getId(), autonomo.getId());
    }

    // -------------------------------------------------------------------------
    // Editar
    // -------------------------------------------------------------------------

    private void iniciarEdicion(TuGestorBot bot, long chatId, String callbackId,
                                UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        if (session.getState() != SessionState.ESPERANDO_CONFIRMACION
                || session.getBorradorPresupuesto() == null) {
            bot.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text("No hay ningún presupuesto pendiente de editar.")
                    .showAlert(false)
                    .build());
            return;
        }

        session.resetContadorEdiciones();
        session.setState(SessionState.EDITANDO);
        quitarBotones(bot, callbackQuery);
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("✏️ ¿Qué quieres corregir? Envíame un texto o audio con los cambios.\n\n" +
                      "<i>Ejemplos: \"El material son 320, no 280\", " +
                      "\"Añade desplazamiento 30 euros\", \"El cliente es Juan, no María\"</i>")
                .parseMode("HTML")
                .build());
    }

    // -------------------------------------------------------------------------
    // Cancelar
    // -------------------------------------------------------------------------

    private void cancelarPresupuesto(TuGestorBot bot, long chatId, String callbackId,
                                     UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {
        session.reset();
        reemplazarBorrador(bot, callbackQuery, "❌ Presupuesto cancelado");

        bot.execute(AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .text("Presupuesto cancelado.")
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Presupuesto cancelado. Envíame un audio o escribe los datos cuando quieras.")
                .build());
    }

    // -------------------------------------------------------------------------
    // Opciones de envío de documentos
    // -------------------------------------------------------------------------

    private void enviarDocTelegram(TuGestorBot bot, long chatId, String callbackId,
                                   UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        byte[] pdfBytes   = session.getPendingPdfBytes();
        String pdfNombre  = session.getPendingPdfNombre();
        byte[] xlsxBytes  = session.getPendingXlsxBytes();
        String xlsxNombre = session.getPendingXlsxNombre();

        if (pdfBytes == null || pdfBytes.length == 0) {
            session.reset();
            quitarBotones(bot, callbackQuery);
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            bot.execute(SendMessage.builder().chatId(chatId)
                    .text("No encontré los documentos. Inténtalo de nuevo.").build());
            return;
        }

        session.reset();
        quitarBotones(bot, callbackQuery);
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());

        enviarDocsPorTelegram(bot, chatId, pdfBytes, pdfNombre, xlsxBytes, xlsxNombre);
    }

    private void enviarDocEmail(TuGestorBot bot, long chatId, String callbackId,
                                UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        byte[] pdfBytes   = session.getPendingPdfBytes();
        String pdfNombre  = session.getPendingPdfNombre();
        byte[] xlsxBytes  = session.getPendingXlsxBytes();
        String xlsxNombre = session.getPendingXlsxNombre();

        if (pdfBytes == null || pdfBytes.length == 0) {
            session.reset();
            quitarBotones(bot, callbackQuery);
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            bot.execute(SendMessage.builder().chatId(chatId)
                    .text("No encontré los documentos. Inténtalo de nuevo.").build());
            return;
        }

        long telegramId = callbackQuery.getFrom().getId();
        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            session.reset();
            quitarBotones(bot, callbackQuery);
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            return;
        }

        Autonomo autonomo = autonomoOpt.get();

        if (autonomo.getEmail() == null || autonomo.getEmail().isBlank()) {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            bot.execute(SendMessage.builder().chatId(chatId)
                    .text("No tienes email configurado. Actualízalo con /perfil o elige 📱 Telegram.")
                    .build());
            return;
        }

        if (rateLimiter.comprobarEmail(telegramId) == RateLimiter.Resultado.LIMITE_EMAIL_DIA) {
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            bot.execute(SendMessage.builder().chatId(chatId)
                    .text(String.format("📧 Has alcanzado el límite de %d emails hoy. " +
                          "Elige 📱 Telegram o inténtalo mañana.", rateLimiter.maxEmailsDia))
                    .build());
            return;
        }

        session.reset();
        quitarBotones(bot, callbackQuery);
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());

        enviarDocsPorEmail(bot, chatId, telegramId, autonomo, pdfBytes, pdfNombre, xlsxBytes, xlsxNombre);
    }

    private void enviarDocAmbos(TuGestorBot bot, long chatId, String callbackId,
                                UserSession session, CallbackQuery callbackQuery)
            throws TelegramApiException {

        byte[] pdfBytes   = session.getPendingPdfBytes();
        String pdfNombre  = session.getPendingPdfNombre();
        byte[] xlsxBytes  = session.getPendingXlsxBytes();
        String xlsxNombre = session.getPendingXlsxNombre();

        if (pdfBytes == null || pdfBytes.length == 0) {
            session.reset();
            quitarBotones(bot, callbackQuery);
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            bot.execute(SendMessage.builder().chatId(chatId)
                    .text("No encontré los documentos. Inténtalo de nuevo.").build());
            return;
        }

        long telegramId = callbackQuery.getFrom().getId();
        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            session.reset();
            quitarBotones(bot, callbackQuery);
            bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());
            return;
        }

        session.reset();
        quitarBotones(bot, callbackQuery);
        bot.execute(AnswerCallbackQuery.builder().callbackQueryId(callbackId).build());

        enviarDocsPorTelegram(bot, chatId, pdfBytes, pdfNombre, xlsxBytes, xlsxNombre);

        Autonomo autonomo = autonomoOpt.get();
        if (autonomo.getEmail() != null && !autonomo.getEmail().isBlank()
                && rateLimiter.comprobarEmail(telegramId) != RateLimiter.Resultado.LIMITE_EMAIL_DIA) {
            enviarDocsPorEmail(bot, chatId, telegramId, autonomo, pdfBytes, pdfNombre, xlsxBytes, xlsxNombre);
        } else if (autonomo.getEmail() == null || autonomo.getEmail().isBlank()) {
            bot.execute(SendMessage.builder().chatId(chatId)
                    .text("No tienes email configurado, pero los documentos ya están disponibles arriba. " +
                          "Actualiza tu email con /perfil.")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Helpers de envío de documentos
    // -------------------------------------------------------------------------

    private void enviarDocsPorTelegram(TuGestorBot bot, long chatId,
                                       byte[] pdfBytes, String pdfNombre,
                                       byte[] xlsxBytes, String xlsxNombre)
            throws TelegramApiException {
        Message msgPdf = bot.execute(SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(new ByteArrayInputStream(pdfBytes), pdfNombre))
                .build());

        Message msgXlsx = bot.execute(SendDocument.builder()
                .chatId(chatId)
                .document(new InputFile(new ByteArrayInputStream(xlsxBytes), xlsxNombre))
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("⚠️ Por seguridad, los documentos se eliminarán del chat en " +
                      (TTL_DOCS_SEG / 60) + " minutos. Descárgalos o solicita reenvío por email.")
                .build());

        programarBorradoMensaje(bot, chatId, msgPdf.getMessageId());
        programarBorradoMensaje(bot, chatId, msgXlsx.getMessageId());
    }

    private void enviarDocsPorEmail(TuGestorBot bot, long chatId, long telegramId,
                                    Autonomo autonomo,
                                    byte[] pdfBytes, String pdfNombre,
                                    byte[] xlsxBytes, String xlsxNombre) {
        try {
            String nombre = pdfNombre != null ? pdfNombre : "presupuesto.pdf";
            String asunto = "Presupuesto " + nombre
                    .replace(".pdf", "")
                    .replace("presupuesto_", "")
                    .replace("-", "/");
            String cuerpo = "Hola,\n\nAdjuntamos el presupuesto generado con TuGestorAI " +
                            "(PDF y Excel).\n\nSaludos,\nTuGestorAI";

            String xlsxNombreFinal = xlsxNombre != null ? xlsxNombre : "presupuesto.xlsx";
            emailService.enviarConAdjuntos(autonomo.getEmail(), asunto, cuerpo,
                    new EmailService.Adjunto(pdfBytes, "application/pdf", nombre),
                    new EmailService.Adjunto(xlsxBytes,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            xlsxNombreFinal));

            rateLimiter.registrarEmail(telegramId);

            try {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("✉️ Documentos enviados a <b>" + autonomo.getEmail() + "</b>.")
                        .parseMode("HTML")
                        .build());
            } catch (TelegramApiException e) {
                log.warn("No se pudo enviar confirmación de email chatId={}", chatId);
            }

            log.info("Presupuesto enviado por email autonomo={}", autonomo.getId());

        } catch (ServiceException e) {
            log.error("Error enviando email autonomo={}: {}", autonomo.getId(), e.getMessage());
            try {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text("No se pudo enviar el email. Los documentos están disponibles en el chat.")
                        .build());
            } catch (TelegramApiException ex) {
                log.warn("No se pudo enviar aviso de fallo de email chatId={}", chatId);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Teclados inline
    // -------------------------------------------------------------------------

    private InlineKeyboardMarkup crearTecladoEnvio() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("📱 Telegram")
                                .callbackData("doc_telegram")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("📧 Email")
                                .callbackData("doc_email")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("📱📧 Ambos")
                                .callbackData("doc_ambos")
                                .build()
                ))
                .build();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void programarBorradoMensaje(TuGestorBot bot, long chatId, int messageId) {
        scheduler.schedule(() -> {
            try {
                bot.execute(DeleteMessage.builder()
                        .chatId(String.valueOf(chatId))
                        .messageId(messageId)
                        .build());
                log.debug("Documento eliminado del chat chatId={} messageId={}", chatId, messageId);
            } catch (TelegramApiException e) {
                log.warn("No se pudo eliminar el documento del chat chatId={} messageId={}: {}",
                        chatId, messageId, e.getMessage());
            }
        }, TTL_DOCS_SEG, TimeUnit.SECONDS);
    }

    private void reemplazarBorrador(TuGestorBot bot, CallbackQuery callbackQuery, String texto)
            throws TelegramApiException {
        bot.execute(EditMessageText.builder()
                .chatId(callbackQuery.getMessage().getChatId())
                .messageId(callbackQuery.getMessage().getMessageId())
                .text(texto)
                .parseMode("HTML")
                .replyMarkup(null)
                .build());
    }

    private void quitarBotones(TuGestorBot bot, CallbackQuery callbackQuery)
            throws TelegramApiException {
        bot.execute(EditMessageReplyMarkup.builder()
                .chatId(callbackQuery.getMessage().getChatId())
                .messageId(callbackQuery.getMessage().getMessageId())
                .replyMarkup(null)
                .build());
    }
}
