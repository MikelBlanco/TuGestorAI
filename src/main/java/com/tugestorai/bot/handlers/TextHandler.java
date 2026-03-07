package com.tugestorai.bot.handlers;

import com.tugestorai.bot.TuGestorBot;
import com.tugestorai.bot.session.SessionManager;
import com.tugestorai.bot.session.SessionState;
import com.tugestorai.bot.session.UserSession;
import com.tugestorai.dao.UsuarioDao;
import com.tugestorai.model.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.Optional;

/**
 * Gestiona los mensajes de texto y comandos del bot.
 */
public class TextHandler {

    private static final Logger log = LoggerFactory.getLogger(TextHandler.class);

    private final UsuarioDao usuarioDao = new UsuarioDao();
    private final SessionManager sessionManager = SessionManager.getInstance();

    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        String texto = message.getText().trim();
        UserSession session = sessionManager.getOrCreate(chatId);

        // Comandos tienen prioridad sobre el estado de sesión
        if (texto.startsWith("/")) {
            manejarComando(bot, message, session);
            return;
        }

        // Texto libre según el estado actual de la sesión
        switch (session.getState()) {
            case REGISTRO_NOMBRE -> procesarRegistroNombre(bot, message, session);
            case REGISTRO_NIF    -> procesarRegistroNif(bot, message, session);
            case REGISTRO_DIRECCION -> procesarRegistroDireccion(bot, message, session);
            default -> enviarMensaje(bot, chatId,
                    "Envíame un audio con el presupuesto o usa /ayuda para ver los comandos disponibles.");
        }
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
                    "Envíame un mensaje de voz con los datos del presupuesto y lo proceso al momento.");
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
                    "¡Bienvenido de nuevo, %s! Envíame un audio con el presupuesto o usa /ayuda.",
                    existente.get().getNombre()));
            return;
        }

        // Nuevo usuario: iniciar flujo de registro
        session.setState(SessionState.REGISTRO_NOMBRE);
        enviarMensaje(bot, chatId,
                "¡Hola! Soy TuGestorAI, tu asistente para presupuestos y facturas.\n\n" +
                "Para empezar, ¿cuál es tu nombre o el de tu negocio?");
    }

    private void enviarAyuda(TuGestorBot bot, long chatId) throws TelegramApiException {
        String ayuda = """
                <b>Comandos disponibles:</b>

                🎤 <b>Presupuesto por voz</b> — Envía un audio con los datos
                /presupuesto — Recordatorio de cómo crear un presupuesto
                /perfil — Ver y editar tus datos fiscales
                /plan — Ver tu plan actual
                /ayuda — Mostrar esta ayuda

                <b>Ejemplo de audio:</b>
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
                        int restantes = Usuario.LIMITE_PRESUPUESTOS_FREE - u.getPresupuestosMes();
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

        // Guardamos temporalmente el nombre en la sesión usando el campo descripcion
        // del borrador como almacén temporal hasta tener un DTO de registro dedicado
        session.setBorradorPresupuesto(new com.tugestorai.model.DatosPresupuesto());
        session.getBorradorPresupuesto().setClienteNombre(nombre); // reutilizamos campo para nombre temporal
        session.setState(SessionState.REGISTRO_NIF);
        enviarMensaje(bot, chatId, "¿Cuál es tu NIF o CIF? (Puedes saltarlo respondiendo con un guión -)");
    }

    private void procesarRegistroNif(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        String nif = message.getText().trim();

        // Guardamos NIF en descripcion como almacén temporal
        session.getBorradorPresupuesto().setDescripcion(nif.equals("-") ? null : nif);
        session.setState(SessionState.REGISTRO_DIRECCION);
        enviarMensaje(bot, chatId, "¿Cuál es tu dirección fiscal? (Puedes saltarla con -)");
    }

    private void procesarRegistroDireccion(TuGestorBot bot, Message message, UserSession session)
            throws TelegramApiException {
        long chatId = message.getChatId();
        long telegramId = message.getFrom().getId();
        String direccion = message.getText().trim();

        // Recuperar datos temporales del borrador
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
                "¡Registrado, %s! Ya puedes enviarme audios con tus presupuestos.\n\n" +
                "Usa /ayuda para ver cómo funciona.", nombre));
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