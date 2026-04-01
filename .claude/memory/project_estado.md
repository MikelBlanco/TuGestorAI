---
name: Estado del proyecto TuGestorAI
description: Qué clases están implementadas, qué falta y deuda técnica pendiente (actualizado 2026-04-01)
type: project
---

## Implementado y funcional

### Backend (Java)
| Capa | Clases |
|---|---|
| Bot | TuGestorBot, VoiceHandler, TextHandler, CallbackHandler, BotInitializer |
| Sesiones | UserSession, SessionManager, SessionState |
| DAOs | AutonomoDao, ClienteDao, PresupuestoDao, FacturaDao, AutonomoAutorizadoDao, BaseDao |
| Servicios | ClaudeService, WhisperService, PdfService, ExcelService, EmailService, FacturaService, NotificacionService, NumeracionService, PresupuestoService |
| Servlets | PresupuestoApiServlet, FacturaApiServlet, LoginApiServlet, HealthServlet |
| Filtros/Utils | AuthFilter, CspFilter, EncodingFilter, CryptoUtil, DbUtil, RateLimiter, TokenStore, ConfigUtil, SecurityUtil |
| Excepciones | BotException, DaoException, ServiceException |

### Frontend (Vue 3)
- Login, Presupuestos, PresupuestoDetalle, Facturas, FacturaDetalle, Perfil

---

## Cambios recientes (2026-04-01)

### Creado: PresupuestoService
Centraliza toda la lógica de negocio de presupuestos. Métodos:
- `verificarLimiteFreemium(autonomoId, plan)` — enforcement plan free, lanza ServiceException
- `contarPresupuestosMes(autonomoId)` — para /plan
- `crear(autonomoId, clienteId, datos, transcripcion)` — numeración automática
- `findById(autonomoId, id)` — con filtro multi-tenant garantizado
- `findByNumero(autonomoId, numero)`
- `listar / listarPorMes / listarPendientes / resumenMensual`
- `cambiarEstadoPorNumero / cambiarEstadoPorId` — validan transiciones (TRANSICIONES_VALIDAS map)

### Bug de seguridad corregido: PresupuestoApiServlet
- GET /{id}: antes `presupuestoDao.findById(id)` sin filtro + check manual. Ahora `presupuestoService.findById(autonomoId, id)`.
- PUT /{id}/estado: antes sin validación de transición. Ahora `cambiarEstadoPorId` valida pertenencia y transición, devuelve 409 si inválida.

### Migrados al servicio (ya no acceden a PresupuestoDao directamente)
- TextHandler: freemium check, /plan, listarPorMes, listarPendientes, cambiarEstado, reenviar. Eliminado método `esTransicionValida` (duplicado).
- VoiceHandler: freemium check.
- CallbackHandler: creación de presupuesto. Eliminado NumeracionService (absorbido por PresupuestoService).

---

## Deuda técnica pendiente (prioridad)

1. **Tests unitarios** — No existe ningún test. Mínimo: PresupuestoService, PresupuestoDao, CryptoUtil.
2. **ClienteApiServlet** — No existe. El frontend no tiene vista de clientes ni el servlet correspondiente.

**Why:** Se detectaron durante revisión del estado del proyecto en 2026-03-31.
**How to apply:** Abordar en este orden en próximas sesiones.
