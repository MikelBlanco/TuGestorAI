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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Gestiona los mensajes de voz: descarga el audio, lo transcribe con Whisper
 * y estructura los datos con Claude, presentando un borrador al usuario.
 */
public class VoiceHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceHandler.class);

    private final WhisperService whisperService = new WhisperService();
    private final ClaudeService claudeService = new ClaudeService();
    private final UsuarioDao usuarioDao = new UsuarioDao();
    private final SessionManager sessionManager = SessionManager.getInstance();

    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        // Verificar que el usuario esté registrado
        Optional<Usuario> usuarioOpt = usuarioDao.findByTelegramId(telegramId);
        if (usuarioOpt.isEmpty()) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Primero debes registrarte. Usa /start para comenzar.")
                    .build());
            return;
        }

        Usuario usuario = usuarioOpt.get();

        // Verificar límite del plan freemium
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

        // Indicar al usuario que estamos procesando
        bot.execute(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Procesando tu audio... un momento.")
                .build());

        try {
            // Descargar audio
            Voice voice = message.getVoice();
            GetFile getFileReq = new GetFile(voice.getFileId());
            org.telegram.telegrambots.meta.api.objects.File tgFile = bot.execute(getFileReq);
            File audioDescargado = bot.downloadFile(tgFile);
            File audioFile = asegurarExtensionOgg(audioDescargado);

            log.info("Audio descargado para chatId={} fileId={} path={}", chatId, voice.getFileId(), audioFile.getName());

            // Transcribir con Whisper
            String transcripcion = whisperService.transcribe(audioFile);
            log.info("Transcripción obtenida chatId={}: {}", chatId, transcripcion);

            // Estructurar con Claude
            DatosPresupuesto datos = claudeService.parsePresupuesto(transcripcion);

            // Guardar borrador en sesión
            UserSession session = sessionManager.getOrCreate(chatId);
            session.setBorradorPresupuesto(datos);
            session.setTranscripcion(transcripcion);
            session.setState(SessionState.ESPERANDO_CONFIRMACION);

            // Presentar borrador con teclado de confirmación
            String borrador = formatearBorrador(datos);
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(borrador)
                    .parseMode("HTML")
                    .replyMarkup(crearTecladoConfirmacion())
                    .build());

        } catch (IOException e) {
            log.error("Error al preparar fichero de audio chatId={}: {}", chatId, e.getMessage(), e);
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ Error al procesar el fichero de audio. Inténtalo de nuevo.")
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
    // Helpers de formato
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

    /**
     * Si el fichero descargado no tiene extensión .ogg, lo copia a un nuevo fichero
     * temporal con ese nombre para que Whisper API reconozca el formato correctamente.
     */
    private File asegurarExtensionOgg(File fichero) throws IOException {
        if (fichero.getName().toLowerCase().endsWith(".ogg")) {
            return fichero;
        }
        File ogg = File.createTempFile("audio_tg_", ".ogg");
        ogg.deleteOnExit();
        Files.copy(fichero.toPath(), ogg.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return ogg;
    }
}