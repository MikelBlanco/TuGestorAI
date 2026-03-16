---
name: tugestorai
description: >
   Skill para desarrollar TuGestorAI, un bot de Telegram para autónomos españoles que genera
   presupuestos y facturas profesionales en PDF mediante mensajes de voz. Stack: Java 21 + Servlets/Tomcat
   (sin Spring), Vue 3 frontend, PostgreSQL, OpenPDF, TelegramBots (rubenlagus), Whisper API para
   transcripción de voz, y Claude API (Haiku) para estructuración de datos.

   Usa esta skill SIEMPRE que trabajes en el proyecto TuGestorAI o en cualquier tarea relacionada con:
   bot de Telegram en Java, generación de presupuestos/facturas PDF, transcripción de audio con Whisper,
   integración con Claude API, servlets Java sin Spring, o cualquier archivo dentro del repositorio
   tugestorai. También cuando el usuario mencione "presupuestos", "facturas", "bot telegram",
   "autónomos", o haga referencia a funcionalidades del proyecto como envío de PDFs, gestión de
   clientes, o procesamiento de voz. Actívate incluso si el usuario no nombra explícitamente el
   proyecto pero trabaja en archivos o patrones que coincidan con esta arquitectura.
---

# TuGestorAI - Skill de Desarrollo

## Visión del Proyecto

TuGestorAI es un bot de Telegram que permite a autónomos españoles del sector servicios (fontaneros,
electricistas, instaladores) generar presupuestos y facturas profesionales en PDF mediante mensajes
de voz, directamente desde su lugar de trabajo.

El flujo principal es:
1. El autónomo envía un audio por Telegram: "Presupuesto para María García, cambio de termo, material 280, mano de obra 120"
2. Whisper API transcribe el audio a texto
3. Claude API (Haiku) extrae y estructura los datos del presupuesto
4. El bot presenta un borrador para validación
5. El autónomo confirma, edita o cancela
6. Se genera un PDF y un Excel profesionales con OpenPDF y Apache POI en memoria
7. Se envía por Telegram y opcionalmente por email

## Stack Tecnológico

| Componente | Tecnología | Notas |
|---|---|---|
| Backend | Java 21, Servlets, Tomcat 10 | Sin Spring. Patrón MVC manual con servlets |
| Frontend | Vue 3 (confirmar Composition vs Options API) | Panel de administración web |
| Base de datos | PostgreSQL | Esquema relacional, sin ORM pesado (JDBC directo) |
| Bot | TelegramBots (rubenlagus) `org.telegram:telegrambots` | Recepción de audio y mensajes |
| Transcripción | Whisper API (OpenAI) | Coste ~€0.006 por audio de 60s |
| IA Estructuración | Claude API (Haiku) | Parseo de texto libre a JSON estructurado |
| PDF | OpenPDF | Fork open-source de iText, generación en memoria |
| Excel | Apache POI (`poi-ooxml:5.3.0`) | Generación de .xlsx en memoria |
| Email | Angus Mail (Jakarta Mail) | Envío de PDFs y Excel por email |
| Cifrado | AES-256-GCM (Java Crypto) | Cifrado de datos sensibles en BD |
| Build | Maven | Gestión de dependencias y build |
| Servidor | Tomcat 10+ en Linux (AlmaLinux/IONOS) | Despliegue en VPS |

## Arquitectura del Proyecto

### Estructura de directorios

```
tugestorai/
├── pom.xml
├── CLAUDE.md
├── .claude/skills/tugestorai/
│   ├── SKILL.md
│   └── references/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── org/gestorai/
│   │   │       ├── bot/              # Lógica del bot de Telegram
│   │   │       │   ├── TuGestorBot.java
│   │   │       │   ├── handlers/     # Handlers por tipo de mensaje
│   │   │       │   │   ├── VoiceHandler.java
│   │   │       │   │   ├── TextHandler.java
│   │   │       │   │   └── CallbackHandler.java
│   │   │       │   └── session/      # Estado conversacional del usuario
│   │   │       │       └── UserSession.java
│   │   │       ├── servlet/          # Servlets HTTP (panel web)
│   │   │       │   ├── DashboardServlet.java
│   │   │       │   └── ApiServlet.java
│   │   │       ├── service/          # Lógica de negocio
│   │   │       │   ├── WhisperService.java
│   │   │       │   ├── ClaudeService.java
│   │   │       │   ├── PdfService.java
│   │   │       │   ├── ExcelService.java
│   │   │       │   ├── EmailService.java
│   │   │       │   ├── PresupuestoService.java
│   │   │       │   └── FacturaService.java
│   │   │       ├── model/            # POJOs / entidades
│   │   │       │   ├── Usuario.java
│   │   │       │   ├── Presupuesto.java
│   │   │       │   ├── Factura.java
│   │   │       │   ├── LineaDetalle.java
│   │   │       │   └── DatosPresupuesto.java
│   │   │       ├── dao/              # Acceso a datos (JDBC)
│   │   │       │   ├── BaseDao.java
│   │   │       │   ├── UsuarioDao.java
│   │   │       │   ├── PresupuestoDao.java
│   │   │       │   └── FacturaDao.java
│   │   │       ├── util/             # Utilidades
│   │   │       │   ├── DbUtil.java
│   │   │       │   ├── ConfigUtil.java
│   │   │       │   ├── CryptoUtil.java
│   │   │       │   ├── RateLimiter.java
│   │   │       │   └── SecurityUtil.java
│   │   │       └── filter/           # Filtros de servlet
│   │   │           ├── AuthFilter.java
│   │   │           └── CspFilter.java
│   │   ├── resources/
│   │   │   ├── config.properties
│   │   │   └── logback.xml
│   │   └── webapp/
│   │       ├── WEB-INF/
│   │       │   └── web.xml
│   │       └── static/
│   └── test/
│       └── java/
│           └── org/gestorai/
├── frontend/                          # Proyecto Vue 3
│   ├── package.json
│   ├── vite.config.js
│   └── src/
└── docs/
```

### Patrones de Código

Dado que el proyecto es Java sin Spring, estos son los patrones que se deben seguir consistentemente:

#### Patrón DAO con JDBC directo

```java
public class PresupuestoDao extends BaseDao {
    
    public Optional<Presupuesto> findById(long id) {
        String sql = "SELECT * FROM presupuestos WHERE id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Error buscando presupuesto id=" + id, e);
        }
        return Optional.empty();
    }
    
    private Presupuesto mapRow(ResultSet rs) throws SQLException {
        Presupuesto p = new Presupuesto();
        p.setId(rs.getLong("id"));
        p.setNumero(rs.getString("numero"));
        p.setClienteNombre(rs.getString("cliente_nombre"));
        // Descifrar campos sensibles
        p.setAudioTranscript(CryptoUtil.decrypt(rs.getString("audio_transcript")));
        return p;
    }
}
```

#### Patrón DAO con cifrado de datos sensibles

```java
// Al guardar: cifrar campos sensibles
ps.setString(3, CryptoUtil.encrypt(usuario.getNif()));
ps.setString(4, CryptoUtil.encrypt(usuario.getDireccion()));
ps.setString(5, CryptoUtil.encrypt(usuario.getTelefono()));
ps.setString(6, CryptoUtil.encrypt(usuario.getEmail()));

// Al leer: descifrar campos sensibles
usuario.setNif(CryptoUtil.decrypt(rs.getString("nif")));
usuario.setDireccion(CryptoUtil.decrypt(rs.getString("direccion")));
usuario.setTelefono(CryptoUtil.decrypt(rs.getString("telefono")));
usuario.setEmail(CryptoUtil.decrypt(rs.getString("email")));
```

#### Patrón Servlet con JSON

```java
@WebServlet("/api/presupuestos/*")
public class PresupuestoApiServlet extends HttpServlet {
    
    private final PresupuestoService service = new PresupuestoService();
    private final Gson gson = new Gson();
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) 
            throws ServletException, IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            List<Presupuesto> lista = service.listarPorUsuario(getUsuarioId(req));
            resp.getWriter().write(gson.toJson(lista));
        } else {
            long id = Long.parseLong(pathInfo.substring(1));
            service.buscarPorId(id)
                .ifPresentOrElse(
                    p -> writeJson(resp, p),
                    () -> sendError(resp, 404, "No encontrado")
                );
        }
    }
}
```

#### Gestión de conexiones

```java
public class DbUtil {
    private static HikariDataSource dataSource;
    
    static {
        Class.forName("org.postgresql.Driver");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(ConfigUtil.get("db.url"));
        config.setUsername(ConfigUtil.get("db.user"));
        config.setPassword(ConfigUtil.get("db.password"));
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
    }
    
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}
```

#### Configuración centralizada

```java
public class ConfigUtil {
    private static final Properties props = new Properties();
    
    static {
        try (InputStream is = ConfigUtil.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            props.load(is);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo cargar config.properties", e);
        }
        // Variables de entorno sobreescriben fichero (para producción)
        props.stringPropertyNames().forEach(key -> {
            String envKey = key.toUpperCase().replace('.', '_');
            String envVal = System.getenv(envKey);
            if (envVal != null) props.setProperty(key, envVal);
        });
    }
    
    public static String get(String key) {
        return props.getProperty(key);
    }
}
```

## Convenciones del Proyecto

### Java

- **Java 21**: Usar text blocks, records donde tenga sentido, try-with-resources siempre, switch expressions, pattern matching
- **Sin Spring**: No usar ninguna dependencia de Spring. Todo es servlets + JDBC + clases propias
- **Nomenclatura**: Clases en PascalCase, métodos/variables en camelCase, constantes en UPPER_SNAKE_CASE
- **Paquetes**: `org.gestorai.{bot,servlet,service,model,dao,util,filter}`
- **Excepciones**: Excepciones propias que extiendan RuntimeException (`DaoException`, `ServiceException`, `BotException`)
- **Logging**: SLF4J + Logback. NUNCA loguear datos personales (NIF, emails, teléfonos, direcciones)
- **SQL**: PreparedStatement siempre (nunca concatenación de strings). Parámetros con `?`
- **Codificación**: UTF-8 en todo (respuestas HTTP, conexiones DB, ficheros)

### Base de datos (PostgreSQL)

- Nombres de tablas y columnas en `snake_case`
- Claves primarias: `id BIGSERIAL PRIMARY KEY`
- Timestamps: `created_at TIMESTAMP DEFAULT NOW()`, `updated_at TIMESTAMP`
- Soft delete donde tenga sentido: `deleted_at TIMESTAMP NULL`
- Índices en columnas de búsqueda frecuente
- Foreign keys con `ON DELETE CASCADE` o `RESTRICT` según contexto
- Campos sensibles cifrados con AES-256-GCM almacenados como TEXT (Base64)

### Vue 3 (Frontend)

- Confirmar si es Composition API o Options API revisando el código existente
- Componentes en PascalCase: `PresupuestoList.vue`
- Composables en `use` prefix: `usePresupuestos.js`
- Llamadas al backend centralizadas en `api/`
- Vite como bundler

### Documentación

- Comentarios JavaDoc en clases y métodos públicos
- Comentarios en español (el código es para desarrollador hispanohablante)
- README.md con instrucciones de despliegue

## Integraciones Externas

### Telegram Bot (TelegramBots rubenlagus)

Lee `references/telegram-integration.md` para detalles de implementación del bot, manejo de
audios, sesiones conversacionales e inline keyboards.

### Whisper API (OpenAI)

Lee `references/whisper-integration.md` para detalles de cómo enviar audios OGG desde Telegram
a Whisper API y obtener la transcripción.

### Claude API (Anthropic Haiku)

Lee `references/claude-integration.md` para el diseño de prompts que extraen datos estructurados
de presupuestos a partir de texto libre en español.

### OpenPDF

Lee `references/pdf-generation.md` para la generación de PDFs profesionales de presupuestos y
facturas con datos fiscales españoles.

### Excel (Apache POI)

Dependencia: `org.apache.poi:poi-ooxml:5.3.0`

ExcelService genera ficheros .xlsx en memoria (ByteArrayOutputStream, devuelve byte[]):
- Cabecera: datos fiscales del autónomo (nombre/razón social, NIF, dirección, teléfono, email)
- Datos del documento: número de presupuesto, fecha
- Datos del cliente: nombre, dirección, teléfono (sin NIF, eso es solo para facturas)
- Tabla de conceptos: concepto, tipo, cantidad, precio unitario, importe
- Fila de totales: subtotal, IVA (%), importe IVA, total
- Estilo profesional: cabeceras en negrita, bordes, columnas auto-dimensionadas

### Email (Angus Mail)

Lee `references/email-integration.md` para el envío de presupuestos y facturas por email.

## Modelo de Datos

Consulta el esquema completo en `references/schema.sql`. Usa siempre ese esquema como fuente de verdad para nombres de tablas, columnas y tipos.

### Datos del cliente en presupuestos vs facturas

Los presupuestos contienen datos básicos del cliente que el autónomo dicta por voz:
- Nombre del cliente
- Teléfono (si lo menciona)
- Dirección (si la menciona)
- **NO incluyen NIF/DNI del cliente**

Las facturas SÍ requieren NIF del cliente (obligatorio fiscalmente y para TicketBAI/Batuz).
Cuando un presupuesto se convierte en factura:
1. Si el cliente ya existe en BD con NIF → se reutiliza
2. Si no tiene NIF → el bot lo pide al autónomo: "Para generar la factura necesito el NIF del cliente. ¿Cuál es?"
3. Se almacena cifrado en la tabla `clientes` para futuras facturas

## Flujos de Negocio Clave

### Flujo: Audio → Presupuesto (flujo principal)

1. **Recepción**: `VoiceHandler` recibe el audio OGG de Telegram
2. **Descarga**: Se descarga el fichero de audio vía Telegram API
3. **Transcripción**: `WhisperService.transcribe(audioFile)` → texto en español
4. **Borrado del audio**: El fichero de audio se borra del disco INMEDIATAMENTE después de transcribir
5. **Estructuración**: `ClaudeService.parsePresupuesto(transcripcion)` → JSON con cliente, conceptos, importes. SOLO se envía la transcripción, NUNCA datos fiscales del autónomo
6. **Borrador**: Bot presenta borrador formateado en Telegram con datos enmascarados (ver Protección en Telegram) e inline keyboard:
   - ✅ Confirmar
   - ✏️ Editar
   - ❌ Cancelar
7. **Si confirma**:
   a. `PresupuestoService.crear(datos)` → Guarda en PostgreSQL (datos sensibles cifrados)
   b. `PdfService.generarPresupuesto(presupuesto)` → byte[] en memoria
   c. `ExcelService.generarPresupuesto(presupuesto)` → byte[] en memoria
   d. Bot pregunta: "¿Cómo quieres recibir los documentos?" con inline keyboard (📱 Telegram / 📧 Email / 📱📧 Ambos)
   e. Envía según la opción elegida
   f. Si se envía por Telegram: "⚠️ Por seguridad, los documentos se eliminarán del chat en 5 minutos. Descárgalos o solicita reenvío por email."
   g. Programar borrado de los mensajes con documentos tras 5 minutos
   h. Reemplazar el mensaje del borrador por: "✅ Presupuesto P-2025-0001 generado (484,00€)"
8. **Si edita**: Flujo de edición (ver más abajo)
9. **Si cancela**: Reemplazar borrador por "❌ Presupuesto cancelado", limpiar sesión, volver a IDLE

### Flujo: Edición de presupuesto

Cuando el autónomo pulsa ✏️ Editar en el borrador:

1. Bot responde: "✏️ ¿Qué quieres corregir? Envía un texto o audio con los cambios.\nEjemplos: 'El material son 320, no 280', 'Añade desplazamiento 30 euros', 'El cliente es Juan, no María'"
2. El estado de la sesión cambia a EDITANDO
3. El autónomo envía corrección por texto o audio:
   - Si audio → Whisper transcribe → texto de corrección
   - Si texto → se usa directamente
4. Se envía a Claude Haiku el borrador actual + la corrección:
   ```
   Borrador actual: {JSON del borrador}
   Corrección del usuario: {texto de corrección}
   Genera el JSON actualizado aplicando la corrección.
   ```
5. Claude devuelve el borrador actualizado
6. Bot presenta nuevo borrador con los mismos botones (✅ Confirmar / ✏️ Editar / ❌ Cancelar)
7. Se repite hasta que el autónomo confirme o cancele
8. Límite de ediciones por presupuesto: 5 (para evitar bucles infinitos y coste excesivo)

### Flujo: Texto → Presupuesto (alternativo)

Mismo flujo que el de audio pero sin transcripción:

1. El autónomo escribe un mensaje de texto libre (no comando) por Telegram
2. Se envía directamente a `ClaudeService.parsePresupuesto()` (sin Whisper). SOLO el texto del usuario, NUNCA datos fiscales
3. A partir de aquí, flujo idéntico al de audio: borrador → confirmar/editar/cancelar → documentos → envío

Coste por presupuesto por texto: ~€0.001 (solo Claude Haiku, sin Whisper).

En TextHandler: si el mensaje no empieza por `/` y el usuario está en estado IDLE, tratar como texto de presupuesto. Si está en estado EDITANDO, tratar como corrección.

### Flujo: Presupuesto → Factura

1. El autónomo solicita convertir presupuesto aceptado en factura
2. Se copian datos y líneas de detalle
3. Si el cliente no tiene NIF en BD → bot lo pide al autónomo
4. Se añade IRPF (15% por defecto para autónomos)
5. Se genera número de factura secuencial (correlativo sin saltos, requisito fiscal)
6. Se genera PDF y Excel de factura con formato fiscal español
7. Factura incluye: NIF emisor, NIF receptor, fecha, base imponible, tipo IVA, cuota IVA, IRPF

### Flujo: Consulta de presupuestos/facturas

El autónomo puede consultar sus documentos por dos vías:

**Vía Telegram (comandos del bot):**
- `/presupuestos` — Lista presupuestos del mes actual (número, cliente, importe, estado)
- `/presupuestos [mes]` — Lista de un mes concreto (ej: `/presupuestos marzo`)
- `/facturas` — Lista facturas del mes actual
- `/facturas [mes]` — Lista de un mes concreto
- Respuesta como mensaje de texto con formato resumido. Si hay más de 10 resultados, paginar con inline keyboard (◀ Anterior / Siguiente ▶)

**Vía panel web (Vue):**
- Vista de tabla con columnas: número, fecha, cliente, importe total, estado
- Filtros: rango de fechas, estado (borrador/enviado/aceptado/rechazado), cliente
- Ordenación por fecha, importe o cliente
- Acción rápida: descargar PDF, reenviar, convertir a factura
- Resumen mensual: total facturado, total presupuestado, tasa de conversión

**Consultas SQL base:**
- Presupuestos del mes: filtrar por `usuario_id` y `created_at >= date_trunc('month', CURRENT_DATE)`
- Facturas del mes: igual sobre tabla `facturas`
- Resumen mensual: `SUM(total)`, `COUNT(*)`, agrupado por estado
- Siempre ordenar por `created_at DESC`
- Los DAOs deben ofrecer métodos: `listarPorMes(usuarioId, year, month)`, `resumenMensual(usuarioId, year, month)`

### Límites Freemium

- Plan free: 5 presupuestos/mes (no facturas)
- Plan pro: Ilimitado + facturación + estadísticas
- El contador se reinicia el día 1 de cada mes
- `PresupuestoService` verifica límites antes de crear

## Control de Acceso

El bot es privado. Solo los usuarios autorizados pueden interactuar con él.

Tabla de control:
```sql
CREATE TABLE usuarios_autorizados (
    telegram_id BIGINT PRIMARY KEY,
    autorizado_por VARCHAR(200),
    created_at TIMESTAMP DEFAULT NOW()
);
```

Flujo de verificación:
1. CADA mensaje que llega al bot, antes de cualquier procesamiento, se verifica si el telegram_id está en `usuarios_autorizados`
2. Si NO está autorizado → responder: "⛔ Este bot es privado.\nTu ID de Telegram es: {chatId}\nEnvía este número al administrador para solicitar acceso."
3. Si está autorizado → procesar normalmente
4. La verificación debe estar en TuGestorBot.onUpdateReceived(), antes de delegar a handlers
5. Cachear los IDs autorizados en memoria (Set<Long>) y refrescar cada 5 minutos para no consultar la BD en cada mensaje

Administración de usuarios autorizados:
- Inserción manual en BD durante fase de pruebas
- Futuro: comando /autorizar (solo para admin) desde Telegram

### Consentimiento del usuario (RGPD)

En el flujo de /start, al registrarse por primera vez, informar al usuario:
"ℹ️ Tus audios se procesan mediante servicios de IA externos para generar los presupuestos. No se almacenan los audios tras el procesamiento. Tus datos fiscales se guardan cifrados en nuestra base de datos."

El usuario debe aceptar antes de poder usar el bot (inline keyboard: ✅ Acepto / ❌ No acepto).
Si no acepta, no se completa el registro y no puede usar el bot.
Guardar la fecha de aceptación en la tabla usuarios.

## Generación de Documentos (PDF y Excel)

Los documentos NO se almacenan en disco. Se generan al vuelo en memoria y se descartan tras el envío:
- PdfService genera PDF en ByteArrayOutputStream, devuelve byte[]
- ExcelService genera XLSX en ByteArrayOutputStream, devuelve byte[]
- Se envían a Telegram y/o email según elija el usuario
- Si el usuario quiere reenviar, se regeneran desde los datos en BD
- No se almacena nada en disco

## Protección en Telegram

Los chats con bots de Telegram NO tienen cifrado extremo a extremo. Medidas para proteger datos sensibles en el chat:

### 1. Borrado automático de documentos
Cuando se envían PDF/Excel por Telegram, avisar al usuario:
"⚠️ Por seguridad, los documentos se eliminarán del chat en 5 minutos. Descárgalos o solicita reenvío por email."
Programar borrado de los mensajes con documentos transcurridos los 5 minutos usando `deleteMessage()` de la API de Telegram.

### 2. Enmascarado de datos en borradores
En el mensaje de borrador que se muestra en el chat, enmascarar datos sensibles:
- NIF del autónomo: `****1234B` (solo últimos 5 caracteres)
- Teléfono: `***456` (solo últimos 3 dígitos)
- Email: `m****@gmail.com` (solo primera letra y dominio)
  Los datos completos solo aparecen en el PDF/Excel.

### 3. Preferencia de email para documentos
Al confirmar un presupuesto, preguntar al usuario cómo quiere recibir los documentos:
"¿Cómo quieres recibir los documentos?" con inline keyboard:
- 📱 Telegram
- 📧 Email (recomendado)
- 📱📧 Ambos

### 4. Reemplazo de borradores tras procesar
Una vez el usuario confirma o cancela, editar el mensaje del borrador y reemplazar todo el contenido por un resumen breve:
- Si confirmó: "✅ Presupuesto P-2025-0001 generado (484,00€)"
- Si canceló: "❌ Presupuesto cancelado"
  Así los datos detallados desaparecen del historial del chat.

### 5. Limpieza de sesión
Limpiar `UserSession.transcripcion` y `UserSession.borradorPresupuesto` inmediatamente después de guardar en BD o cancelar. No mantener datos sensibles en memoria más de lo necesario.

## Cifrado de Datos

### Cifrado de disco (producción)
En el VPS IONOS con AlmaLinux, cifrar la partición de PostgreSQL con LUKS. Configurar en despliegue, no afecta al código.

### Cifrado de columnas (aplicación)
CryptoUtil cifra/descifra campos sensibles con AES-256-GCM antes de guardar en BD.
La clave de cifrado se almacena SOLO como variable de entorno: `CRYPTO_SECRET_KEY`

Campos cifrados:

| Tabla | Columnas cifradas |
|---|---|
| usuarios | nif, direccion, telefono, email |
| clientes | nif, direccion, telefono, email |
| presupuestos | audio_transcript |

Estos campos se almacenan como TEXT en BD (valor cifrado en Base64).
Los DAOs cifran al guardar y descifran al leer, de forma transparente para el resto de la app.

No se puede hacer WHERE sobre campos cifrados. Buscar por campos no cifrados (id, telegram_id, nombre) y descifrar después.

## Protección de Datos y APIs Externas

### Minimización de datos enviados a APIs
- A Whisper (OpenAI): solo el audio, inevitable
- A Claude (Haiku): solo la transcripción del audio (o texto del usuario). NUNCA enviar datos fiscales del autónomo (NIF, dirección, etc.). Esos datos se añaden después desde la BD al generar el documento
- En el flujo de edición, enviar a Claude solo el JSON del borrador + la corrección, nunca datos fiscales adicionales
- Los audios descargados de Telegram se borran del disco INMEDIATAMENTE después de transcribir. No deben quedar en /tmp ni en ningún otro sitio

### Políticas de proveedores
- Usar siempre la API de pago (no versiones web gratuitas). Tanto OpenAI como Anthropic garantizan que los datos de la API no se usan para entrenamiento

### Logging seguro
- NUNCA loguear datos personales: NIF, emails, teléfonos, direcciones, contenido de transcripciones
- Loguear solo IDs, números de presupuesto y mensajes genéricos
- En caso de error, loguear solo el tipo de error y el contexto, no los datos que causaron el error

## Protección de Costes

Límites configurables en config.properties:

| Límite | Valor | Config key |
|---|---|---|
| Duración máxima audio | 3 minutos (180s) | limit.audio.max.duration |
| Tamaño máximo audio | 1MB (1048576 bytes) | limit.audio.max.size |
| Peticiones por hora/usuario (audio+texto) | 10 | limit.requests.per.hour |
| Peticiones por día/usuario (audio+texto) | 30 | limit.requests.per.day |
| Reintentos Claude API por presupuesto | 1 | limit.claude.max.retries |
| Ediciones máximas por presupuesto | 5 | limit.edits.per.presupuesto |
| Emails por día/usuario | 20 | limit.email.per.day |
| Coste diario global máximo | €3 | limit.cost.daily.max |
| Presupuestos/mes plan free | 5 | plan.free.limite.presupuestos |
| Tiempo borrado docs Telegram | 5 minutos (300s) | limit.telegram.doc.ttl |

Reglas:
- Los rate limits de audio y texto son COMPARTIDOS (mismo contador)
- Las ediciones de un presupuesto cuentan como peticiones a efectos de rate limiting
- El VoiceHandler y TextHandler validan los límites ANTES de llamar a APIs externas
- Si Claude devuelve JSON inválido, se reintenta UNA sola vez. Si falla otra vez, se informa al usuario
- El coste diario global se estima a €0.007 por audio y €0.001 por texto
- Rate limiting implementado con mapa en memoria con TTL
- Mensajes de rechazo amigables en español
- Contador de emails por usuario y día en memoria

## Seguridad

- API keys (Whisper, Claude, Telegram) y contraseñas de BD SOLO en variables de entorno, nunca en config.properties ni en código
- Clave de cifrado AES SOLO en variable de entorno: `CRYPTO_SECRET_KEY`
- HTTPS obligatorio en producción
- CSP headers sin inline JS/CSS (usar CspFilter)
- Prepared statements siempre (prevención SQL injection)
- Escapar output HTML (prevención XSS)
- Validar y sanitizar toda entrada del usuario
- Tokens CSRF en formularios del panel web
- Datos sensibles cifrados en BD con AES-256-GCM
- Audios borrados inmediatamente tras transcripción
- Documentos en Telegram borrados tras 5 minutos
- Borradores reemplazados por resumen tras procesar
- Datos enmascarados en mensajes del chat
- Sesiones limpiadas tras uso
- Logs sin datos personales

## Despliegue

- Servidor: VPS IONOS con AlmaLinux
- Disco cifrado con LUKS (partición de PostgreSQL)
- Tomcat 10 detrás de Apache HTTP Server como reverse proxy
- SSL/TLS con Let's Encrypt
- PostgreSQL con SSL habilitado
- Variables de entorno para toda configuración sensible
- Build: `mvn clean package` genera WAR
- Frontend: `npm run build` en `frontend/`, copiar `dist/` a `webapp/static/`

## Cuando trabajes en este proyecto

1. **Antes de generar código nuevo**, revisa la estructura existente del proyecto para mantener consistencia
2. **Sigue los patrones** de DAO, Service y Servlet establecidos — no introduzcas frameworks ni librerías adicionales sin discutirlo
3. **Todo el SQL** debe usar PreparedStatement con parámetros
4. **Los textos de usuario** (mensajes del bot, errores, labels) deben estar en español
5. **Genera tests** cuando se creen nuevos servicios o DAOs
6. **Documenta** con JavaDoc las clases públicas
7. **Si necesitas detalles de integración**, lee el fichero de referencia correspondiente en `references/`
8. **Respeta el modelo freemium**: toda funcionalidad nueva debe considerar los límites del plan
9. **Prioriza el flujo principal** (audio → presupuesto → PDF + Excel) sobre funcionalidades secundarias
10. **Cifra datos sensibles** en DAOs al guardar y descifra al leer
11. **No loguees datos personales** (NIF, emails, teléfonos, direcciones, transcripciones)
12. **Enmascara datos sensibles** en mensajes de Telegram
13. **Borra/reemplaza mensajes** con datos sensibles del chat tras procesar
14. **Comunicarse siempre en castellano**
