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
import org.gestorai.util.ConfigUtil;
import org.gestorai.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Optional;

/**
 * Gestiona los mensajes de texto y comandos del bot.
 *
 * <p>Si el usuario envía texto libre (no comando) estando en estado {@link SessionState#IDLE},
 * se interpreta como un presupuesto dictado por escrito y se procesa directamente con
 * Claude, sin pasar por Whisper. El contador de rate limit es compartido con los audios.</p>
 */
public class TextHandler {

    private static final Logger log = LoggerFactory.getLogger(TextHandler.class);

    private final UsuarioDao usuarioDao = new UsuarioDao();
    private final ClaudeService claudeService = new ClaudeService();
    private final SessionManager sessionManager = SessionManager.getInstance();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();

    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String texto = message.getText().trim();
        UserSession session = sessionManager.getOrCreate(chatId);

        // Comandos tienen prioridad sobre el estado de sesión
        if (texto.startsWith("/")) {
            manejarComando(bot, message, session);
            return;
        }

        // Texto libre según el estado actual de la sesión
        switch (session.getState()) {
            case PENDIENTE_CONSENTIMIENTO -> enviarMensaje(bot, chatId,
                    "Por favor, acepta o rechaza los términos usando los botones de arriba.");
            case EDITANDO           -> procesarCorreccion(bot, chatId, telegramId,
                                            message.getText().trim(), session);
            case REGISTRO_NOMBRE    -> procesarRegistroNombre(bot, message, session);
            case REGISTRO_NIF       -> procesarRegistroNif(bot, message, session);
            case REGISTRO_DIRECCION -> procesarRegistroDireccion(bot, message, session);
            case IDLE               -> procesarTextoPresupuesto(bot, message, session);
            default -> enviarMensaje(bot, chatId,
                    "Envíame un audio o escribe los datos del presupuesto, " +
                    "o usa /ayuda para ver los comandos disponibles.");
        }
    }

    // -------------------------------------------------------------------------
    // Texto libre como presupuesto (estado IDLE)
    // -------------------------------------------------------------------------

    private void procesarTextoPresupuesto(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        // Verificar registro
        Optional<Usuario> usuarioOpt = usuarioDao.findByTelegramId(telegramId);
        if (usuarioOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "Primero debes registrarte. Usa /start para comenzar.");
            return;
        }

        Usuario usuario = usuarioOpt.get();

        // Verificar rate limit (contador compartido con audios)
        RateLimiter.Resultado limitado = rateLimiter.comprobarTexto(telegramId);
        if (limitado != RateLimiter.Resultado.OK) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(mensajeLimiteTexto(limitado))
                    .parseMode("HTML")
                    .build());
            log.warn("Texto rechazado por límite={} telegramId={}", limitado, telegramId);
            return;
        }

        // Verificar límite del plan freemium
        if (!usuario.puedeCrearPresupuesto()) {
            enviarMensaje(bot, chatId, String.format(
                    "Has alcanzado el límite de %d presupuestos este mes con el plan gratuito.\n\n" +
                    "Usa /plan para ver cómo actualizar a PRO.",
                    Usuario.LIMITE_PRESUPUESTOS_FREE));
            return;
        }

        String textoPresupuesto = message.getText().trim();

        // Indicar al usuario que estamos procesando
        bot.execute(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build());

        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("Procesando tu mensaje... un momento.")
                .build());

        try {
            DatosPresupuesto datos = claudeService.parsePresupuesto(textoPresupuesto);
            log.info("Presupuesto por texto procesado chatId={}", chatId);

            // Registrar en los contadores compartidos (procesamiento exitoso)
            rateLimiter.registrarTexto(telegramId);

            // Guardar borrador en sesión (el texto original hace las veces de transcripción)
            session.setBorradorPresupuesto(datos);
            session.setTranscripcion(textoPresupuesto);
            session.setState(SessionState.ESPERANDO_CONFIRMACION);

            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(formatearBorrador(datos))
                    .parseMode("HTML")
                    .replyMarkup(crearTecladoConfirmacion())
                    .build());

        } catch (ServiceException e) {
            log.error("Error procesando texto presupuesto chatId={}: {}", chatId, e.getMessage());
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ " + e.getMessage() + "\n\nInténtalo de nuevo.")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Mensajes de límite (texto — no incluye DURACION_EXCEDIDA ni TAMANO_EXCEDIDO)
    // -------------------------------------------------------------------------

    private String mensajeLimiteTexto(RateLimiter.Resultado resultado) {
        return switch (resultado) {
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
    // Comandos
    // -------------------------------------------------------------------------

    private void manejarComando(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        String comando = message.getText().split(" ")[0].toLowerCase();

        switch (comando) {
            case "/start"       -> manejarStart(bot, message, session);
            case "/ayuda"       -> enviarAyuda(bot, chatId);
            case "/perfil"      -> mostrarPerfil(bot, message);
            case "/plan"        -> mostrarPlan(bot, message);
            case "/presupuesto" -> enviarMensaje(bot, chatId,
                    "Envíame un mensaje de voz o escribe directamente los datos del presupuesto.");
            default             -> enviarMensaje(bot, chatId,
                    "Comando no reconocido. Usa /ayuda para ver los disponibles.");
        }
    }

    private void manejarStart(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        Optional<Usuario> existente = usuarioDao.findByTelegramId(telegramId);
        if (existente.isPresent()) {
            enviarMensaje(bot, chatId, String.format(
                    "¡Bienvenido de nuevo, %s! Envíame un audio o escribe los datos del presupuesto, " +
                    "o usa /ayuda.",
                    existente.get().getNombre()));
            return;
        }

        // Aviso de privacidad obligatorio (RGPD) antes de iniciar el registro
        session.setState(SessionState.PENDIENTE_CONSENTIMIENTO);
        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text("""
                        ¡Hola! Soy <b>TuGestorAI</b>, tu asistente para presupuestos y facturas.

                        ℹ️ <b>Antes de continuar, necesito informarte:</b>

                        • Tus mensajes de voz se envían a servicios de IA externos (OpenAI Whisper) \
                        para transcribirlos. <b>No se almacenan tras el procesamiento.</b>
                        • Tus datos fiscales (nombre, NIF, dirección) se guardan <b>cifrados</b> \
                        en nuestra base de datos.
                        • Solo tú tienes acceso a tus datos y documentos.

                        ¿Aceptas el tratamiento de tus datos según lo descrito?
                        """)
                .parseMode("HTML")
                .replyMarkup(crearTecladoConsentimiento())
                .build());
    }

    private InlineKeyboardMarkup crearTecladoConsentimiento() {
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("✅ Acepto")
                                .callbackData("rgpd_acepto")
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("❌ No acepto")
                                .callbackData("rgpd_rechazo")
                                .build()
                ))
                .build();
    }

    private void enviarAyuda(TuGestorBot bot, long chatId) throws TelegramApiException {
        String ayuda = """
                <b>Cómo crear un presupuesto:</b>

                🎤 <b>Por voz</b> — Envía un audio con los datos
                ✍️ <b>Por texto</b> — Escribe directamente los datos del presupuesto

                <b>Comandos:</b>
                /presupuesto — Recordatorio de cómo crear un presupuesto
                /perfil — Ver y editar tus datos fiscales
                /plan — Ver tu plan actual
                /ayuda — Mostrar esta ayuda

                <b>Ejemplo:</b>
                <i>"Presupuesto para María García, cambio de grifo, mano de obra 80 euros, material 40 euros"</i>
                """;
        enviarMensajeHtml(bot, chatId, ayuda);
    }

    private void mostrarPerfil(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        usuarioDao.findByTelegramId(telegramId).ifPresentOrElse(
                u -> {
                    String perfil = String.format("""
                            <b>Tus datos fiscales:</b>

                            👤 Nombre: %s
                            🏢 Nombre comercial: %s
                            📋 NIF: %s
                            📍 Dirección: %s
                            📞 Teléfono: %s
                            📧 Email: %s
                            """,
                            nvl(u.getNombre()),
                            nvl(u.getNombreComercial()),
                            nvl(u.getNif()),
                            nvl(u.getDireccion()),
                            nvl(u.getTelefono()),
                            nvl(u.getEmail()));
                    try {
                        enviarMensajeHtml(bot, chatId, perfil);
                    } catch (TelegramApiException e) {
                        log.error("Error enviando perfil a chatId={}", chatId, e);
                    }
                },
                () -> {
                    try {
                        enviarMensaje(bot, chatId, "No estás registrado. Usa /start para comenzar.");
                    } catch (TelegramApiException e) {
                        log.error("Error enviando mensaje a chatId={}", chatId, e);
                    }
                }
        );
    }

    private void mostrarPlan(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        usuarioDao.findByTelegramId(telegramId).ifPresentOrElse(
                u -> {
                    String texto;
                    if (u.esPro()) {
                        texto = "⭐ <b>Plan PRO</b> — Presupuestos y facturas ilimitadas.";
                    } else {
                        texto = String.format(
                                "Plan <b>FREE</b> — %d de %d presupuestos usados este mes.\n\n" +
                                "Hazte PRO para presupuestos ilimitados y generación de facturas.",
                                u.getPresupuestosMes(), Usuario.LIMITE_PRESUPUESTOS_FREE);
                    }
                    try {
                        enviarMensajeHtml(bot, chatId, texto);
                    } catch (TelegramApiException e) {
                        log.error("Error enviando plan a chatId={}", chatId, e);
                    }
                },
                () -> {
                    try {
                        enviarMensaje(bot, chatId, "No estás registrado. Usa /start para comenzar.");
                    } catch (TelegramApiException e) {
                        log.error("Error enviando mensaje a chatId={}", chatId, e);
                    }
                }
        );
    }

    // -------------------------------------------------------------------------
    // Flujo de registro
    // -------------------------------------------------------------------------

    private void procesarRegistroNombre(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        String nombre = message.getText().trim();

        if (nombre.length() < 2) {
            enviarMensaje(bot, chatId, "Por favor, introduce un nombre válido.");
            return;
        }

        session.setBorradorPresupuesto(new DatosPresupuesto());
        session.getBorradorPresupuesto().setClienteNombre(nombre);
        session.setState(SessionState.REGISTRO_NIF);
        enviarMensaje(bot, chatId, "¿Cuál es tu NIF o CIF? (Puedes saltarlo respondiendo con un guión -)");
    }

    private void procesarRegistroNif(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        String nif = message.getText().trim();

        session.getBorradorPresupuesto().setDescripcion(nif.equals("-") ? null : nif);
        session.setState(SessionState.REGISTRO_DIRECCION);
        enviarMensaje(bot, chatId, "¿Cuál es tu dirección fiscal? (Puedes saltarla con -)");
    }

    private void procesarRegistroDireccion(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String direccion = message.getText().trim();

        String nombre = session.getBorradorPresupuesto().getClienteNombre();
        String nif    = session.getBorradorPresupuesto().getDescripcion();

        Usuario nuevo = new Usuario();
        nuevo.setTelegramId(telegramId);
        nuevo.setNombre(nombre);
        nuevo.setNif(nif);
        nuevo.setDireccion(direccion.equals("-") ? null : direccion);
        nuevo.setPlan(Usuario.PLAN_FREE);

        usuarioDao.crear(nuevo);
        session.reset();

        enviarMensaje(bot, chatId, String.format(
                "¡Registrado, %s! Ya puedes enviarme audios o escribirme los datos de tus presupuestos.\n\n" +
                "Usa /ayuda para ver cómo funciona.", nombre));
    }

    // -------------------------------------------------------------------------
    // Flujo de edición
    // -------------------------------------------------------------------------

    /**
     * Procesa una corrección en lenguaje natural sobre el borrador actual.
     * Llamado tanto desde texto ({@link TextHandler}) como desde audio ({@link VoiceHandler}).
     */
    void procesarCorreccion(TuGestorBot bot, long chatId, long telegramId,
                            String correccion, UserSession session)
            throws TelegramApiException {

        int maxEdiciones = leerMaxEdiciones();

        if (session.getContadorEdiciones() >= maxEdiciones) {
            session.reset();
            enviarMensaje(bot, chatId, String.format(
                    "Has alcanzado el límite de %d ediciones. El presupuesto ha sido cancelado.\n\n" +
                    "Envía un nuevo audio o escribe los datos para empezar de nuevo.", maxEdiciones));
            return;
        }

        // Las ediciones cuentan como peticiones (rate limit compartido)
        RateLimiter.Resultado limitado = rateLimiter.comprobarTexto(telegramId);
        if (limitado != RateLimiter.Resultado.OK) {
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(mensajeLimiteTexto(limitado))
                    .parseMode("HTML")
                    .build());
            return;
        }

        bot.execute(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build());

        try {
            DatosPresupuesto actualizado = claudeService.editarPresupuesto(
                    session.getBorradorPresupuesto(), correccion);

            session.incrementarEdiciones();
            rateLimiter.registrarTexto(telegramId);
            session.setBorradorPresupuesto(actualizado);
            session.setState(SessionState.ESPERANDO_CONFIRMACION);

            log.info("Borrador editado chatId={} edicion={}/{}", chatId,
                    session.getContadorEdiciones(), maxEdiciones);

            // Avisar sobre ediciones restantes si quedan pocas
            int restantes = maxEdiciones - session.getContadorEdiciones();
            String notaEdiciones = restantes <= 2
                    ? String.format("\n\n<i>%d edición%s restante%s.</i>",
                            restantes,
                            restantes == 1 ? "" : "es",
                            restantes == 1 ? "" : "s")
                    : "";

            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(formatearBorrador(actualizado) + notaEdiciones)
                    .parseMode("HTML")
                    .replyMarkup(crearTecladoConfirmacion())
                    .build());

        } catch (ServiceException e) {
            log.error("Error editando borrador chatId={}: {}", chatId, e.getMessage());
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ " + e.getMessage() + "\n\nInténtalo de nuevo con otra descripción.")
                    .build());
        }
    }

    private int leerMaxEdiciones() {
        try {
            String val = ConfigUtil.get("limit.edits.per.presupuesto");
            return (val != null) ? Integer.parseInt(val.trim()) : 5;
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    // -------------------------------------------------------------------------
    // Formato del borrador (idéntico al de VoiceHandler)
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

    // -------------------------------------------------------------------------
    // Helpers de envío
    // -------------------------------------------------------------------------

    void enviarMensaje(TuGestorBot bot, long chatId, String texto) throws TelegramApiException {
        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text(texto)
                .build());
    }

    void enviarMensajeHtml(TuGestorBot bot, long chatId, String html) throws TelegramApiException {
        bot.execute(SendMessage.builder()
                .chatId(chatId)
                .text(html)
                .parseMode("HTML")
                .build());
    }

    private String nvl(String valor) {
        return valor != null ? valor : "—";
    }
}
