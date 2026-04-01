package org.gestorai.bot.handlers;

import org.gestorai.bot.TuGestorBot;
import org.gestorai.bot.session.SessionManager;
import org.gestorai.bot.session.SessionState;
import org.gestorai.bot.session.UserSession;
import org.gestorai.dao.AutonomoDao;
import org.gestorai.dao.ClienteDao;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.Autonomo;
import org.gestorai.model.Cliente;
import org.gestorai.model.DatosPresupuesto;
import org.gestorai.model.LineaDetalle;
import org.gestorai.model.Presupuesto;
import org.gestorai.service.ClaudeService;
import org.gestorai.service.EmailService;
import org.gestorai.service.ExcelService;
import org.gestorai.service.PdfService;
import org.gestorai.service.PresupuestoService;
import org.gestorai.util.ConfigUtil;
import org.gestorai.util.RateLimiter;
import org.gestorai.util.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.ActionType;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gestiona los mensajes de texto y comandos del bot.
 *
 * <p>Si el autónomo envía texto libre (no comando) estando en estado {@link SessionState#IDLE},
 * se interpreta como un presupuesto dictado por escrito y se procesa directamente con
 * Claude, sin pasar por Whisper. El contador de rate limit es compartido con los audios.</p>
 */
public class TextHandler {

    private static final Logger log = LoggerFactory.getLogger(TextHandler.class);

    private final AutonomoDao autonomoDao = new AutonomoDao();
    private final PresupuestoService presupuestoService = new PresupuestoService();
    private final ClienteDao clienteDao = new ClienteDao();
    private final ClaudeService claudeService = new ClaudeService();
    private final PdfService pdfService = new PdfService();
    private final ExcelService excelService = new ExcelService();
    private final EmailService emailService = new EmailService();
    private final SessionManager sessionManager = SessionManager.getInstance();
    private final RateLimiter rateLimiter = RateLimiter.getInstance();

    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_PRESUPUESTOS_LISTADO = 10;

    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String texto = message.getText().trim();
        UserSession session = sessionManager.getOrCreate(chatId);

        if (texto.startsWith("/")) {
            manejarComando(bot, message, session);
            return;
        }

        switch (session.getState()) {
            case PENDIENTE_CONSENTIMIENTO -> enviarMensaje(bot, chatId,
                    "Por favor, acepta o rechaza los términos usando los botones de arriba.");
            case CONFIRMANDO_CLIENTE -> enviarMensaje(bot, chatId,
                    "Por favor, responde primero a la pregunta sobre el cliente usando los botones de arriba.");
            case EDITANDO           -> procesarCorreccion(bot, chatId, telegramId,
                                            message.getText().trim(), session);
            case REGISTRO_NOMBRE    -> procesarRegistroNombre(bot, message, session);
            case REGISTRO_NIF       -> procesarRegistroNif(bot, message, session);
            case REGISTRO_DIRECCION -> procesarRegistroDireccion(bot, message, session);
            case REGISTRO_EMAIL     -> procesarRegistroEmail(bot, message, session);
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

        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "Primero debes registrarte. Usa /start para comenzar.");
            return;
        }

        Autonomo autonomo = autonomoOpt.get();

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
        try {
            presupuestoService.verificarLimiteFreemium(autonomo.getId(), autonomo.getPlan());
        } catch (ServiceException e) {
            enviarMensaje(bot, chatId, e.getMessage());
            return;
        }

        String textoPresupuesto = message.getText().trim();

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

            rateLimiter.registrarTexto(telegramId);

            session.setBorradorPresupuesto(datos);
            session.setTranscripcion(textoPresupuesto);

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

        } catch (ServiceException e) {
            log.error("Error procesando texto presupuesto chatId={}: {}", chatId, e.getMessage());
            bot.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("⚠️ " + e.getMessage() + "\n\nInténtalo de nuevo.")
                    .build());
        }
    }

    // -------------------------------------------------------------------------
    // Mensajes de límite
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
            case "/start"         -> manejarStart(bot, message, session);
            case "/ayuda"         -> enviarAyuda(bot, chatId);
            case "/perfil"        -> mostrarPerfil(bot, message);
            case "/plan"          -> mostrarPlan(bot, message);
            case "/presupuesto"   -> enviarMensaje(bot, chatId,
                    "Envíame un mensaje de voz o escribe directamente los datos del presupuesto.");
            case "/presupuestos"  -> listarPresupuestos(bot, message);
            case "/pendientes"    -> listarPendientes(bot, message);
            case "/aceptado"      -> cambiarEstado(bot, message, Presupuesto.ESTADO_ACEPTADO);
            case "/rechazado"     -> cambiarEstado(bot, message, Presupuesto.ESTADO_RECHAZADO);
            case "/facturado"     -> cambiarEstado(bot, message, Presupuesto.ESTADO_FACTURADO);
            case "/reenviar"      -> reenviarPresupuesto(bot, message);
            case "/panel"         -> generarTokenPanel(bot, message);
            default               -> enviarMensaje(bot, chatId,
                    "Comando no reconocido. Usa /ayuda para ver los disponibles.");
        }
    }

    private void manejarStart(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        Optional<Autonomo> existente = autonomoDao.findByTelegramId(telegramId);
        if (existente.isPresent()) {
            enviarMensaje(bot, chatId, String.format(
                    "¡Bienvenido de nuevo, %s! Envíame un audio o escribe los datos del presupuesto, " +
                    "o usa /ayuda.",
                    existente.get().getNombre()));
            return;
        }

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

                <b>Consulta:</b>
                /presupuestos — Lista presupuestos del mes actual
                /presupuestos marzo — Lista presupuestos de un mes concreto
                /pendientes — Presupuestos aceptados sin facturar

                <b>Cambio de estado:</b>
                /aceptado P-2026-0001 — El cliente ha aceptado
                /rechazado P-2026-0001 — El cliente ha rechazado
                /facturado P-2026-0001 — Ya facturado en TicketBAI
                /reenviar P-2026-0001 — Reenviar documentos por email

                <b>Cuenta:</b>
                /perfil — Ver tus datos fiscales
                /plan — Ver tu plan actual
                /panel — Obtener token de acceso al panel web
                /ayuda — Mostrar esta ayuda

                <b>Ejemplo de presupuesto:</b>
                <i>"Para María García, cambio de grifo, mano de obra 80 euros, material 40 euros"</i>
                """;
        enviarMensajeHtml(bot, chatId, ayuda);
    }

    private void mostrarPerfil(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        autonomoDao.findByTelegramId(telegramId).ifPresentOrElse(
                a -> {
                    String perfil = String.format("""
                            <b>Tus datos fiscales:</b>

                            👤 Nombre: %s
                            🏢 Nombre comercial: %s
                            📋 NIF: %s
                            📍 Dirección: %s
                            📞 Teléfono: %s
                            📧 Email: %s
                            """,
                            nvl(a.getNombre()),
                            nvl(a.getNombreComercial()),
                            nvl(a.getNif()),
                            nvl(a.getDireccion()),
                            nvl(a.getTelefono()),
                            nvl(a.getEmail()));
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

        autonomoDao.findByTelegramId(telegramId).ifPresentOrElse(
                a -> {
                    String texto;
                    if (a.esPro()) {
                        texto = "⭐ <b>Plan PRO</b> — Presupuestos y facturas ilimitadas.";
                    } else {
                        int usados = presupuestoService.contarPresupuestosMes(a.getId());
                        texto = String.format(
                                "Plan <b>FREE</b> — %d de %d presupuestos usados este mes.\n\n" +
                                "Hazte PRO para presupuestos ilimitados y generación de facturas.",
                                usados, Autonomo.LIMITE_PRESUPUESTOS_FREE);
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

    private void generarTokenPanel(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        autonomoDao.findByTelegramId(telegramId).ifPresentOrElse(
                a -> {
                    String token = TokenStore.getInstance().generarToken(a.getId());
                    String panelUrl = ConfigUtil.get("panel.url");
                    String texto = String.format(
                            """
                            🔐 <b>Acceso al panel web</b>

                            Tu token de acceso (válido 10 minutos):
                            <code>%s</code>

                            Accede en: %s
                            Introduce el token cuando te lo solicite.

                            ⚠️ No compartas este token con nadie.""",
                            token, panelUrl != null ? panelUrl : "");
                    try {
                        enviarMensajeHtml(bot, chatId, texto);
                    } catch (TelegramApiException e) {
                        log.error("Error enviando token de panel a chatId={}", chatId, e);
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
    // Comandos de consulta y cambio de estado
    // -------------------------------------------------------------------------

    /**
     * /presupuestos [mes] — Lista presupuestos del mes actual o del mes indicado.
     * Admite nombres de meses en español: enero, febrero, ... diciembre.
     */
    private void listarPresupuestos(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "No estás registrado. Usa /start para comenzar.");
            return;
        }
        long autonomoId = autonomoOpt.get().getId();

        String[] partes = message.getText().trim().split("\\s+", 2);
        LocalDate hoy = LocalDate.now();
        int year = hoy.getYear();
        int month = hoy.getMonthValue();
        String nombreMes = null;

        if (partes.length > 1) {
            Integer mesParsed = parsearMes(partes[1].trim().toLowerCase());
            if (mesParsed == null) {
                enviarMensaje(bot, chatId,
                        "Mes no reconocido. Usa el nombre en español: enero, febrero, ... diciembre.");
                return;
            }
            month = mesParsed;
            nombreMes = partes[1].trim();
            // Si el mes pedido es posterior al actual, asumimos el año anterior
            if (month > hoy.getMonthValue()) year--;
        }

        List<Presupuesto> lista = presupuestoService.listarPorMes(autonomoId, year, month);

        if (lista.isEmpty()) {
            String periodo = nombreMes != null ? nombreMes + " " + year
                    : hoy.getMonth().getDisplayName(java.time.format.TextStyle.FULL,
                            new java.util.Locale("es")) + " " + year;
            enviarMensaje(bot, chatId, "No hay presupuestos en " + periodo + ".");
            return;
        }

        String[] MESES_ES = {"", "enero", "febrero", "marzo", "abril", "mayo", "junio",
                             "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre"};
        String encabezado = "📋 <b>Presupuestos — " + capitalizar(MESES_ES[month]) + " " + year + "</b>\n\n";

        enviarMensajeHtml(bot, chatId, encabezado + formatearListaPresupuestos(lista, true));
    }

    /**
     * /pendientes — Lista presupuestos en estado ACEPTADO pendientes de facturar.
     */
    private void listarPendientes(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "No estás registrado. Usa /start para comenzar.");
            return;
        }

        List<Presupuesto> pendientes = presupuestoService.listarPendientes(autonomoOpt.get().getId());

        if (pendientes.isEmpty()) {
            enviarMensaje(bot, chatId, "✅ No tienes presupuestos pendientes de facturar.");
            return;
        }

        BigDecimal totalPendiente = pendientes.stream()
                .map(p -> p.getTotal() != null ? p.getTotal() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StringBuilder sb = new StringBuilder("📋 <b>Presupuestos aceptados pendientes de facturar:</b>\n\n");
        sb.append(formatearListaPresupuestos(pendientes, false));
        sb.append(String.format("\n💰 <b>Total pendiente:</b> %s", formatDinero(totalPendiente)));
        sb.append("\n\n<i>Usa /facturado P-AAAA-NNNN cuando lo pases a TicketBAI.</i>");

        enviarMensajeHtml(bot, chatId, sb.toString());
    }

    /**
     * /aceptado, /rechazado, /facturado — Cambia el estado de un presupuesto.
     */
    private void cambiarEstado(TuGestorBot bot, Message message, String nuevoEstado)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "No estás registrado. Usa /start para comenzar.");
            return;
        }

        String[] partes = message.getText().trim().split("\\s+", 2);
        if (partes.length < 2 || partes[1].isBlank()) {
            enviarMensaje(bot, chatId,
                    "Indica el número de presupuesto. Ejemplo: /aceptado P-2026-0001");
            return;
        }

        String numero = partes[1].trim().toUpperCase();
        long autonomoId = autonomoOpt.get().getId();

        try {
            presupuestoService.cambiarEstadoPorNumero(autonomoId, numero, nuevoEstado);
        } catch (ServiceException e) {
            enviarMensaje(bot, chatId, e.getMessage());
            return;
        }
        log.info("Estado presupuesto {} → {} por telegramId={}", numero, nuevoEstado, telegramId);

        String emoji = switch (nuevoEstado) {
            case Presupuesto.ESTADO_ACEPTADO  -> "✅";
            case Presupuesto.ESTADO_RECHAZADO -> "❌";
            case Presupuesto.ESTADO_FACTURADO -> "🧾";
            default -> "✔️";
        };

        enviarMensaje(bot, chatId, String.format(
                "%s Presupuesto %s marcado como %s.",
                emoji, numero, nuevoEstado.toLowerCase()));
    }

    /**
     * /reenviar P-AAAA-NNNN — Regenera PDF + Excel y los reenvía por email.
     */
    private void reenviarPresupuesto(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();

        Optional<Autonomo> autonomoOpt = autonomoDao.findByTelegramId(telegramId);
        if (autonomoOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "No estás registrado. Usa /start para comenzar.");
            return;
        }

        Autonomo autonomo = autonomoOpt.get();

        if (autonomo.getEmail() == null || autonomo.getEmail().isBlank()) {
            enviarMensaje(bot, chatId,
                    "No tienes email configurado en tu perfil. Usa /perfil para añadirlo.");
            return;
        }

        String[] partes = message.getText().trim().split("\\s+", 2);
        if (partes.length < 2 || partes[1].isBlank()) {
            enviarMensaje(bot, chatId,
                    "Indica el número de presupuesto. Ejemplo: /reenviar P-2026-0001");
            return;
        }

        String numero = partes[1].trim().toUpperCase();

        Optional<Presupuesto> presupuestoOpt = presupuestoService.findByNumero(autonomo.getId(), numero);
        if (presupuestoOpt.isEmpty()) {
            enviarMensaje(bot, chatId, "No he encontrado el presupuesto " + numero + ".");
            return;
        }

        Presupuesto presupuesto = presupuestoOpt.get();

        bot.execute(SendChatAction.builder()
                .chatId(chatId)
                .action(ActionType.TYPING.toString())
                .build());

        try {
            byte[] pdfBytes  = pdfService.generarPresupuesto(presupuesto, autonomo);
            byte[] xlsxBytes = excelService.generarPresupuesto(presupuesto, autonomo);

            emailService.enviarConAdjuntos(
                    autonomo.getEmail(),
                    "Presupuesto " + numero + " (reenvío)",
                    "Adjunto encontrarás el presupuesto " + numero +
                    " para " + nvl(presupuesto.getClienteNombre()) + ".",
                    new EmailService.Adjunto(pdfBytes, "application/pdf",
                            "presupuesto_" + numero + ".pdf"),
                    new EmailService.Adjunto(xlsxBytes,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "presupuesto_" + numero + ".xlsx")
            );

            enviarMensaje(bot, chatId, "📧 Presupuesto " + numero +
                    " reenviado a " + autonomo.getEmail() + ".");
            log.info("Presupuesto {} reenviado por email a telegramId={}", numero, telegramId);

        } catch (ServiceException e) {
            log.error("Error reenviando presupuesto {} chatId={}: {}", numero, chatId, e.getMessage());
            enviarMensaje(bot, chatId, "⚠️ Error al reenviar el presupuesto. Inténtalo de nuevo.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers de estado y formato de listas
    // -------------------------------------------------------------------------

    private String formatearListaPresupuestos(List<Presupuesto> lista, boolean mostrarEstado) {
        StringBuilder sb = new StringBuilder();
        LocalDate hoy = LocalDate.now();
        int mostrados = Math.min(lista.size(), MAX_PRESUPUESTOS_LISTADO);

        for (int i = 0; i < mostrados; i++) {
            Presupuesto p = lista.get(i);
            String antiguedad = p.getCreatedAt() != null
                    ? formatearAntiguedad(p.getCreatedAt().toLocalDate(), hoy) : "";
            String linea = mostrarEstado
                    ? String.format("%d. <b>%s</b> | %s | %s | %s\n",
                            i + 1, p.getNumero(), nvl(p.getClienteNombre()),
                            formatDinero(p.getTotal()), p.getEstado().toLowerCase())
                    : String.format("%d. <b>%s</b> | %s | %s | %s\n",
                            i + 1, p.getNumero(), nvl(p.getClienteNombre()),
                            formatDinero(p.getTotal()), antiguedad);
            sb.append(linea);
        }

        if (lista.size() > MAX_PRESUPUESTOS_LISTADO) {
            sb.append(String.format("\n<i>... y %d más. Consulta el panel web para el listado completo.</i>",
                    lista.size() - MAX_PRESUPUESTOS_LISTADO));
        }

        return sb.toString();
    }

    private String formatearAntiguedad(LocalDate fecha, LocalDate hoy) {
        long dias = ChronoUnit.DAYS.between(fecha, hoy);
        if (dias == 0) return "hoy";
        if (dias == 1) return "ayer";
        if (dias < 7) return "hace " + dias + " días";
        if (dias < 30) return "hace " + (dias / 7) + " semana" + (dias / 7 == 1 ? "" : "s");
        return fecha.format(FMT_FECHA);
    }

    private String formatDinero(BigDecimal importe) {
        if (importe == null) return "0,00 €";
        return String.format("%,.2f €", importe)
                .replace(",", "X").replace(".", ",").replace("X", ".");
    }

    private String capitalizar(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Parsea un nombre de mes en español y devuelve el número de mes (1-12), o null si no se reconoce.
     */
    private static Integer parsearMes(String mes) {
        return MESES_ES.get(mes.toLowerCase());
    }

    private static final Map<String, Integer> MESES_ES = Map.ofEntries(
            Map.entry("enero",      1),
            Map.entry("febrero",    2),
            Map.entry("marzo",      3),
            Map.entry("abril",      4),
            Map.entry("mayo",       5),
            Map.entry("junio",      6),
            Map.entry("julio",      7),
            Map.entry("agosto",     8),
            Map.entry("septiembre", 9),
            Map.entry("octubre",   10),
            Map.entry("noviembre", 11),
            Map.entry("diciembre", 12)
    );

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
        String direccion = message.getText().trim();

        // Guardar dirección en notas (campo temporal durante el registro)
        session.getBorradorPresupuesto().setNotas(direccion.equals("-") ? null : direccion);
        session.setState(SessionState.REGISTRO_EMAIL);
        enviarMensaje(bot, chatId,
                "¿Cuál es tu email? Aquí recibirás los presupuestos y facturas en PDF.\n" +
                "(Es obligatorio para poder enviarte los documentos)");
    }

    private void procesarRegistroEmail(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String email = message.getText().trim();

        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            enviarMensaje(bot, chatId,
                    "El email no parece válido. Introduce una dirección de correo correcta (ej: nombre@ejemplo.com)");
            return;
        }

        String nombre   = session.getBorradorPresupuesto().getClienteNombre();
        String nif      = session.getBorradorPresupuesto().getDescripcion();
        String direccion = session.getBorradorPresupuesto().getNotas();

        Autonomo nuevo = new Autonomo();
        nuevo.setTelegramId(telegramId);
        nuevo.setNombre(nombre);
        nuevo.setNif(nif);
        nuevo.setDireccion(direccion);
        nuevo.setEmail(email);
        nuevo.setPlan(Autonomo.PLAN_FREE);

        autonomoDao.crear(nuevo);
        session.reset();

        enviarMensajeHtml(bot, chatId, String.format(
                "¡Registrado, %s! Ya puedes enviarme audios o escribirme los datos de tus presupuestos.\n\n" +
                "📧 Los documentos se enviarán a <b>%s</b>\n\n" +
                "Usa /ayuda para ver cómo funciona.", nombre, email));
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
