# PresupuestoAI — Memoria del Proyecto

## Índice de memorias

- [`project_tareas_pendientes.md`](project_tareas_pendientes.md) — lista priorizada de tareas (seguridad + UX). Próxima: **Tarea 2** (borrado audio tras transcripción)
- [`despliegue-vps.md`](despliegue-vps.md) — guía completa de despliegue en VPS (Hetzner CAX11 recomendado)

## Entorno de compilación

- Compilar siempre con: `JAVA_HOME="$HOME/.jdks/corretto-24.0.2" mvn compile --settings .mvn/settings-personal.xml`
- JDK 24 en `~/.jdks/corretto-24.0.2` (compatible con target Java 21)
- El `settings-personal.xml` es necesario para resolver dependencias (Nexus corporativo con cert inválido)

## Estado actual (2026-03-16)

- Java 21 (pom.xml). El SKILL.md menciona 17 en algunos sitios — ignorar, usar 21
- Último commit pusheado: `f9a8fc5` — CryptoUtil AES-256-GCM
- Build: WAR desplegable en Tomcat 10

## Estructura real del proyecto

### Backend Java (`org.gestorai`)

```
bot/
  BotInitializer.java
  TuGestorBot.java          ← verifica autorización + caché 5 min (UsuarioAutorizadoDao)
  handlers/  VoiceHandler, TextHandler, CallbackHandler
  session/   SessionManager, SessionState, UserSession

dao/         BaseDao, FacturaDao, PresupuestoDao, UsuarioDao, UsuarioAutorizadoDao
exception/   BotException, DaoException, ServiceException
filter/      CspFilter, EncodingFilter
model/       DatosPresupuesto, Factura, LineaDetalle, Presupuesto, Usuario
service/     ClaudeService, EmailService, ExcelService, FacturaService,
             NumeracionService, PdfService, WhisperService
servlet/     FacturaApiServlet, PresupuestoApiServlet, HealthServlet
util/        ConfigUtil, CryptoUtil, DbUtil, RateLimiter
```

### Frontend Vue 3 (`frontend/src`)

- Composition API
- Pinia store: `stores/usuario.js`
- Vistas implementadas: Facturas, FacturaDetalle, Presupuestos, PresupuestoDetalle, Login, Perfil
- NO implementadas aún: Dashboard, Clientes, Configuracion

## Flujo principal (estado actual)

Audio → VoiceHandler → Whisper → Claude → detección cliente → borrador en Telegram
→ CallbackHandler (confirmar) → crea/vincula cliente → BD + PDF + Excel → envío por email
→ reset sesión

Estados de sesión: IDLE, ESPERANDO_CONFIRMACION, EDITANDO, ESPERANDO_OPCION_ENVIO,
PENDIENTE_CONSENTIMIENTO, REGISTRO_NOMBRE, REGISTRO_NIF, REGISTRO_DIRECCION,
REGISTRO_EMAIL, CONFIRMANDO_CLIENTE

## Flujo de autenticación en panel web

1. Autónomo usa `/panel` en el bot → `TextHandler.generarTokenPanel()` → `TokenStore.generarToken(autonomoId)` → token UUID enviado por Telegram (válido 10 min, un solo uso)
2. Usuario abre panel web, introduce el token en `Login.vue`
3. Frontend: `POST /api/auth/login` con `{"token": "..."}` → `LoginApiServlet`
4. `LoginApiServlet` llama a `TokenStore.validarToken(token)` → devuelve `Optional<Long> autonomoId`
5. Si válido: invalida sesión anterior (anti-session-fixation), crea nueva sesión HTTP con `autonomo_id` en atributo de sesión
6. Responde con datos públicos del autónomo: `{"id":..., "nombre":"...", "plan":"..."}`
7. Resto de peticiones al panel: `AuthFilter` lee `session.getAttribute("autonomo_id")`, propaga como `request.setAttribute("autonomo_id", ...)` para que los servlets lo usen
8. `POST /api/auth/logout` → `session.invalidate()`

## Seguridad implementada

- Cifrado AES-256-GCM: `CryptoUtil`. Campos cifrados en BD:
  - `autonomos`: nif, direccion, telefono, email
  - `clientes`: nif, direccion, telefono, email
  - `presupuestos`: audio_transcript
  - Clave desde `CRYPTO_SECRET_KEY` / `crypto.secret.key` (Base64, 32 bytes)
- Autorización por tabla `autonomos_autorizados`, caché en memoria con TTL 5 min
- Rate limiting por usuario: audio/texto/email/coste global (`RateLimiter`)
- config.properties excluido de git — usar config.properties.example como plantilla
- Autenticación panel web mediante sesiones HTTP (sin JWT):
  - `TokenStore`: singleton, tokens UUID de un solo uso con TTL 10 min
  - `AuthFilter`: protege `/api/*`, excluye `/api/auth/*`
  - `LoginApiServlet`: `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`
  - `autonomoPublico()` en LoginApiServlet: solo devuelve id/nombre/plan al frontend (nunca NIF/email/dir)

## Patrones clave

- JDBC directo con HikariCP (DbUtil), PreparedStatement siempre
- ConfigUtil: config.properties + sobreescritura por env vars (UPPER_SNAKE_CASE)
- NumeracionService: numeración secuencial de presupuestos/facturas
- PdfService y ExcelService generan documentos en memoria (byte[]), nunca en disco
- EmailService.enviarConAdjuntos(varargs Adjunto) — soporta múltiples adjuntos

## Convenciones

- Textos al usuario en español
- Comentarios en código en español
- Logging con SLF4J — NUNCA loguear datos personales (NIF, email, teléfono, transcripciones)
- SQL: snake_case, BIGSERIAL PKs, timestamps con NOW()
- Freemium: 5 presupuestos/mes en plan free, ilimitado en pro
