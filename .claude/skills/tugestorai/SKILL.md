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


## Idioma

Comunicarse siempre en castellano (español de España).

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
5. El autónomo confirma o corrige por voz/texto
6. Se genera un PDF profesional con OpenPDF en memoria
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
| Email | Angus Mail (Jakarta Mail) | Envío de PDFs por email |
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
        // ... mapear campos
        return p;
    }
}
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
- **Logging**: SLF4J + Logback
- **SQL**: PreparedStatement siempre (nunca concatenación de strings). Parámetros con `?`
- **Codificación**: UTF-8 en todo (respuestas HTTP, conexiones DB, ficheros)

### Base de datos (PostgreSQL)

- Nombres de tablas y columnas en `snake_case`
- Claves primarias: `id BIGSERIAL PRIMARY KEY`
- Timestamps: `created_at TIMESTAMP DEFAULT NOW()`, `updated_at TIMESTAMP`
- Soft delete donde tenga sentido: `deleted_at TIMESTAMP NULL`
- Índices en columnas de búsqueda frecuente
- Foreign keys con `ON DELETE CASCADE` o `RESTRICT` según contexto

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

### Email (Angus Mail)

Lee `references/email-integration.md` para el envío de presupuestos y facturas por email.

## Modelo de Datos

Consulta el esquema completo en `references/schema.sql`. Usa siempre ese esquema como fuente de verdad para nombres de tablas, columnas y tipos.

## Flujos de Negocio Clave

### Flujo: Audio → Presupuesto PDF (flujo principal)

1. **Recepción**: `VoiceHandler` recibe el audio OGG de Telegram
2. **Descarga**: Se descarga el fichero de audio vía Telegram API
3. **Transcripción**: `WhisperService.transcribe(audioFile)` → texto en español
4. **Estructuración**: `ClaudeService.parsePresupuesto(transcripcion)` → JSON con cliente, conceptos, importes
5. **Borrador**: Bot presenta borrador formateado en Telegram con inline keyboard:
   - ✅ Confirmar
   - ✏️ Editar (fase posterior)
   - ❌ Cancelar
6. **Si confirma**:
   a. `PresupuestoService.crear(datos)` → Guarda en PostgreSQL
   b. `PdfService.generarPresupuesto(presupuesto)` → byte[] en memoria
   c. Bot envía el PDF como documento por Telegram al autónomo
   d. Bot pregunta: "¿Quieres enviarlo también por email?" con inline keyboard (Sí / No)
   e. Si sí → `EmailService.enviar(email, pdfBytes)` → envía al email del autónomo
7. **Si cancela**: Se descarta el borrador y se vuelve a estado IDLE
8. **Si edita** (fase posterior): Flujo de corrección por texto/voz

### Flujo: Texto → Presupuesto PDF (alternativo)

Mismo flujo que el de audio pero sin transcripción:

1. El autónomo escribe un mensaje de texto libre (no comando) por Telegram
2. Se envía directamente a `ClaudeService.parsePresupuesto()` (sin Whisper)
3. A partir de aquí, flujo idéntico al de audio: borrador → confirmar/cancelar → PDF → envío

Coste por presupuesto por texto: ~€0.001 (solo Claude Haiku, sin Whisper).

En TextHandler: si el mensaje no empieza por `/` y el usuario está en estado IDLE, tratar como texto de presupuesto.

### Flujo: Presupuesto → Factura

1. El autónomo solicita convertir presupuesto aceptado en factura
2. Se copian datos y líneas de detalle
3. Se añade IRPF (15% por defecto para autónomos)
4. Se genera número de factura secuencial
5. Se genera PDF de factura con formato fiscal español

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
2. Si NO está autorizado → responder "⛔ Este bot es privado. Contacta con el administrador para solicitar acceso." y no procesar nada más
3. Si está autorizado → procesar normalmente
4. La verificación debe estar en TuGestorBot.onUpdateReceived(), antes de delegar a handlers
5. Cachear los IDs autorizados en memoria (Set<Long>) y refrescar cada 5 minutos para no consultar la BD en cada mensaje

Administración de usuarios autorizados:
- Inserción manual en BD durante fase de pruebas
- Futuro: comando /autorizar (solo para admin) desde Telegram

## Generación de PDFs

Los PDFs NO se almacenan en disco. Se generan al vuelo en memoria y se descartan tras el envío:
- PdfService genera el PDF en un ByteArrayOutputStream, devuelve byte[]
- Se envía a Telegram como InputFile desde byte array
- Se envía por email como adjunto desde byte array
- Si el usuario quiere reenviar, se regenera desde los datos en BD
- No existe campo pdf_path en las tablas
- No existe configuración pdf.output.dir

## Protección de Costes

Límites configurables en config.properties:

| Límite | Valor | Config key |
|---|---|---|
| Duración máxima audio | 3 minutos (180s) | limit.audio.max.duration |
| Tamaño máximo audio | 1MB (1048576 bytes) | limit.audio.max.size |
| Peticiones por hora/usuario (audio+texto) | 10 | limit.requests.per.hour |
| Peticiones por día/usuario (audio+texto) | 30 | limit.requests.per.day |
| Reintentos Claude API por presupuesto | 1 | limit.claude.max.retries |
| Emails por día/usuario | 20 | limit.email.per.day |
| Coste diario global máximo | €3 | limit.cost.daily.max |
| Presupuestos/mes plan free | 5 | plan.free.limite.presupuestos |

Reglas:
- Los rate limits de audio y texto son COMPARTIDOS (mismo contador)
- El VoiceHandler y TextHandler validan los límites ANTES de llamar a APIs externas
- Si Claude devuelve JSON inválido, se reintenta UNA sola vez. Si falla otra vez, se informa al usuario
- El coste diario global se estima a €0.007 por audio y €0.001 por texto
- Rate limiting implementado con mapa en memoria con TTL
- Mensajes de rechazo amigables en español
- Contador de emails por usuario y día en memoria

## Seguridad

- API keys (Whisper, Claude, Telegram) en variables de entorno, nunca en código
- HTTPS obligatorio en producción
- CSP headers sin inline JS/CSS (usar CspFilter)
- Prepared statements siempre (prevención SQL injection)
- Escapar output HTML (prevención XSS)
- Validar y sanitizar toda entrada del usuario
- Tokens CSRF en formularios del panel web

## Despliegue

- Servidor: VPS IONOS con AlmaLinux
- Tomcat 10 detrás de Apache HTTP Server como reverse proxy
- SSL/TLS con Let's Encrypt
- PostgreSQL local o en servidor dedicado
- Variables de entorno para configuración sensible
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
9. **Prioriza el flujo principal** (audio → presupuesto → PDF) sobre funcionalidades secundarias
10. **Comunicarse siempre en castellano**