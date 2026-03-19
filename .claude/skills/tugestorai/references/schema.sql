-- =============================================
-- TuGestorAI - Creación de base de datos y esquema
-- PostgreSQL
--
-- Ejecutar como superusuario (postgres):
--   psql -U postgres -f schema.sql
-- =============================================

-- Crear usuario y base de datos
CREATE USER tugestorai WITH PASSWORD 'tugestorai';
CREATE DATABASE tugestorai OWNER tugestorai;

-- Conectar a la base de datos
\c tugestorai

-- Dar permisos
GRANT ALL PRIVILEGES ON DATABASE tugestorai TO tugestorai;
GRANT ALL PRIVILEGES ON SCHEMA public TO tugestorai;

-- =============================================
-- Esquema de tablas
-- =============================================

-- Control de acceso: autónomos autorizados a usar el bot
CREATE TABLE autonomos_autorizados (
    telegram_id   BIGINT PRIMARY KEY,
    autorizado_por VARCHAR(200),
    created_at    TIMESTAMP DEFAULT NOW()
);

-- Autónomos que usan el bot
-- Nota: nif, direccion, telefono, email se almacenan cifrados con AES-256-GCM (TEXT en Base64)
CREATE TABLE autonomos (
    id                BIGSERIAL PRIMARY KEY,
    telegram_id       BIGINT UNIQUE NOT NULL,
    nombre            VARCHAR(200) NOT NULL,
    nif               TEXT,               -- cifrado AES-256-GCM
    direccion         TEXT,               -- cifrado
    telefono          TEXT,               -- cifrado
    email             TEXT,               -- cifrado
    nombre_comercial  VARCHAR(200),
    plan              VARCHAR(20) DEFAULT 'free',
    rgpd_aceptado_at  TIMESTAMP,
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP
);

-- Presupuestos generados por los autónomos
-- Nota: audio_transcript se almacena cifrado con AES-256-GCM
CREATE TABLE presupuestos (
    id              BIGSERIAL PRIMARY KEY,
    autonomo_id     BIGINT NOT NULL REFERENCES autonomos(id),
    cliente_id      BIGINT,               -- NULL hasta confirmar/crear el cliente
    numero          VARCHAR(50) UNIQUE NOT NULL,
    estado          VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    cliente_nombre  VARCHAR(200),
    subtotal        NUMERIC(10,2),
    iva_porcentaje  NUMERIC(5,2) DEFAULT 21.00,
    iva_importe     NUMERIC(10,2),
    total           NUMERIC(10,2),
    audio_transcript TEXT,                -- cifrado AES-256-GCM
    notas           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP
);

CREATE INDEX idx_presupuestos_autonomo ON presupuestos(autonomo_id);
CREATE INDEX idx_presupuestos_estado   ON presupuestos(autonomo_id, estado);

-- Facturas emitidas (vinculadas opcionalmente a un presupuesto)
CREATE TABLE facturas (
    id               BIGSERIAL PRIMARY KEY,
    autonomo_id      BIGINT NOT NULL REFERENCES autonomos(id),
    presupuesto_id   BIGINT REFERENCES presupuestos(id),
    cliente_id       BIGINT,
    numero           VARCHAR(50) UNIQUE NOT NULL,
    estado           VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    cliente_nombre   VARCHAR(200),
    notas            TEXT,
    subtotal         NUMERIC(10,2),
    iva_porcentaje   NUMERIC(5,2) DEFAULT 21.00,
    iva_importe      NUMERIC(10,2),
    irpf_porcentaje  NUMERIC(5,2) DEFAULT 15.00,
    irpf_importe     NUMERIC(10,2),
    total            NUMERIC(10,2),
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP
);

CREATE INDEX idx_facturas_autonomo ON facturas(autonomo_id);
CREATE INDEX idx_facturas_estado   ON facturas(autonomo_id, estado);

-- Líneas de detalle de presupuestos y facturas
-- Cada línea pertenece a un presupuesto O a una factura, nunca a ambos
CREATE TABLE lineas_detalle (
    id               BIGSERIAL PRIMARY KEY,
    presupuesto_id   BIGINT REFERENCES presupuestos(id) ON DELETE CASCADE,
    factura_id       BIGINT REFERENCES facturas(id) ON DELETE CASCADE,
    concepto         VARCHAR(500) NOT NULL,
    tipo             VARCHAR(20) DEFAULT 'servicio',
    cantidad         NUMERIC(10,2) DEFAULT 1,
    precio_unitario  NUMERIC(10,2) NOT NULL,
    importe          NUMERIC(10,2) NOT NULL,
    orden            INT DEFAULT 0,
    CONSTRAINT chk_linea_padre CHECK (
        (presupuesto_id IS NOT NULL AND factura_id IS NULL) OR
        (presupuesto_id IS NULL     AND factura_id IS NOT NULL)
    )
);

CREATE INDEX idx_lineas_presupuesto ON lineas_detalle(presupuesto_id);
CREATE INDEX idx_lineas_factura     ON lineas_detalle(factura_id);

-- =============================================
-- Triggers para updated_at automático
-- =============================================

CREATE OR REPLACE FUNCTION actualizar_updated_at()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_autonomos_updated     BEFORE UPDATE ON autonomos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_presupuestos_updated  BEFORE UPDATE ON presupuestos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_facturas_updated      BEFORE UPDATE ON facturas
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

-- =============================================
-- Asignar propiedad de las tablas al usuario
-- =============================================

ALTER TABLE autonomos_autorizados OWNER TO tugestorai;
ALTER TABLE autonomos             OWNER TO tugestorai;
ALTER TABLE presupuestos          OWNER TO tugestorai;
ALTER TABLE facturas              OWNER TO tugestorai;
ALTER TABLE lineas_detalle        OWNER TO tugestorai;

-- =============================================
-- Datos iniciales
-- =============================================

INSERT INTO autonomos_autorizados (telegram_id, autorizado_por) VALUES (666004366, 'admin');