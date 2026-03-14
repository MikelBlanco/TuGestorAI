package org.gestorai.bot.handlers;

import org.gestorai.bot.TuGestorBot;
import org.gestorai.bot.session.SessionManager;
import org.gestorai.bot.session.SessionState;
import org.gestorai.bot.session.UserSession;
import org.gestorai.dao.UsuarioDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.DatosPresupuesto;
import org.gestorai.model.LineaDetalle;
import org.gestorai.model.Usuario;
import org.gestorai.service.ClaudeService;
import org.gestorai.service.WhisperService;
import org.gestorai.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Voice;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.File;
import java.util.List;
import java.util.Optional;

/**
 * Gestiona los mensajes de voz: valida límites de uso, descarga el audio,
 * lo transcribe con Whisper y estructura los datos con Claude,
 * presentando un borrador al usuario.
 */
public class VoiceHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceHandler.class);

    private final WhisperService whisperService = new WhisperService();
    private final ClaudeService claudeService = new ClaudeService();
    private final UsuarioDao usuarioDao = new UsuarioDao();
    private final SessionManager sessionManager = SessionManager.getInstance();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();

    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        // 1. Verificar que el usuario esté registrado
        Optional<Usuario> usuarioOpt = usuarioDao.findByTelegramId(telegramId);
        if (usuarioOpt.isEmpty()) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Primero debes registrarte. Usa /start para comenzar.")
                    .build());
            return;
        }

        Usuario usuario = usuarioOpt.get();

        // 2. Validar metadatos del audio ANTES de descargarlo
        Voice voice = message.getVoice();
        int duracion = voice.getDuration()  != null ? voice.getDuration()  : 0;
        int tamano   = voice.getFileSize()  != null ? voice.getFileSize()  : 0;

        RateLimiter.Resultado resultado = rateLimiter.comprobarAudio(telegramId, duracion, tamano);
        if (resultado != RateLimiter.Resultado.OK) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(mensajeLimite(resultado))
                    .parseMode("HTML")
                    .build());
            log.warn("Audio rechazado por límite={} telegramId={} duracion={}s tamano={}B",
                    resultado, telegramId, duracion, tamano);
            return;
        }

        // 3. Verificar límite del plan freemium
        if (!usuario.puedeCrearPresupuesto()) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(String.format(
                            "Has alcanzado el límite de %d presupuestos este mes con el plan gratuito.\n\n" +
                            "Usa /plan para ver cómo actualizar a PRO.",
                            Usuario.LIMITE_PRESUPUESTOS_FREE))
                    .build());
            return;
        }

        // 4. Indicar al usuario que estamos procesando
        bot.execute(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Procesando tu audio... un momento.")
                .build());

        try {
            // 5. Descargar audio
            GetFile getFileReq = new GetFile(voice.getFileId());
            org.telegram.telegrambots.meta.api.objects.File tgFile = bot.execute(getFileReq);
            File audioFile = bot.downloadFile(tgFile);

            log.info("Audio descargado para chatId={} fileId={} duracion={}s tamano={}B",
                    chatId, voice.getFileId(), duracion, tamano);

            // 6. Transcribir con Whisper
            String transcripcion = whisperService.transcribe(audioFile);
            log.info("Transcripción obtenida chatId={}: {}", chatId, transcripcion);

            // 7. Estructurar con Claude
            DatosPresupuesto datos = claudeService.parsePresupuesto(transcripcion);

            // 8. Registrar en los contadores compartidos (procesamiento exitoso)
            rateLimiter.registrarAudio(telegramId);

            // 9. Guardar borrador en sesión
            UserSession session = sessionManager.getOrCreate(chatId);
            session.setBorradorPresupuesto(datos);
            session.setTranscripcion(transcripcion);
            session.setState(SessionState.ESPERANDO_CONFIRMACION);

            // 10. Presentar borrador con teclado de confirmación
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(formatearBorrador(datos))
                    .parseMode("HTML")
                    .replyMarkup(crearTecladoConfirmacion())
                    .build());

        } catch (ServiceException e) {
            log.error("Error procesando audio chatId={}: {}", chatId, e.getMessage());
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ " + e.getMessage() + "\n\nInténtalo de nuevo enviando otro audio.")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Mensajes de límite
    // -------------------------------------------------------------------------

    private String mensajeLimite(RateLimiter.Resultado resultado) {
        int maxMin = rateLimiter.maxDuracionSeg / 60;
        int maxMB  = rateLimiter.maxTamanioBytes / (1024 * 1024);

        return switch (resultado) {
            case DURACION_EXCEDIDA -> String.format(
                    "⏱ <b>Audio demasiado largo.</b>\n\n" +
                    "El máximo permitido es <b>%d minutos</b>. " +
                    "Graba un audio más corto con los datos del presupuesto.",
                    maxMin);
            case TAMANO_EXCEDIDO -> String.format(
                    "📦 <b>Audio demasiado grande.</b>\n\n" +
                    "El tamaño máximo es <b>%d MB</b>. " +
                    "Envía un audio de menor calidad o duración.",
                    maxMB);
            case LIMITE_HORA -> String.format(
                    "⏳ <b>Límite por hora alcanzado.</b>\n\n" +
                    "Puedes procesar hasta %d audios o mensajes por hora. " +
                    "Espera unos minutos e inténtalo de nuevo.",
                    rateLimiter.maxPeticionesHora);
            case LIMITE_DIA -> String.format(
                    "📅 <b>Límite diario alcanzado.</b>\n\n" +
                    "Has alcanzado el máximo de %d peticiones hoy. " +
                    "El contador se reinicia a medianoche.",
                    rateLimiter.maxPeticionesDia);
            case LIMITE_COSTE_GLOBAL ->
                    "🔒 <b>Servicio temporalmente pausado.</b>\n\n" +
                    "El servicio ha alcanzado su límite de uso diario. " +
                    "Estará disponible de nuevo mañana. Disculpa las molestias.";
            default -> "Error inesperado. Inténtalo más tarde.";
        };
    }

    // -------------------------------------------------------------------------
    // Formato del borrador
    // -------------------------------------------------------------------------

    private String formatearBorrador(DatosPresupuesto datos) {
        StringBuilder sb = new StringBuilder();
        sb.append("<b>📋 Borrador de presupuesto</b>\n\n");
        sb.append(String.format("👤 <b>Cliente:</b> %s\n", nvl(datos.getClienteNombre())));

        if (datos.getDescripcion() != null) {
            sb.append(String.format("📝 <b>Descripción:</b> %s\n", datos.getDescripcion()));
        }

        if (!datos.getLineas().isEmpty()) {
            sb.append("\n<b>Conceptos:</b>\n");
            for (LineaDetalle l : datos.getLineas()) {
                sb.append(String.format("  • %s: <b>%.2f €</b>\n", l.getConcepto(), l.getImporte()));
            }
        }

        sb.append(String.format("\n💶 <b>Subtotal:</b> %.2f €", datos.calcularSubtotal()));
        sb.append(String.format("\n🧾 <b>IVA (%s%%):</b> %.2f €",
                datos.getIvaPorcentaje().stripTrailingZeros().toPlainString(),
                datos.calcularIvaImporte()));
        sb.append(String.format("\n✅ <b>Total:</b> %.2f €", datos.calcularTotal()));

        sb.append("\n\n¿Es correcto?");
        return sb.toString();
    }

    private InlineKeyboardMarkup crearTecladoConfirmacion() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ Confirmar")
                                .callbackData("confirmar_presupuesto")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("✏️ Editar")
                                .callbackData("editar_presupuesto")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❌ Cancelar")
                                .callbackData("cancelar_presupuesto")
                                .build()
                ))
                .build();
    }

    private String nvl(String valor) {
        return valor != null ? valor : "—";
    }
}
