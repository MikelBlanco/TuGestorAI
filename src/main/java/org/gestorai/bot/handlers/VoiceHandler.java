package org.gestorai.bot.handlers;

import org.gestorai.bot.TuGestorBot;
import org.gestorai.bot.session.SessionManager;
import org.gestorai.bot.session.SessionState;
import org.gestorai.bot.session.UserSession;
import org.gestorai.dao.AutonomoDao;
import org.gestorai.dao.ClienteDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.service.PresupuestoService;
import org.gestorai.model.Autonomo;
import org.gestorai.model.Cliente;
import org.gestorai.model.DatosPresupuesto;
import org.gestorai.model.LineaDetalle;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;

/**
 * Gestiona los mensajes de voz: valida límites de uso, descarga el audio,
 * lo transcribe con Whisper y estructura los datos con Claude,
 * presentando un borrador al autónomo.
 */
public class VoiceHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceHandler.class);

    private final WhisperService whisperService = new WhisperService();
    private final ClaudeService claudeService = new ClaudeService();
    private final AutonomoDao autonomoDao = new AutonomoDao();
    private final PresupuestoService presupuestoService = new PresupuestoService();
    private final ClienteDao clienteDao = new ClienteDao();
    private final SessionManager sessionManager = SessionManager.getInstance();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();

    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        // 1. Verificar que el autónomo esté registrado
        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Primero debes registrarte. Usa /start para comenzar.")
                    .build());
            return;
        }

        Autonomo autonomo = autonomoOpt.get();

        // 2. Validar metadatos del audio ANTES de descargarlo
        Voice voice = message.getVoice();
        int duracion = voice.getDuration()  != null ? voice.getDuration()  : 0;
        int tamano   = voice.getFileSize()  != null ? voice.getFileSize().intValue()  : 0;

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

        // 3. Verificar estado de sesión
        UserSession session = sessionManager.getOrCreate(chatId);
        if (session.getState() == SessionState.CONFIRMANDO_CLIENTE) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Por favor, responde primero a la pregunta sobre el cliente usando los botones de arriba.")
                    .build());
            return;
        }
        boolean esEdicion = session.getState() == SessionState.EDITANDO;
        if (!esEdicion) {
            try {
                presupuestoService.verificarLimiteFreemium(autonomo.getId(), autonomo.getPlan());
            } catch (ServiceException e) {
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(e.getMessage())
                        .build());
                return;
            }
        }

        // 4. Indicar al autónomo que estamos procesando
        bot.execute(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text(esEdicion ? "Procesando tu corrección... un momento."
                                : "Procesando tu audio... un momento.")
                .build());

        try {
            // 5. Descargar audio
            GetFile getFileReq = new GetFile(voice.getFileId());
            org.telegram.telegrambots.meta.api.objects.File tgFile = bot.execute(getFileReq);
            File audioDescargado = bot.downloadFile(tgFile);
            File audioFile = asegurarExtensionOgg(audioDescargado);

            log.info("Audio descargado para chatId={} fileId={} duracion={}s tamano={}B",
                    chatId, voice.getFileId(), duracion, tamano);

            // 6. Transcribir con Whisper y borrar audio INMEDIATAMENTE
            String transcripcion;
            try {
                transcripcion = whisperService.transcribe(audioFile);
                log.info("Transcripción obtenida chatId={}", chatId);
            } finally {
                borrarAudio(audioFile);
                if (!audioFile.equals(audioDescargado)) {
                    borrarAudio(audioDescargado);
                }
            }

            if (esEdicion) {
                // 7a. Flujo de edición
                new TextHandler().procesarCorreccion(bot, chatId, telegramId, transcripcion, session);
            } else {
                // 7b. Flujo normal
                DatosPresupuesto datos = claudeService.parsePresupuesto(transcripcion);

                rateLimiter.registrarAudio(telegramId);

                session.setBorradorPresupuesto(datos);
                session.setTranscripcion(transcripcion);

                // Detección de cliente existente
                if (datos.getClienteNombre() != null && !datos.getClienteNombre().isBlank()) {
                    List<Cliente> candidatos = clienteDao.buscarPorNombre(autonomo.getId(), datos.getClienteNombre());
                    if (!candidatos.isEmpty()) {
                        Cliente candidato = candidatos.get(0);
                        session.setClienteExistenteId(candidato.getId());
                        session.setState(SessionState.CONFIRMANDO_CLIENTE);
                        bot.execute(SendMessage.builder()
                                .chatId(chatId)
                                .text(formatearPreguntaCliente(candidato))
                                .parseMode("HTML")
                                .replyMarkup(crearTecladoClienteExistente())
                                .build());
                        return;
                    }
                }

                session.setClienteExistenteId(null);
                session.setState(SessionState.ESPERANDO_CONFIRMACION);
                bot.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(formatearBorrador(datos))
                        .parseMode("HTML")
                        .replyMarkup(crearTecladoConfirmacion())
                        .build());
            }

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
    // Cliente existente
    // -------------------------------------------------------------------------

    private String formatearPreguntaCliente(Cliente candidato) {
        StringBuilder sb = new StringBuilder();
        sb.append("He encontrado un cliente con ese nombre:\n\n");
        sb.append(String.format("👤 <b>%s</b>\n", candidato.getNombre()));
        if (candidato.getTelefono() != null && !candidato.getTelefono().isBlank()) {
            sb.append(String.format("📞 %s\n", maskTelefono(candidato.getTelefono())));
        }
        sb.append("\n¿Es la misma persona?");
        return sb.toString();
    }

    private InlineKeyboardMarkup crearTecladoClienteExistente() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ Sí, usar estos datos")
                                .callbackData("cliente_si")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("👤 No, es otra persona")
                                .callbackData("cliente_no")
                                .build()
                ))
                .build();
    }

    private String maskTelefono(String tel) {
        if (tel == null || tel.length() < 3) return "***";
        return "***" + tel.substring(tel.length() - 3);
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

    /**
     * Borra el fichero de audio del disco inmediatamente tras transcribir.
     */
    private void borrarAudio(File fichero) {
        try {
            boolean borrado = Files.deleteIfExists(fichero.toPath());
            if (borrado) log.debug("Audio temporal borrado: {}", fichero.getName());
        } catch (IOException e) {
            log.warn("No se pudo borrar el audio temporal {}: {}", fichero.getName(), e.getMessage());
        }
    }

    /**
     * Si el fichero descargado no tiene extensión .ogg, lo copia con esa extensión
     * para que Whisper API reconozca el formato correctamente.
     */
    private File asegurarExtensionOgg(File fichero) throws IOException {
        if (fichero.getName().toLowerCase().endsWith(".ogg")) return fichero;
        File ogg = File.createTempFile("audio_tg_", ".ogg");
        ogg.deleteOnExit();
        Files.copy(fichero.toPath(), ogg.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return ogg;
    }
}
