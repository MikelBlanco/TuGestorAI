# TuGestorAI

Bot de Telegram para autónomos españoles del sector servicios. Permite generar presupuestos y facturas profesionales en PDF mediante mensajes de voz.

## Stack

- **Backend**: Java 21 + Servlets + Tomcat 10 (sin Spring)
- **Frontend**: Vue 3
- **Base de datos**: PostgreSQL
- **Bot**: TelegramBots (rubenlagus)
- **Transcripción**: Whisper API (OpenAI)
- **IA**: Claude API (Haiku) para estructurar datos
- **PDF**: OpenPDF
- **Build**: Maven

## Comandos de Desarrollo

### Maven (Backend Java)
```bash
mvn compile                 # Compilar código fuente
mvn clean compile          # Limpiar y compilar
mvn package                # Crear WAR para despliegue
mvn clean package         # Build completo limpio
mvn test                  # Ejecutar tests unitarios
mvn clean install        # Build completo con instalación local
```

### Frontend (Vue 3)
```bash
cd frontend/
npm install               # Instalar dependencias
npm run dev              # Servidor de desarrollo
npm run build            # Build para producción
```

## Convenciones

- Java sin Spring: servlets, JDBC directo, clases propias
- SQL siempre con PreparedStatement (nunca concatenación)
- CSP sin inline JS/CSS
- Textos de usuario en español
- UTF-8 en todo
- Logging con SLF4J + Logback
- Variables de entorno para configuración sensible (API keys, DB)

## Estructura

Consulta `.claude/skills/tugestorai/SKILL.md` para arquitectura detallada, patrones de código, modelo de datos y guías de integración.

## Idioma

Comunicarse siempre en castellano (español de España).