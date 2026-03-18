# TuGestorAI

Bot de Telegram para autónomos españoles del sector servicios. Permite generar presupuestos profesionales en PDF y Excel mediante mensajes de voz. Telegram solo como interfaz de entrada; documentos siempre por email.

## Stack

- **Backend**: Java 21 + Servlets + Tomcat 10 (sin Spring)
- **Frontend**: Vue 3
- **Base de datos**: PostgreSQL (datos sensibles cifrados con AES-256-GCM)
- **Bot**: TelegramBots (rubenlagus)
- **Transcripción**: Whisper API (OpenAI)
- **IA**: Claude API (Haiku) para estructurar datos
- **PDF**: OpenPDF
- **Excel**: Apache POI (poi-ooxml)
- **Email**: Angus Mail (Jakarta Mail)
- **Build**: Maven

## Comandos de Desarrollo

### Maven (Backend Java)
```bash
mvn compile                # Compilar código fuente
mvn clean compile          # Limpiar y compilar
mvn package                # Crear WAR para despliegue
mvn clean package          # Build completo limpio
mvn test                   # Ejecutar tests unitarios
mvn clean install          # Build completo con instalación local
```

### Frontend (Vue 3)
```bash
cd frontend/
npm install                # Instalar dependencias
npm run dev                # Servidor de desarrollo
npm run build              # Build para producción
```

### Base de datos
```bash
psql -U tugestorai -d tugestorai -f .claude/skills/tugestorai/references/schema.sql
```

## Convenciones

- Java sin Spring: servlets, JDBC directo, clases propias
- SQL siempre con PreparedStatement (nunca concatenación)
- CSP sin inline JS/CSS
- Textos de usuario en español
- UTF-8 en todo
- Logging con SLF4J + Logback. NUNCA loguear datos personales (NIF, emails, teléfonos, direcciones, transcripciones)
- Variables de entorno para configuración sensible (API keys, DB, CRYPTO_SECRET_KEY)

## Reglas críticas

- **Multi-tenant**: TODAS las consultas de datos de negocio DEBEN filtrar por `autonomo_id`. Nunca SELECT sin WHERE autonomo_id = ?
- **Nomenclatura**: Tabla principal es `autonomos` (NO `usuarios`), FK es `autonomo_id` (NO `usuario_id`)
- **Cifrado**: Campos sensibles (NIF, dirección, teléfono, email) se cifran con CryptoUtil en los DAOs al guardar y descifran al leer
- **Separación de canales**: Telegram solo para entrada/validación. PDF/Excel NUNCA por Telegram, siempre por email
- **Borradores**: En Telegram sin datos fiscales del autónomo. Reemplazar por resumen breve tras confirmar/cancelar
- **Audios**: Borrar del disco inmediatamente después de transcribir
- **APIs externas**: A Claude solo enviar transcripción, NUNCA datos fiscales del autónomo

## Estados de presupuesto

```
BORRADOR → ENVIADO → ACEPTADO → FACTURADO → COBRADO
                   → RECHAZADO              → IMPAGADO
         → CANCELADO
```

## Estructura

Consulta `.claude/skills/tugestorai/SKILL.md` para arquitectura detallada, patrones de código, modelo de datos, flujos de negocio y guías de integración.

Consulta `.claude/skills/tugestorai/references/schema.sql` para el esquema de base de datos.

## Idioma

Comunicarse siempre en castellano (español de España).