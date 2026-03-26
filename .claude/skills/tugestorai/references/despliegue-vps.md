# Guía de despliegue — PresupuestoAI en VPS

Última actualización: 2026-03-18

## VPS recomendado para pruebas

| Proveedor | Plan | Precio | Specs | Registro |
|-----------|------|--------|-------|----------|
| **Hetzner** (recomendado) | CAX11 | €3,79/mes | 2 vCPU ARM, 4 GB RAM, 40 GB SSD, 20 TB tráfico | Sencillo, sin sorpresas |
| Oracle Cloud | Always Free | Gratis | 2-4 cores ARM, 12-24 GB RAM | Requiere tarjeta, registro complicado |

**Alta en Hetzner:** https://www.hetzner.com/cloud
- Crear proyecto → Add Server → Location: Falkenstein (EU) → Image: Ubuntu 22.04 → Type: CAX11
- Añadir clave SSH pública antes de crear (más seguro que contraseña)
- Anotar la IP pública del servidor

---

## 1. Preparación del servidor (una sola vez)

Conectar por SSH:
```bash
ssh root@<IP_DEL_VPS>
```

### 1.1 Actualizar sistema y crear usuario no-root

```bash
apt update && apt upgrade -y

# Crear usuario de despliegue
useradd -m -s /bin/bash gestorai
usermod -aG sudo gestorai

# Copiar clave SSH al nuevo usuario
mkdir -p /home/gestorai/.ssh
cp /root/.ssh/authorized_keys /home/gestorai/.ssh/
chown -R gestorai:gestorai /home/gestorai/.ssh
chmod 700 /home/gestorai/.ssh
chmod 600 /home/gestorai/.ssh/authorized_keys
```

### 1.2 Instalar Java 21

```bash
apt install -y wget apt-transport-https

# OpenJDK 21 (Ubuntu 22.04 universe)
add-apt-repository -y universe
apt install -y openjdk-21-jdk-headless

# Verificar
java -version
# Esperado: openjdk version "21.x.x"
```

### 1.3 Instalar Tomcat 10

```bash
# Descargar Tomcat 10.1.x (verificar última versión en tomcat.apache.org)
cd /opt
wget https://downloads.apache.org/tomcat/tomcat-10/v10.1.39/bin/apache-tomcat-10.1.39.tar.gz
tar xzf apache-tomcat-10.1.39.tar.gz
mv apache-tomcat-10.1.39 tomcat
rm apache-tomcat-10.1.39.tar.gz

# Asignar al usuario gestorai
chown -R gestorai:gestorai /opt/tomcat

# Eliminar aplicaciones de ejemplo (seguridad)
rm -rf /opt/tomcat/webapps/ROOT
rm -rf /opt/tomcat/webapps/examples
rm -rf /opt/tomcat/webapps/docs
rm -rf /opt/tomcat/webapps/host-manager
rm -rf /opt/tomcat/webapps/manager
```

Crear servicio systemd para Tomcat (`/etc/systemd/system/tomcat.service`):

```ini
[Unit]
Description=Apache Tomcat 10
After=network.target postgresql.service

[Service]
Type=forking
User=gestorai
Group=gestorai
WorkingDirectory=/opt/tomcat

Environment="JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64"
Environment="CATALINA_HOME=/opt/tomcat"
Environment="CATALINA_BASE=/opt/tomcat"
Environment="CATALINA_PID=/opt/tomcat/temp/tomcat.pid"
Environment="CATALINA_OPTS=-Xms256m -Xmx512m -server"

# Variables de entorno de la aplicación (rellenar con valores reales)
Environment="DB_URL=jdbc:postgresql://localhost:5432/tugestorai"
Environment="DB_USER=gestorai_db"
Environment="DB_PASSWORD=CAMBIAR_POR_CONTRASEÑA_REAL"
Environment="TELEGRAM_BOT_TOKEN=CAMBIAR_POR_TOKEN_REAL"
Environment="TELEGRAM_BOT_USERNAME=CAMBIAR_POR_USERNAME_REAL"
Environment="OPENAI_API_KEY=CAMBIAR_POR_KEY_REAL"
Environment="ANTHROPIC_API_KEY=CAMBIAR_POR_KEY_REAL"
Environment="CRYPTO_SECRET_KEY=CAMBIAR_POR_CLAVE_BASE64_32_BYTES"
Environment="EMAIL_SMTP_HOST=smtp.gmail.com"
Environment="EMAIL_SMTP_PORT=587"
Environment="EMAIL_SMTP_USER=CAMBIAR_POR_EMAIL"
Environment="EMAIL_SMTP_PASSWORD=CAMBIAR_POR_APP_PASSWORD"
Environment="EMAIL_FROM=CAMBIAR_POR_EMAIL"

ExecStart=/opt/tomcat/bin/startup.sh
ExecStop=/opt/tomcat/bin/shutdown.sh

Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable tomcat
```

### 1.4 Instalar PostgreSQL

```bash
apt install -y postgresql postgresql-contrib

# Verificar que arranca
systemctl status postgresql
systemctl enable postgresql

# Crear base de datos y usuario
sudo -u postgres psql <<EOF
CREATE USER gestorai_db WITH PASSWORD 'CAMBIAR_POR_CONTRASEÑA_REAL';
CREATE DATABASE tugestorai OWNER gestorai_db;
GRANT ALL PRIVILEGES ON DATABASE tugestorai TO gestorai_db;
EOF
```

Aplicar esquema:

```bash
# Copiar schema.sql al servidor y aplicar
sudo -u postgres psql -d tugestorai -f /ruta/a/schema.sql
# O directamente:
sudo -u gestorai_db psql -d tugestorai -f schema.sql
```

### 1.5 Hardening de SSH

Editar `/etc/ssh/sshd_config` para deshabilitar login por contraseña (solo clave):

```
PermitRootLogin no
PasswordAuthentication no
PubkeyAuthentication yes
```

```bash
systemctl restart sshd
```

⚠️ Antes de hacer esto, asegúrate de que puedes acceder con tu clave SSH desde otra terminal abierta.

### 1.6 Hardening de PostgreSQL (solo localhost)

Por defecto PostgreSQL solo escucha en localhost, pero conviene verificarlo y reforzarlo. Editar `/etc/postgresql/*/main/postgresql.conf`:

```
listen_addresses = 'localhost'   # Solo acepta conexiones locales
```

Editar `/etc/postgresql/*/main/pg_hba.conf` — verificar que la línea de acceso a tugestorai es:

```
local   tugestorai   gestorai_db                            scram-sha-256
host    tugestorai   gestorai_db   127.0.0.1/32             scram-sha-256
```

```bash
systemctl restart postgresql
```

El puerto 5432 **nunca debe aparecer abierto** en el firewall hacia el exterior.

### 1.7 Firewall

```bash
apt install -y ufw

# Reglas por defecto: denegar entrada, permitir salida
ufw default deny incoming
ufw default allow outgoing

ufw allow OpenSSH          # Puerto 22 SSH
ufw allow 8080/tcp         # Tomcat (solo para pruebas; en producción usar Nginx + 443)
# ufw allow 80/tcp         # HTTP (cuando se añada Nginx)
# ufw allow 443/tcp        # HTTPS (cuando se añada SSL)
# Puerto 5432 PostgreSQL: NO abrir nunca al exterior

ufw enable
ufw status verbose         # Verificar reglas activas
```

---

## 2. Cifrado de la base de datos

La aplicación ya implementa **cifrado a nivel de aplicación (AES-256-GCM)** sobre los campos sensibles: `nif`, `direccion`, `telefono`, `email`, `audio_transcript`. Esto significa que aunque alguien acceda directamente a la BD, esos datos son ilegibles sin la `CRYPTO_SECRET_KEY`.

Sin embargo, hay tres niveles de cifrado posibles:

| Nivel | Qué protege | Estado |
|-------|------------|--------|
| **Aplicación AES-256-GCM** | Campos sensibles en BD | ✅ Ya implementado |
| **Cifrado de directorio PostgreSQL (LUKS)** | Ficheros físicos de BD en disco | 🔶 Recomendado para producción real |
| **pgcrypto (columnas en BD)** | Columnas individuales en PostgreSQL | ❌ Redundante — ya lo hace la app |

### Para el piloto (dos amigos): el nivel de aplicación es suficiente

Los datos sensibles ya van cifrados en BD. Nadie que acceda a la BD directamente puede leer NIF, dirección ni email sin la clave AES.

### Para producción real: añadir cifrado de directorio con LUKS

Cifra el directorio donde PostgreSQL almacena sus ficheros (`/var/lib/postgresql`). Protege frente a acceso físico al disco del servidor o snapshot del VPS.

```bash
# Instalar herramientas
apt install -y cryptsetup

# Crear un fichero contenedor cifrado de 10 GB para los datos de PG
dd if=/dev/urandom of=/opt/pgdata.img bs=1M count=10240
cryptsetup luksFormat /opt/pgdata.img          # Te pedirá una passphrase
cryptsetup luksOpen /opt/pgdata.img pgdata

# Formatear y montar
mkfs.ext4 /dev/mapper/pgdata
mount /dev/mapper/pgdata /var/lib/postgresql

# Mover datos de PG al contenedor cifrado y reiniciar
# (hacerlo antes de inicializar PostgreSQL, o haciendo dump/restore)
```

⚠️ **Limitación en VPS:** LUKS con passphrase requiere introducirla manualmente en cada reinicio del servidor. Para automatizar el arranque sin intervención manual hay que usar un keyfile en disco (reduce algo la protección) o Dropbear SSH en initramfs (complejo). Para el piloto no compensa.

---

## 3. Cumplimiento RGPD / GDPR con Hetzner

### Hetzner como proveedor

Hetzner es empresa alemana, **100% dentro de la UE**, con pleno cumplimiento del RGPD:

- Datos almacenados y procesados exclusivamente en la UE (Alemania/Finlandia)
- Sin transferencias a terceros países
- Auditado anualmente por TÜV Rheinland
- Permite firmar un **DPA (Data Processing Agreement / Acuerdo de Tratamiento de Datos)** según Art. 28 RGPD — **obligatorio firmarlo** desde el panel de cliente de Hetzner

**Cómo firmar el DPA en Hetzner:** Panel de cliente → Ajustes de cuenta → Protección de datos → Aceptar el acuerdo.

### Qué cubre ya la aplicación

| Obligación RGPD | Estado |
|-----------------|--------|
| Consentimiento explícito antes del registro | ✅ Implementado (`consentimiento_at`) |
| Cifrado de datos personales | ✅ AES-256-GCM |
| Borrado de audios tras procesamiento | ✅ Inmediato tras Whisper |
| No logging de datos personales | ✅ Convenido en el código |
| Limitación de acceso (lista blanca) | ✅ `usuarios_autorizados` |
| Borrado de documentos de Telegram | ✅ 5 minutos |

### Qué falta para producción real (no urgente para el piloto)

| Obligación | Descripción |
|------------|-------------|
| **Política de privacidad** | Texto accesible en el bot (`/privacidad`) y en la web. Indica qué datos se recogen, con qué fin, cuánto tiempo se guardan y cómo ejercer derechos |
| **Derecho al olvido** | Comando `/eliminar` que borra todos los datos del usuario de la BD |
| **Registro de actividades** | Documento interno (no público) con el registro de tratamientos de datos (Art. 30 RGPD) |
| **Backups cifrados** | Los `pg_dump` de backup también deben cifrarse (ej: `gpg --symmetric`) antes de guardarse |

### Nota sobre APIs externas (Whisper + Claude)

Telegram, OpenAI (Whisper) y Anthropic (Claude) son procesadores de datos. El audio de voz y la transcripción pasan por sus servidores. Para producción, habría que:
- Revisar si OpenAI y Anthropic ofrecen DPA compatible con RGPD (ambos lo ofrecen, pero requiere cuenta de empresa)
- Indicarlo en la política de privacidad

Para el piloto con dos amigos que conoces y que son conscientes de la herramienta, esto es aceptable sin formalizar.

---

## 4. Generar la clave de cifrado

En tu máquina local (o en el servidor):

```bash
openssl rand -base64 32
```

Copiar el resultado como valor de `CRYPTO_SECRET_KEY` en el servicio systemd.
**Guardar esta clave en un lugar seguro** — si se pierde, los datos cifrados en BD son irrecuperables.

---

## 3. Compilar y desplegar (desde tu máquina local)

### 3.1 Crear perfil de producción

Editar `src/main/profiles/prod/config.properties` (los valores de este fichero son un fallback; en producción se usan las variables de entorno del systemd, que tienen preferencia):

```properties
db.url=jdbc:postgresql://localhost:5432/tugestorai
db.user=gestorai_db
db.password=

telegram.bot.token=
telegram.bot.username=

openai.api.key=
anthropic.api.key=
crypto.secret.key=

email.smtp.host=smtp.gmail.com
email.smtp.port=587
email.smtp.user=
email.smtp.password=
email.from=

limit.audio.max.duration=180
limit.audio.max.size=1048576
limit.requests.per.hour=10
limit.requests.per.day=30
limit.claude.max.retries=1
limit.edits.per.presupuesto=5
limit.email.per.day=20
limit.cost.daily.max=3.00
limit.telegram.doc.ttl=300
```

### 3.2 Generar el WAR

```bash
# En tu máquina local, desde la raíz del proyecto
JAVA_HOME="$HOME/.jdks/corretto-24.0.2" mvn clean package -Pprod --settings .mvn/settings-personal.xml -DskipTests

# El WAR generado estará en:
# target/tugestorai.war
```

### 3.3 Desplegar en el servidor

```bash
# Subir WAR al servidor
scp target/tugestorai.war gestorai@<IP_DEL_VPS>:/opt/tomcat/webapps/ROOT.war

# Tomcat lo desplegará automáticamente si está arrancado
# Si ya había una versión anterior, Tomcat la reemplaza
```

Si Tomcat no está arrancado todavía:

```bash
ssh gestorai@<IP_DEL_VPS>
sudo systemctl start tomcat
sudo systemctl status tomcat
```

### 3.4 Verificar que arranca

```bash
# En el servidor
tail -f /opt/tomcat/logs/catalina.out

# En local, probar health check
curl http://<IP_DEL_VPS>:8080/health
# Esperado: {"status":"ok"}
```

---

## 5. Añadir usuario autorizado (lista blanca Telegram)

Para que tus amigos puedan usar el bot, hay que insertar sus `telegram_id` en la tabla `usuarios_autorizados`:

```bash
sudo -u postgres psql -d tugestorai

-- Obtener el telegram_id: pedir al usuario que envíe un mensaje al bot
-- y buscarlo en los logs: grep "telegram_id" /opt/tomcat/logs/catalina.out

INSERT INTO usuarios_autorizados (telegram_id, autorizado_por)
VALUES (123456789, 'admin');

INSERT INTO usuarios_autorizados (telegram_id, autorizado_por)
VALUES (987654321, 'piloto_amigo_1');
```

---

## 6. Checklist pre-prueba

- [ ] Servidor arrancado y accesible por SSH
- [ ] PostgreSQL activo y esquema aplicado
- [ ] Variables de entorno configuradas en `/etc/systemd/system/tomcat.service`
- [ ] Tomcat arrancado y WAR desplegado
- [ ] `curl http://<IP>/health` devuelve `{"status":"ok"}`
- [ ] `telegram_id` de los usuarios piloto insertados en `usuarios_autorizados`
- [ ] Bot de Telegram operativo (enviar `/start` y verificar respuesta)
- [ ] Clave de cifrado (`CRYPTO_SECRET_KEY`) generada y guardada de forma segura
- [ ] Email SMTP configurado y probado (enviar un presupuesto de prueba)

---

## 7. Comandos útiles de operación

```bash
# Ver logs en tiempo real
tail -f /opt/tomcat/logs/catalina.out

# Reiniciar Tomcat (tras despliegue de nuevo WAR)
sudo systemctl restart tomcat

# Parar / Arrancar
sudo systemctl stop tomcat
sudo systemctl start tomcat

# Ver estado
sudo systemctl status tomcat

# Conectar a la BD directamente
sudo -u postgres psql -d tugestorai

# Ver presupuestos generados
SELECT numero, usuario_id, total, estado, created_at FROM presupuestos ORDER BY created_at DESC LIMIT 20;

# Ver usuarios registrados
SELECT id, telegram_id, nombre, plan, presupuestos_mes FROM usuarios;
```

---

## 8. Próximos pasos (post-piloto)

Cuando el piloto funcione y se quiera pasar a producción real:

- Añadir **Nginx** como reverse proxy (puerto 80/443 → 8080)
- Configurar **SSL/TLS** con Let's Encrypt (`certbot`)
- Asignar un **dominio** propio
- Separar el bot de producción del de desarrollo (dos tokens distintos)
- Activar backups automáticos de PostgreSQL (`pg_dump` en cron)
- Configurar monitorización básica (UptimeRobot o similar, gratis)
