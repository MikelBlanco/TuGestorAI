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

Telegram se usa SOLO como interfaz de entrada y validación. Los documentos generados (PDF/Excel)
se envían SIEMPRE por email, nunca por Telegram.

El flujo principal es:
1. El autónomo envía un audio por Telegram
2. Whisper API transcribe el audio a texto
3. Claude API (Haiku) extrae y estructura los datos del presupuesto
4. El bot presenta un borrador en Telegram (sin datos fiscales) para validación
5. El autónomo confirma, edita o cancela
6. Se genera PDF y Excel en memoria con datos fiscales completos desde BD
7. Se envían los documentos por email al autónomo

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
| Email | Angus Mail (Jakarta Mail) | Envío de documentos al autónomo |
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
│   │   │       │   ├── FacturaService.java
│   │   │       │   └── NotificacionService.java
│   │   │       ├── model/            # POJOs / entidades
│   │   │       │   ├── Autonomo.java
│   │   │       │   ├── Cliente.java
│   │   │       │   ├── Presupuesto.java
│   │   │       │   ├── Factura.java
│   │   │       │   ├── LineaDetalle.java
│   │   │       │   ├── DatosPresupuesto.java
│   │   │       │   └── EstadoPresupuesto.java
│   │   │       ├── dao/              # Acceso a datos (JDBC)
│   │   │       │   ├── BaseDao.java
│   │   │       │   ├── AutonomoDao.java
│   │   │       │   ├── ClienteDao.java
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

### Nomenclatura de entidades

| Tabla BD | Clase Java | Descripción |
|---|---|---|
| `autonomos` | `Autonomo.java` | Los autónomos que usan el bot (fontanero, electricista...) |
| `clientes` | `Cliente.java` | Las personas a las que los autónomos hacen presupuestos |
| `presupuestos` | `Presupuesto.java` | Presupuestos generados |
| `facturas` | `Factura.java` | Facturas emitidas |
| `lineas_detalle` | `LineaDetalle.java` | Conceptos de presupuestos/facturas |
| `autonomos_autorizados` | — | Control de acceso al bot |

**IMPORTANTE**: La tabla de autónomos se llama `autonomos` (NO `usuarios`). Las foreign keys son `autonomo_id` (NO `usuario_id`). Los DAOs son `AutonomoDao` (NO `UsuarioDao`). Mantener esta nomenclatura en todo el código.

### Patrones de Código

Dado que el proyecto es Java sin Spring, estos son los patrones que se deben seguir consistentemente:

#### Patrón DAO con JDBC directo

```java
public class PresupuestoDao extends BaseDao {
    
    public Optional<Presupuesto> findById(long autonomoId, long id) {
        validateAutonomoId(autonomoId);
        String sql = "SELECT * FROM presupuestos WHERE id = ? AND autonomo_id = ?";
        try (Connection conn = DbUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setLong(2, autonomoId);
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
        p.setAutonomoId(rs.getLong("autonomo_id"));
        p.setNumero(rs.getString("numero"));
        p.setClienteNombre(rs.getString("cliente_nombre"));
        p.setEstado(EstadoPresupuesto.valueOf(rs.getString("estado")));
        p.setAudioTranscript(CryptoUtil.decrypt(rs.getString("audio_transcript")));
        return p;
    }
}
```

#### BaseDao con validación de autonomo_id

```java
public abstract class BaseDao {
    
    /**
     * Valida que el autonomoId sea válido.
     * TODAS las consultas de datos de negocio DEBEN llamar a este método.
     */
    protected void validateAutonomoId(long autonomoId) {
        if (autonomoId <= 0) {
            throw new DaoException("autonomoId obligatorio en todas las consultas");
        }
    }
}
```

#### Patrón DAO con cifrado de datos sensibles

```java
// Al guardar: cifrar campos sensibles
ps.setString(3, CryptoUtil.encrypt(autonomo.getNif()));
ps.setString(4, CryptoUtil.encrypt(autonomo.getDireccion()));
ps.setString(5, CryptoUtil.encrypt(autonomo.getTelefono()));
ps.setString(6, CryptoUtil.encrypt(autonomo.getEmail()));

// Al leer: descifrar campos sensibles
autonomo.setNif(CryptoUtil.decrypt(rs.getString("nif")));
autonomo.setDireccion(CryptoUtil.decrypt(rs.getString("direccion")));
autonomo.setTelefono(CryptoUtil.decrypt(rs.getString("telefono")));
autonomo.setEmail(CryptoUtil.decrypt(rs.getString("email")));
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
        
        long autonomoId = getAutonomoId(req); // del filtro de autenticación
        
        String pathInfo = req.getPathInfo();
        if (pathInfo == null || pathInfo.equals("/")) {
            List<Presupuesto> lista = service.listarPorAutonomo(autonomoId);
            resp.getWriter().write(gson.toJson(lista));
        } else {
            long id = Long.parseLong(pathInfo.substring(1));
            service.buscarPorId(autonomoId, id)
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
- **Multi-tenant**: TODAS las consultas de datos de negocio DEBEN filtrar por `autonomo_id`. Nunca hacer SELECT sin `WHERE autonomo_id = ?`

### Base de datos (PostgreSQL)

- Nombres de tablas y columnas en `snake_case`
- Claves primarias: `id BIGSERIAL PRIMARY KEY`
- Timestamps: `created_at TIMESTAMP DEFAULT NOW()`, `updated_at TIMESTAMP`
- Soft delete donde tenga sentido: `deleted_at TIMESTAMP NULL`
- Índices en columnas de búsqueda frecuente
- Foreign keys con `ON DELETE CASCADE` o `RESTRICT` según contexto
- Campos sensibles cifrados con AES-256-GCM almacenados como TEXT (Base64)
- Tabla principal: `autonomos` (NO `usuarios`)
- Foreign key: `autonomo_id` (NO `usuario_id`)

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

Telegram se usa SOLO como interfaz de entrada (audio/texto) y validación (borradores).
Los documentos generados (PDF/Excel) NUNCA se envían por Telegram.

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
El email es el ÚNICO canal de envío de documentos (PDF/Excel).

## Modelo de Datos

Consulta el esquema completo en `references/schema.sql`. Usa siempre ese esquema como fuente de verdad para nombres de tablas, columnas y tipos.

### Entidades principales

```sql
-- Autónomos que usan el bot
CREATE TABLE autonomos (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    nombre VARCHAR(200) NOT NULL,
    nif TEXT,                -- cifrado AES-256
    direccion TEXT,          -- cifrado
    telefono TEXT,           -- cifrado
    email TEXT,              -- cifrado
    plan VARCHAR(20) DEFAULT 'free',
    rgpd_aceptado_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);

-- Clientes de cada autónomo (pertenecen a un autónomo)
CREATE TABLE clientes (
    id BIGSERIAL PRIMARY KEY,
    autonomo_id BIGINT NOT NULL REFERENCES autonomos(id),
    nombre VARCHAR(200) NOT NULL,
    telefono TEXT,           -- cifrado
    email TEXT,              -- cifrado
    direccion TEXT,          -- cifrado
    nif TEXT,                -- cifrado, puede ser NULL hasta que se facture
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);
-- SIN constraint UNIQUE: puede haber clientes con el mismo nombre.
-- Al detectar coincidencia, preguntar al autónomo si es el mismo o crear uno nuevo.

CREATE INDEX idx_clientes_autonomo ON clientes(autonomo_id);
CREATE INDEX idx_clientes_nombre ON clientes(autonomo_id, nombre);

-- Presupuestos
CREATE TABLE presupuestos (
    id BIGSERIAL PRIMARY KEY,
    autonomo_id BIGINT NOT NULL REFERENCES autonomos(id),
    cliente_id BIGINT REFERENCES clientes(id),
    numero VARCHAR(50) UNIQUE NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    cliente_nombre VARCHAR(200),
    subtotal NUMERIC(10,2),
    iva_porcentaje NUMERIC(4,2) DEFAULT 21.00,
    iva_importe NUMERIC(10,2),
    total NUMERIC(10,2),
    audio_transcript TEXT,   -- cifrado
    notas TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE INDEX idx_presupuestos_autonomo ON presupuestos(autonomo_id);
CREATE INDEX idx_presupuestos_estado ON presupuestos(autonomo_id, estado);

-- Control de acceso
CREATE TABLE autonomos_autorizados (
    telegram_id BIGINT PRIMARY KEY,
    autorizado_por VARCHAR(200),
    created_at TIMESTAMP DEFAULT NOW()
);
```

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

### Detección de clientes existentes

Cuando Claude extrae el nombre del cliente del audio/texto, antes de crear un nuevo registro:

1. Buscar en BD: `SELECT * FROM clientes WHERE autonomo_id = ? AND LOWER(nombre) LIKE LOWER(?)`
2. Si hay coincidencias, mostrar al autónomo:
   ```
   He encontrado un cliente con ese nombre:
   👤 María García
   📞 ***456
   ¿Es la misma persona?
   ✅ Sí, usar estos datos | 👤 No, es otra persona
   ```
3. Si el autónomo dice "Sí" → reutilizar el cliente existente
4. Si dice "No" → crear un nuevo registro de cliente para este autónomo
5. Si no hay coincidencias → crear nuevo cliente automáticamente

## Aislamiento de Datos (Multi-tenant)

TuGestorAI es multi-tenant: varios autónomos usan el mismo bot y la misma BD. Cada autónomo solo debe ver y acceder a SUS datos.

### Reglas de aislamiento

1. **TODAS las tablas de negocio** (clientes, presupuestos, facturas, lineas_detalle) tienen `autonomo_id` como foreign key obligatoria
2. **TODAS las consultas SELECT** de datos de negocio DEBEN incluir `WHERE autonomo_id = ?`
3. **BaseDao.validateAutonomoId()** se llama al inicio de cada método del DAO. Si `autonomoId <= 0`, lanzar excepción
4. **Los comandos del bot** obtienen el `autonomo_id` a partir del `telegram_id` del mensaje. Solo devuelven datos de ese autónomo
5. **El panel web** (Vue) requiere autenticación. Solo muestra datos del autónomo autenticado
6. **Los services** reciben siempre `autonomoId` como primer parámetro y lo pasan al DAO
7. **Nunca** hacer consultas globales sin filtro de autónomo (excepto tareas administrativas)
8. **Los recordatorios y resúmenes** se generan individualmente por autónomo

### Prueba de aislamiento

Antes de considerar completa una funcionalidad, verificar:
- ¿La consulta SQL tiene `WHERE autonomo_id = ?`?
- ¿El método del DAO llama a `validateAutonomoId()`?
- ¿El service pasa el `autonomoId` correctamente?
- ¿Un autónomo puede acceder a datos de otro? (debe ser imposible)

## Estados de Presupuesto

### Diagrama de estados

```
BORRADOR → ENVIADO → ACEPTADO → FACTURADO → COBRADO
                   → RECHAZADO              → IMPAGADO
         → CANCELADO
```

### Enum EstadoPresupuesto

```java
public enum EstadoPresupuesto {
    BORRADOR,     // Recién generado, pendiente de confirmar en Telegram
    ENVIADO,      // Confirmado por el autónomo, documentos enviados por email
    ACEPTADO,     // El cliente ha aceptado el presupuesto
    RECHAZADO,    // El cliente ha rechazado el presupuesto
    FACTURADO,    // El autónomo ha emitido factura en TicketBAI
    COBRADO,      // El autónomo ha recibido el pago
    IMPAGADO,     // Facturado pero no cobrado tras un tiempo
    CANCELADO     // Cancelado por el autónomo antes de enviar
}
```

### Transiciones válidas

| De | A | Quién | Cómo |
|---|---|---|---|
| BORRADOR | ENVIADO | Sistema | Al confirmar el borrador en Telegram |
| BORRADOR | CANCELADO | Autónomo | Al cancelar el borrador en Telegram |
| ENVIADO | ACEPTADO | Autónomo | Comando `/aceptado P-2025-0001` |
| ENVIADO | RECHAZADO | Autónomo | Comando `/rechazado P-2025-0001` |
| ACEPTADO | FACTURADO | Autónomo | Comando `/facturado P-2025-0001` |
| FACTURADO | COBRADO | Autónomo | Comando `/cobrado P-2025-0001` |
| FACTURADO | IMPAGADO | Autónomo | Comando `/impagado P-2025-0001` |

No se permiten transiciones no listadas. El service debe validar que la transición es válida antes de cambiar el estado.

### Comandos de estado

- `/aceptado P-2025-0001` — Marca como aceptado por el cliente
- `/rechazado P-2025-0001` — Marca como rechazado por el cliente
- `/facturado P-2025-0001` — Marca como facturado (ya pasado a TicketBAI)
- `/cobrado P-2025-0001` — Marca como cobrado
- `/impagado P-2025-0001` — Marca como impagado
- `/pendientes` — Lista presupuestos en estado ACEPTADO (pendientes de facturar)
- `/impagados` — Lista presupuestos en estado FACTURADO o IMPAGADO (pendientes de cobrar)
- `/presupuestos` — Lista presupuestos del mes actual (todos los estados)
- `/presupuestos [mes]` — Lista de un mes concreto
- `/reenviar P-2025-0001` — Regenera PDF+Excel y los envía por email

### Comando /pendientes

```
📋 Presupuestos aceptados pendientes de facturar:

1. P-2025-0012 | María García | 520,30€ | hace 5 días
2. P-2025-0014 | Pedro López | 890,00€ | hace 3 días
3. P-2025-0019 | Ana Ruiz | 340,00€ | ayer

💰 Total pendiente: 1.750,30€

Usa /facturado P-2025-XXXX cuando lo pases a TicketBAI.
```

### Comando /impagados

```
💸 Facturas pendientes de cobro:

1. P-2025-0008 | María García | 520,30€ | facturado hace 25 días
2. P-2025-0010 | Pedro López | 890,00€ | facturado hace 18 días

💰 Total pendiente de cobro: 1.410,30€

Usa /cobrado P-2025-XXXX cuando recibas el pago.
```

## Notificaciones Automáticas

### Recordatorio semanal (Email)

Cada lunes a las 9:00 (configurable), enviar por email a cada autónomo que tenga presupuestos aceptados sin facturar o facturas pendientes de cobro:

```
📊 Recordatorio semanal TuGestorAI

Presupuestos aceptados sin facturar:
• P-2025-0012 — María García — 520,30€ (hace 12 días)
• P-2025-0014 — Pedro López — 890,00€ (hace 10 días)
• P-2025-0019 — Ana Ruiz — 340,00€ (hace 8 días)

💰 Total pendiente de facturar: 1.750,30€

Facturas pendientes de cobro:
• P-2025-0008 — María García — 520,30€ (hace 25 días)
• P-2025-0010 — Pedro López — 890,00€ (hace 18 días)

💸 Total pendiente de cobro: 1.410,30€
```

Si el autónomo no tiene pendientes de facturar ni de cobrar, no enviar nada (no molestar).

Implementación:
- `NotificacionService.enviarRecordatorioSemanal()` — recorre todos los autónomos con presupuestos en estado ACEPTADO o FACTURADO
- Programado con `ScheduledExecutorService` (no usar cron externo ni Spring Scheduler)
- Configurable: `notificacion.recordatorio.dia=MONDAY`, `notificacion.recordatorio.hora=09:00`

### Resumen fiscal mensual (Email)

El día 1 de cada mes a las 8:00, enviar por email a cada autónomo un resumen del mes anterior:

```
📊 Resumen mensual - Marzo 2025

Presupuestos generados: 12
Presupuestos enviados: 10
Presupuestos aceptados: 8
Presupuestos rechazados: 1
Pendientes de facturar: 2
Presupuestos cancelados: 1

💰 Total presupuestado: 6.450,00€
💰 Total aceptado: 4.890,00€
💰 Total facturado: 3.920,00€
💰 Pendiente de facturar: 970,00€
💸 Total cobrado: 2.800,00€
💸 Pendiente de cobro: 1.120,00€
📈 Tasa de conversión: 80%
```

Implementación:
- `NotificacionService.enviarResumenMensual()` — recorre todos los autónomos y genera resumen individual
- Programado con `ScheduledExecutorService`
- Configurable: `notificacion.resumen.dia=1`, `notificacion.resumen.hora=08:00`
- Se envía como email con formato HTML limpio (no PDF, es solo informativo)

### Configuración de notificaciones

```properties
# Recordatorio semanal (Email)
notificacion.recordatorio.enabled=true
notificacion.recordatorio.dia=MONDAY
notificacion.recordatorio.hora=09:00

# Resumen mensual (Email)
notificacion.resumen.enabled=true
notificacion.resumen.dia=1
notificacion.resumen.hora=08:00
```

## Flujos de Negocio Clave

### Flujo: Audio → Presupuesto (flujo principal)

1. **Recepción**: `VoiceHandler` recibe el audio OGG de Telegram
2. **Descarga**: Se descarga el fichero de audio vía Telegram API
3. **Transcripción**: `WhisperService.transcribe(audioFile)` → texto en español
4. **Borrado del audio**: El fichero de audio se borra del disco INMEDIATAMENTE después de transcribir
5. **Estructuración**: `ClaudeService.parsePresupuesto(transcripcion)` → JSON con cliente, conceptos, importes. SOLO se envía la transcripción, NUNCA datos fiscales del autónomo
6. **Detección de cliente**: Buscar si el cliente ya existe para este autónomo. Si hay coincidencia, preguntar si es el mismo
7. **Borrador**: Bot presenta borrador en Telegram SIN datos fiscales, solo lo que el autónomo dictó (nombre cliente, conceptos, importes, totales calculados). Estado: BORRADOR. Inline keyboard:
   - ✅ Confirmar
   - ✏️ Editar
   - ❌ Cancelar
8. **Si confirma**:
   a. `PresupuestoService.crear(datos)` → Guarda en PostgreSQL (datos sensibles cifrados). Estado: ENVIADO
   b. `PdfService.generarPresupuesto(presupuesto)` → byte[] en memoria (con datos fiscales completos desde BD)
   c. `ExcelService.generarPresupuesto(presupuesto)` → byte[] en memoria
   d. `EmailService.enviar(email, pdfBytes, excelBytes)` → envía PDF + Excel al email del autónomo
   e. Bot responde en Telegram: "✅ Presupuesto P-2025-0001 generado (520,30€)\n📧 Documentos enviados a tu email."
   f. Reemplazar el mensaje del borrador por: "✅ Presupuesto P-2025-0001 generado (520,30€)"
9. **Si edita**: Flujo de edición (ver más abajo)
10. **Si cancela**: Estado: CANCELADO. Reemplazar borrador por "❌ Presupuesto cancelado", limpiar sesión, volver a IDLE

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
3. A partir de aquí, flujo idéntico al de audio: detección cliente → borrador → confirmar/editar/cancelar → documentos por email

Coste por presupuesto por texto: ~€0.001 (solo Claude Haiku, sin Whisper).

En TextHandler: si el mensaje no empieza por `/` y el autónomo está en estado IDLE, tratar como texto de presupuesto. Si está en estado EDITANDO, tratar como corrección.

### Flujo: Presupuesto → Factura (TicketBAI)

TuGestorAI NO genera facturas TicketBAI. La facturación la hace el software certificado del autónomo.

Lo que TuGestorAI sí hace:
1. El autónomo marca el presupuesto como ACEPTADO (`/aceptado P-2025-0001`)
2. Cuando factura en su software de TicketBAI, marca como FACTURADO (`/facturado P-2025-0001`)
3. Cuando recibe el pago, marca como COBRADO (`/cobrado P-2025-0001`)
4. TuGestorAI lleva el control de qué está pendiente de facturar y de cobrar

El Excel y PDF del presupuesto sirven como referencia para que el autónomo copie los datos al software de facturación.

### Flujo: Consulta de presupuestos/facturas

El autónomo puede consultar sus documentos por dos vías:

**Vía Telegram (comandos del bot):**
- `/presupuestos` — Lista presupuestos del mes actual (número, cliente, importe, estado)
- `/presupuestos [mes]` — Lista de un mes concreto (ej: `/presupuestos marzo`)
- `/pendientes` — Lista presupuestos ACEPTADOS sin facturar
- `/impagados` — Lista presupuestos FACTURADOS sin cobrar
- `/reenviar P-2025-0001` — Regenera PDF+Excel y los envía por email
- `/aceptado P-2025-0001` — Marca como aceptado
- `/rechazado P-2025-0001` — Marca como rechazado
- `/facturado P-2025-0001` — Marca como facturado
- `/cobrado P-2025-0001` — Marca como cobrado
- `/impagado P-2025-0001` — Marca como impagado
- Respuesta como mensaje de texto con formato resumido. Si hay más de 10 resultados, paginar con inline keyboard (◀ Anterior / Siguiente ▶)

**Vía panel web (Vue):**
- Vista de tabla con columnas: número, fecha, cliente, importe total, estado
- Filtros: rango de fechas, estado, cliente
- Ordenación por fecha, importe o cliente
- Acción rápida: descargar PDF/Excel, reenviar por email, cambiar estado
- Resumen mensual: total presupuestado, aceptado, facturado, cobrado, tasa de conversión

**Consultas SQL base:**
- Presupuestos del mes: filtrar por `autonomo_id` y `created_at >= date_trunc('month', CURRENT_DATE)`
- Pendientes de facturar: `WHERE autonomo_id = ? AND estado = 'ACEPTADO'`
- Pendientes de cobro: `WHERE autonomo_id = ? AND estado IN ('FACTURADO', 'IMPAGADO')`
- Resumen mensual: `SUM(total)`, `COUNT(*)`, agrupado por estado
- Siempre ordenar por `created_at DESC`
- Los DAOs deben ofrecer métodos: `listarPorMes(autonomoId, year, month)`, `listarPendientes(autonomoId)`, `listarImpagados(autonomoId)`, `resumenMensual(autonomoId, year, month)`

### Límites Freemium

- Plan free: 5 presupuestos/mes (no facturas)
- Plan pro: Ilimitado + estadísticas + notificaciones
- El contador se reinicia el día 1 de cada mes
- `PresupuestoService` verifica límites antes de crear

## Control de Acceso

El bot es privado. Solo los autónomos autorizados pueden interactuar con él.

Tabla de control:
```sql
CREATE TABLE autonomos_autorizados (
    telegram_id BIGINT PRIMARY KEY,
    autorizado_por VARCHAR(200),
    created_at TIMESTAMP DEFAULT NOW()
);
```

Flujo de verificación:
1. CADA mensaje que llega al bot, antes de cualquier procesamiento, se verifica si el telegram_id está en `autonomos_autorizados`
2. Si NO está autorizado → responder: "⛔ Este bot es privado.\nTu ID de Telegram es: {chatId}\nEnvía este número al administrador para solicitar acceso."
3. Si está autorizado → procesar normalmente
4. La verificación debe estar en TuGestorBot.onUpdateReceived(), antes de delegar a handlers
5. Cachear los IDs autorizados en memoria (Set<Long>) y refrescar cada 5 minutos para no consultar la BD en cada mensaje

Administración de autónomos autorizados:
- Inserción manual en BD durante fase de pruebas
- Futuro: comando /autorizar (solo para admin) desde Telegram

### Consentimiento del usuario (RGPD)

En el flujo de /start, al registrarse por primera vez, informar al autónomo:
"ℹ️ Tus audios se procesan mediante servicios de IA externos para generar los presupuestos. No se almacenan los audios tras el procesamiento. Tus datos fiscales se guardan cifrados en nuestra base de datos."

El autónomo debe aceptar antes de poder usar el bot (inline keyboard: ✅ Acepto / ❌ No acepto).
Si no acepta, no se completa el registro y no puede usar el bot.
Guardar la fecha de aceptación en `autonomos.rgpd_aceptado_at`.

## Generación de Documentos (PDF y Excel)

Los documentos NO se almacenan en disco. Se generan al vuelo en memoria y se descartan tras el envío:
- PdfService genera PDF en ByteArrayOutputStream, devuelve byte[]
- ExcelService genera XLSX en ByteArrayOutputStream, devuelve byte[]
- Se envían SIEMPRE por email, NUNCA por Telegram
- Si el autónomo quiere reenviar (`/reenviar`), se regeneran desde los datos en BD y se envían por email
- No se almacena nada en disco

## Separación de canales (principio de seguridad)

Telegram y email cumplen roles distintos:

**Telegram** (interfaz de interacción):
- Entrada de datos: audio y texto del autónomo
- Borradores de validación: SIN datos fiscales, solo lo que dictó el autónomo
- Confirmación/edición/cancelación
- Comandos: consultas, listados, cambios de estado, ayuda
- Mensajes de estado: "Presupuesto generado", "Documentos enviados"

**Email** (canal de documentos):
- Envío de PDF y Excel con datos fiscales completos
- Único canal por donde viajan documentos con NIF, dirección fiscal, etc.
- Reenvío de documentos cuando se solicita
- Recordatorio semanal de pendientes de facturar y cobrar
- Resumen fiscal mensual

**Nunca por Telegram:**
- PDF o Excel
- NIF del autónomo
- Dirección fiscal del autónomo
- Datos bancarios
- Cualquier documento con datos fiscales completos

## Protección en Telegram

Aunque Telegram solo muestra datos mínimos, se aplican estas medidas:

### 1. Borrador sin datos fiscales
En el mensaje de borrador solo aparecen datos que el autónomo ya dictó en el mismo chat:
nombre del cliente, conceptos, importes, totales calculados.
NUNCA aparecen: NIF del autónomo, dirección fiscal, nombre comercial, datos bancarios.

### 2. Reemplazo de borradores tras procesar
Una vez el autónomo confirma o cancela, editar el mensaje del borrador:
- Si confirmó: "✅ Presupuesto P-2025-0001 generado (520,30€)"
- Si canceló: "❌ Presupuesto cancelado"
Los datos detallados desaparecen del historial del chat.

### 3. Limpieza de sesión
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
| autonomos | nif, direccion, telefono, email |
| clientes | nif, direccion, telefono, email |
| presupuestos | audio_transcript |

Estos campos se almacenan como TEXT en BD (valor cifrado en Base64).
Los DAOs cifran al guardar y descifran al leer, de forma transparente para el resto de la app.

No se puede hacer WHERE sobre campos cifrados. Buscar por campos no cifrados (id, autonomo_id, nombre) y descifrar después.

## Protección de Datos y APIs Externas

### Minimización de datos enviados a APIs
- A Whisper (OpenAI): solo el audio, inevitable
- A Claude (Haiku): solo la transcripción del audio (o texto del autónomo). NUNCA enviar datos fiscales del autónomo (NIF, dirección, etc.). Esos datos se añaden después desde la BD al generar el documento
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
| Peticiones por hora/autónomo (audio+texto) | 10 | limit.requests.per.hour |
| Peticiones por día/autónomo (audio+texto) | 30 | limit.requests.per.day |
| Reintentos Claude API por presupuesto | 1 | limit.claude.max.retries |
| Ediciones máximas por presupuesto | 5 | limit.edits.per.presupuesto |
| Emails por día/autónomo | 20 | limit.email.per.day |
| Coste diario global máximo | €3 | limit.cost.daily.max |
| Presupuestos/mes plan free | 5 | plan.free.limite.presupuestos |

Reglas:
- Los rate limits de audio y texto son COMPARTIDOS (mismo contador)
- Las ediciones de un presupuesto cuentan como peticiones a efectos de rate limiting
- El VoiceHandler y TextHandler validan los límites ANTES de llamar a APIs externas
- Si Claude devuelve JSON inválido, se reintenta UNA sola vez. Si falla otra vez, se informa al autónomo
- El coste diario global se estima a €0.007 por audio y €0.001 por texto
- Rate limiting implementado con mapa en memoria con TTL
- Mensajes de rechazo amigables en español
- Contador de emails por autónomo y día en memoria

## Seguridad

- API keys (Whisper, Claude, Telegram) y contraseñas de BD SOLO en variables de entorno, nunca en config.properties ni en código
- Clave de cifrado AES SOLO en variable de entorno: `CRYPTO_SECRET_KEY`
- HTTPS obligatorio en producción
- CSP headers sin inline JS/CSS (usar CspFilter)
- Prepared statements siempre (prevención SQL injection)
- Escapar output HTML (prevención XSS)
- Validar y sanitizar toda entrada del autónomo
- Tokens CSRF en formularios del panel web
- Datos sensibles cifrados en BD con AES-256-GCM
- Audios borrados inmediatamente tras transcripción
- Documentos (PDF/Excel) solo por email, nunca por Telegram
- Borradores en Telegram sin datos fiscales, reemplazados tras procesar
- Sesiones limpiadas tras uso
- Logs sin datos personales
- Aislamiento multi-tenant: todas las consultas filtran por autonomo_id

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
4. **Los textos del autónomo** (mensajes del bot, errores, labels) deben estar en español
5. **Genera tests** cuando se creen nuevos servicios o DAOs
6. **Documenta** con JavaDoc las clases públicas
7. **Si necesitas detalles de integración**, lee el fichero de referencia correspondiente en `references/`
8. **Respeta el modelo freemium**: toda funcionalidad nueva debe considerar los límites del plan
9. **Prioriza el flujo principal** (audio → presupuesto → PDF + Excel por email) sobre funcionalidades secundarias
10. **Cifra datos sensibles** en DAOs al guardar y descifra al leer
11. **No loguees datos personales** (NIF, emails, teléfonos, direcciones, transcripciones)
12. **Documentos solo por email**, nunca enviar PDF/Excel por Telegram
13. **Borradores en Telegram sin datos fiscales**, reemplazar tras procesar
14. **SIEMPRE filtrar por autonomo_id** en consultas de datos de negocio. Nunca hacer SELECT sin WHERE autonomo_id = ?
15. **Tabla principal es `autonomos`** (no `usuarios`), foreign key es `autonomo_id` (no `usuario_id`)
16. **Comunicarse siempre en castellano**