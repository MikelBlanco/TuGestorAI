-- =============================================
-- TuGestorAI - Reset del esquema (dev)
-- Ejecutar conectado a la BD tugestorai como superusuario
-- ELIMINA todas las tablas existentes y recrea el esquema actual
-- =============================================

-- ── Paso 1: eliminar tablas antiguas ─────────────────────────────────────────

DROP TABLE IF EXISTS public.lineas_detalle       CASCADE;
DROP TABLE IF EXISTS public.facturas             CASCADE;
DROP TABLE IF EXISTS public.presupuestos         CASCADE;
DROP TABLE IF EXISTS public.clientes             CASCADE;
DROP TABLE IF EXISTS public.usuarios             CASCADE;
DROP TABLE IF EXISTS public.usuarios_autorizados CASCADE;
DROP TABLE IF EXISTS public.autonomos            CASCADE;
DROP TABLE IF EXISTS public.autonomos_autorizados CASCADE;

DROP FUNCTION IF EXISTS public.actualizar_updated_at() CASCADE;


-- ── Paso 2: tablas ────────────────────────────────────────────────────────────

-- Lista blanca de autónomos autorizados para usar el bot
CREATE TABLE autonomos_autorizados (
    telegram_id    BIGINT PRIMARY KEY,
    autorizado_por VARCHAR(200),
    created_at     TIMESTAMP DEFAULT NOW()
);

-- Autónomos registrados
-- Los campos sensibles se almacenan cifrados con AES-256-GCM (Base64) → tipo TEXT
CREATE TABLE autonomos (
    id               BIGSERIAL PRIMARY KEY,
    telegram_id      BIGINT UNIQUE NOT NULL,
    nombre           VARCHAR(200) NOT NULL,
    nif              TEXT,                        -- cifrado AES-256-GCM
    direccion        TEXT,                        -- cifrado AES-256-GCM
    telefono         TEXT,                        -- cifrado AES-256-GCM
    email            TEXT,                        -- cifrado AES-256-GCM
    nombre_comercial VARCHAR(200),
    plan             VARCHAR(20) DEFAULT 'free',
    rgpd_aceptado_at TIMESTAMP,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP
);

-- Clientes de cada autónomo
-- Sin UNIQUE sobre nombre: puede haber dos clientes con el mismo nombre.
-- Al detectar coincidencia el bot pregunta si es el mismo o uno nuevo.
CREATE TABLE clientes (
    id          BIGSERIAL PRIMARY KEY,
    autonomo_id BIGINT NOT NULL REFERENCES autonomos(id) ON DELETE CASCADE,
    nombre      VARCHAR(200) NOT NULL,
    nif         TEXT,                            -- cifrado AES-256-GCM (opcional hasta facturar)
    direccion   TEXT,                            -- cifrado AES-256-GCM
    telefono    TEXT,                            -- cifrado AES-256-GCM
    email       TEXT,                            -- cifrado AES-256-GCM
    created_at  TIMESTAMP DEFAULT NOW(),
    updated_at  TIMESTAMP
);

-- Presupuestos
-- Los documentos (PDF/Excel) se generan en memoria y NO se almacenan en disco.
CREATE TABLE presupuestos (
    id               BIGSERIAL PRIMARY KEY,
    autonomo_id      BIGINT NOT NULL REFERENCES autonomos(id) ON DELETE RESTRICT,
    cliente_id       BIGINT REFERENCES clientes(id) ON DELETE SET NULL,
    numero           VARCHAR(50) NOT NULL,
    estado           VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    cliente_nombre   VARCHAR(200),
    subtotal         NUMERIC(10,2),
    iva_porcentaje   NUMERIC(4,2) DEFAULT 21.00,
    iva_importe      NUMERIC(10,2),
    total            NUMERIC(10,2),
    audio_transcript TEXT,                       -- cifrado AES-256-GCM
    notas            TEXT,
    created_at       TIMESTAMP DEFAULT NOW(),
    updated_at       TIMESTAMP
);

-- Facturas
-- Los documentos (PDF/Excel) se generan en memoria y NO se almacenan en disco.
CREATE TABLE facturas (
    id              BIGSERIAL PRIMARY KEY,
    autonomo_id     BIGINT NOT NULL REFERENCES autonomos(id) ON DELETE RESTRICT,
    presupuesto_id  BIGINT REFERENCES presupuestos(id) ON DELETE SET NULL,
    cliente_id      BIGINT REFERENCES clientes(id) ON DELETE SET NULL,
    numero          VARCHAR(50) NOT NULL,
    estado          VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    cliente_nombre  VARCHAR(200),
    subtotal        NUMERIC(10,2),
    iva_porcentaje  NUMERIC(4,2) DEFAULT 21.00,
    iva_importe     NUMERIC(10,2),
    irpf_porcentaje NUMERIC(4,2) DEFAULT 15.00,
    irpf_importe    NUMERIC(10,2),
    total           NUMERIC(10,2),
    notas           TEXT,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP
);

-- Líneas de detalle (compartidas entre presupuestos y facturas)
CREATE TABLE lineas_detalle (
    id              BIGSERIAL PRIMARY KEY,
    presupuesto_id  BIGINT REFERENCES presupuestos(id) ON DELETE CASCADE,
    factura_id      BIGINT REFERENCES facturas(id)     ON DELETE CASCADE,
    concepto        VARCHAR(500) NOT NULL,
    tipo            VARCHAR(20) DEFAULT 'servicio',
    cantidad        NUMERIC(10,2) DEFAULT 1,
    precio_unitario NUMERIC(10,2) NOT NULL,
    importe         NUMERIC(10,2) NOT NULL,
    orden           INT DEFAULT 0,
    CONSTRAINT chk_linea_padre CHECK (
        (presupuesto_id IS NOT NULL AND factura_id IS NULL) OR
        (presupuesto_id IS NULL     AND factura_id IS NOT NULL)
    )
);


-- ── Paso 3: índices ───────────────────────────────────────────────────────────

CREATE INDEX idx_clientes_autonomo     ON clientes(autonomo_id);
CREATE INDEX idx_clientes_nombre       ON clientes(autonomo_id, nombre);

CREATE UNIQUE INDEX uq_presupuestos_numero ON presupuestos(autonomo_id, numero);
CREATE INDEX idx_presupuestos_autonomo ON presupuestos(autonomo_id);
CREATE INDEX idx_presupuestos_estado   ON presupuestos(autonomo_id, estado);
CREATE INDEX idx_presupuestos_fecha    ON presupuestos(autonomo_id, created_at DESC);

CREATE UNIQUE INDEX uq_facturas_numero ON facturas(autonomo_id, numero);
CREATE INDEX idx_facturas_autonomo     ON facturas(autonomo_id);
CREATE INDEX idx_facturas_estado       ON facturas(autonomo_id, estado);

CREATE INDEX idx_lineas_presupuesto    ON lineas_detalle(presupuesto_id);
CREATE INDEX idx_lineas_factura        ON lineas_detalle(factura_id);


-- ── Paso 4: función y triggers para updated_at ───────────────────────────────

CREATE OR REPLACE FUNCTION actualizar_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_autonomos_updated
    BEFORE UPDATE ON autonomos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_clientes_updated
    BEFORE UPDATE ON clientes
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_presupuestos_updated
    BEFORE UPDATE ON presupuestos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_facturas_updated
    BEFORE UPDATE ON facturas
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();


-- ── Paso 5: permisos al usuario de aplicación ────────────────────────────────

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO tugestorai;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO tugestorai;


-- ── Paso 6: datos iniciales ───────────────────────────────────────────────────

INSERT INTO autonomos_autorizados (telegram_id, autorizado_por)
VALUES (666004366, 'admin_inicial');
