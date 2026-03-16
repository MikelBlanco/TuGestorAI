---
name: Tareas pendientes de seguridad y UX
description: Lista priorizada de funcionalidades identificadas en el análisis del SKILL.md (2026-03-16) pendientes de implementar
type: project
---

Resultado del análisis SKILL.md vs código real (2026-03-16). El cifrado AES-256-GCM (tarea 1) ya está implementado.

**Why:** El SKILL.md fue actualizado con requisitos de seguridad (RGPD, cifrado, borrado de datos) y UX (flujo de edición, opciones de envío) que no estaban en el código. Se acuerda implementar en orden de prioridad.

**How to apply:** Al iniciar cada sesión, consultar esta lista para continuar por donde se quedó. Marcar como completada y actualizar el fichero cuando se implemente cada tarea.

---

## Tarea 1 — CryptoUtil: cifrado AES-256-GCM ✅ COMPLETADA (2026-03-16)

Commit: `f9a8fc5`
- `CryptoUtil`: AES/GCM/NoPadding, clave 256-bit desde `CRYPTO_SECRET_KEY` / `crypto.secret.key`
- `UsuarioDao`: cifra `nif`, `direccion`, `telefono`, `email` al guardar; descifra al leer
- `PresupuestoDao`: cifra `audio_transcript` al guardar; descifra al leer
- `config.properties.example`: añadida clave `crypto.secret.key`

⚠️ **Aviso de migración:** Los datos ya existentes en BD están en texto plano. Al activar el cifrado `decrypt()` fallará sobre ellos. En producción hacer migración antes de desplegar; en dev vaciar la tabla `usuarios` y volver a registrarse.

---

## Tarea 2 — Borrado del audio tras transcripción 🔴 PENDIENTE (ALTA)

**Qué:** El fichero `.ogg` descargado de Telegram se guarda en un directorio temporal y nunca se borra explícitamente tras llamar a `whisperService.transcribe()`.

**Dónde:** `VoiceHandler.java` — tras la llamada a `whisperService.transcribe(audioFile)`, borrar `audioFile` y `audioDescargado` con `Files.deleteIfExists()`.

**Por qué urgente:** El audio contiene la voz del autónomo (dato personal). El SKILL exige borrarlo "INMEDIATAMENTE después de transcribir".

---

## Tarea 3 — Borrado automático de documentos en Telegram (5 min) 🔴 PENDIENTE (ALTA)

**Qué:** Al enviar PDF/Excel por Telegram, programar el borrado de esos mensajes transcurridos 5 minutos con `deleteMessage()`. Avisar al usuario: "⚠️ Por seguridad, los documentos se eliminarán del chat en 5 minutos."

**Dónde:** `CallbackHandler.java` — tras enviar cada `SendDocument`, guardar el `messageId` devuelto y programar un borrado diferido (ScheduledExecutorService o similar).

**Config key:** `limit.telegram.doc.ttl` (segundos, defecto 300).

---

## Tarea 4 — Reemplazo del borrador tras confirmar/cancelar 🟡 PENDIENTE (MEDIA)

**Qué:** Tras confirmar o cancelar, editar el mensaje original del borrador con `EditMessageText` para reemplazarlo por un resumen breve:
- Si confirmó: "✅ Presupuesto P-2026-0001 generado (484,00€)"
- Si canceló: "❌ Presupuesto cancelado"

**Dónde:** `CallbackHandler.java` — `confirmarPresupuesto()` y `cancelarPresupuesto()`. Actualmente solo se llama a `quitarBotones()` que elimina el teclado pero deja el texto completo del borrador.

**Por qué:** Los datos del cliente quedan visibles en el historial del chat.

---

## Tarea 5 — Opciones de envío: Telegram / Email / Ambos 🟡 PENDIENTE (MEDIA)

**Qué:** Al confirmar un presupuesto, en lugar de enviar siempre por Telegram y luego preguntar por email, preguntar primero:
"¿Cómo quieres recibir los documentos?"
- 📱 Telegram
- 📧 Email (recomendado)
- 📱📧 Ambos

**Dónde:** `CallbackHandler.java` — rediseñar el flujo de `confirmarPresupuesto()` y añadir callbacks `doc_telegram`, `doc_email`, `doc_ambos`. Generar los documentos antes de enviar según la opción elegida. Añadir estado de sesión `ESPERANDO_OPCION_ENVIO`.

---

## Tarea 6 — Consentimiento RGPD en /start 🟡 PENDIENTE (MEDIA)

**Qué:** En el flujo de `/start`, antes de iniciar el registro, mostrar:
"ℹ️ Tus audios se procesan mediante servicios de IA externos para generar los presupuestos. No se almacenan los audios tras el procesamiento. Tus datos fiscales se guardan cifrados en nuestra base de datos."

El usuario debe aceptar con inline keyboard (✅ Acepto / ❌ No acepto) antes de continuar. Si no acepta, no se completa el registro.

**Dónde:** `TextHandler.java` — `manejarStart()`. Añadir estado de sesión `PENDIENTE_CONSENTIMIENTO`. Guardar fecha de aceptación en la tabla `usuarios` (añadir columna `consentimiento_at TIMESTAMP`).

---

## Tarea 7 — Flujo de edición completo 🟢 PENDIENTE (BAJA)

**Qué:** El estado `EDITANDO` existe en `SessionState` y `CallbackHandler.iniciarEdicion()` solo muestra "próximamente". Implementar el flujo completo:

1. Al pulsar ✏️ Editar → cambiar estado a `EDITANDO`, pedir corrección por texto o audio
2. Si audio → Whisper transcribe → texto de corrección
3. Si texto → usar directamente
4. Enviar a Claude: borrador actual (JSON) + corrección → nuevo JSON actualizado
5. Presentar nuevo borrador con los mismos botones
6. Límite: 5 ediciones por presupuesto (`limit.edits.per.presupuesto`)

**Dónde:** `CallbackHandler.java` (iniciar edición), `TextHandler.java` (manejar estado EDITANDO), `VoiceHandler.java` (manejar estado EDITANDO), `ClaudeService.java` (nuevo método `editarPresupuesto(borradorJson, correccion)`).
