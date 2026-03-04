# Integración con Telegram Bot

## Dependencia Maven

```xml
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots</artifactId>
    <version>6.9.7.1</version>
</dependency>
```

## Arquitectura del Bot

El bot se ejecuta como un proceso independiente dentro del WAR de Tomcat, iniciándose en un
`ServletContextListener`. Usa el modo **webhook** (no long polling) para producción, ya que
Tomcat ya está escuchando HTTPS.

### Inicialización

```java
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
        }
    }
}
```

### Clase principal del Bot

```java
public class TuGestorBot extends TelegramLongPollingBot {
    
    private final VoiceHandler voiceHandler = new VoiceHandler();
    private final TextHandler textHandler = new TextHandler();
    private final CallbackHandler callbackHandler = new CallbackHandler();
    
    @Override
    public String getBotUsername() {
        return ConfigUtil.get("telegram.bot.username");
    }
    
    @Override
    public String getBotToken() {
        return ConfigUtil.get("telegram.bot.token");
    }
    
    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasMessage()) {
                Message msg = update.getMessage();
                if (msg.hasVoice()) {
                    voiceHandler.handle(this, msg);
                } else if (msg.hasText()) {
                    textHandler.handle(this, msg);
                }
            } else if (update.hasCallbackQuery()) {
                callbackHandler.handle(this, update.getCallbackQuery());
            }
        } catch (Exception e) {
            log.error("Error procesando update {}", update.getUpdateId(), e);
        }
    }
}
```

### Manejo de Audios (VoiceHandler)

El flujo de recepción de audio es:

1. Telegram envía el audio en formato OGG/Opus
2. Se descarga el fichero vía `getFile()` de la API de Telegram
3. Se envía a Whisper API para transcripción
4. Se procesa la transcripción con Claude API

```java
public class VoiceHandler {
    
    private final WhisperService whisperService = new WhisperService();
    private final ClaudeService claudeService = new ClaudeService();
    private final SessionManager sessionManager = new SessionManager();
    
    public void handle(TuGestorBot bot, Message message) throws TelegramApiException {
        long chatId = message.getChatId();
        
        // Indicar que estamos procesando
        bot.execute(SendChatAction.builder()
            .chatId(chatId)
            .action(ActionType.TYPING.toString())
            .build());
        
        // Descargar audio
        Voice voice = message.getVoice();
        GetFile getFile = new GetFile(voice.getFileId());
        org.telegram.telegrambots.meta.api.objects.File tgFile = bot.execute(getFile);
        java.io.File audioFile = bot.downloadFile(tgFile);
        
        // Transcribir
        String transcripcion = whisperService.transcribe(audioFile);
        
        // Estructurar con Claude
        DatosPresupuesto datos = claudeService.parsePresupuesto(transcripcion);
        
        // Guardar en sesión y presentar borrador
        UserSession session = sessionManager.getOrCreate(chatId);
        session.setBorradorPresupuesto(datos);
        session.setTranscripcion(transcripcion);
        
        // Enviar borrador con botones
        String borrador = formatearBorrador(datos);
        InlineKeyboardMarkup keyboard = crearTecladoConfirmacion();
        
        bot.execute(SendMessage.builder()
            .chatId(chatId)
            .text(borrador)
            .replyMarkup(keyboard)
            .parseMode("HTML")
            .build());
    }
}
```

### Sesiones Conversacionales

Los usuarios pueden estar en diferentes estados de una conversación. Usa un mapa en memoria
(con expiración) para mantener el estado:

```java
public class SessionManager {
    // ConcurrentHashMap con TTL de 30 minutos
    private final Map<Long, UserSession> sessions = new ConcurrentHashMap<>();
    
    public UserSession getOrCreate(long chatId) {
        return sessions.computeIfAbsent(chatId, k -> new UserSession(k));
    }
    
    // Limpieza periódica de sesiones expiradas (ScheduledExecutorService)
}

public class UserSession {
    private long chatId;
    private SessionState state;  // IDLE, ESPERANDO_CONFIRMACION, EDITANDO, etc.
    private DatosPresupuesto borradorPresupuesto;
    private String transcripcion;
    private Instant lastActivity;
}
```

### Inline Keyboards para Confirmación

```java
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
```

### Envío de PDF por Telegram

```java
public void enviarPdf(long chatId, java.io.File pdfFile, String caption) 
        throws TelegramApiException {
    bot.execute(SendDocument.builder()
        .chatId(chatId)
        .document(new InputFile(pdfFile))
        .caption(caption)
        .build());
}
```

## Comandos del Bot

| Comando | Descripción |
|---|---|
| `/start` | Registro inicial, pedir datos fiscales |
| `/presupuesto` | Iniciar nuevo presupuesto (pedir audio) |
| `/factura` | Convertir presupuesto en factura |
| `/clientes` | Listar clientes |
| `/perfil` | Ver/editar datos fiscales |
| `/ayuda` | Mostrar ayuda |
| `/plan` | Ver plan actual y opción de upgrade |

## Consideraciones

- El bot debe funcionar tanto en modo long polling (desarrollo) como webhook (producción)
- Los audios de Telegram tienen un límite de 20MB y duración variable
- Gestionar timeouts: la transcripción + IA puede tardar 5-10 segundos
- Enviar "typing..." action mientras se procesa
- Mensajes de error amigables en español coloquial
- Considerar que el usuario puede enviar varios audios seguidos
