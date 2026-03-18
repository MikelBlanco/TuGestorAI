-- =============================================================
-- TuGestorAI - Setup completo desde cero
-- PostgreSQL - Schema v3
--
-- Ejecutar como superusuario (postgres):
--   psql -U postgres -f setup_completo_v3.sql
-- =============================================================

-- Eliminar BD si existe (CUIDADO: borra todos los datos)
DROP DATABASE IF EXISTS tugestorai;
DROP USER IF EXISTS tugestorai;

-- Crear usuario y base de datos
CREATE USER tugestorai WITH PASSWORD 'tugestorai';
CREATE DATABASE tugestorai OWNER tugestorai;

-- Conectar a la base de datos
\c tugestorai

-- Dar permisos
GRANT ALL PRIVILEGES ON DATABASE tugestorai TO tugestorai;
GRANT ALL PRIVILEGES ON SCHEMA public TO tugestorai;

-- =============================================================
-- Tabla: autonomos
-- Los autónomos que usan el bot (fontanero, electricista...)
-- =============================================================
CREATE TABLE autonomos (
    id BIGSERIAL PRIMARY KEY,
    telegram_id BIGINT UNIQUE NOT NULL,
    nombre VARCHAR(200) NOT NULL,
    nif TEXT,                          -- cifrado AES-256-GCM
    direccion TEXT,                    -- cifrado
    telefono TEXT,                     -- cifrado
    email TEXT,                        -- cifrado
    nombre_comercial VARCHAR(200),
    logo_url VARCHAR(500),
    plan VARCHAR(20) DEFAULT 'free',   -- 'free' o 'pro'
    presupuestos_mes INT DEFAULT 0,
    rgpd_aceptado_at TIMESTAMP,        -- fecha aceptación RGPD, NULL si no ha aceptado
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);

ALTER TABLE autonomos OWNER TO tugestorai;

-- =============================================================
-- Tabla: autonomos_autorizados
-- Control de acceso al bot (lista blanca)
-- =============================================================
CREATE TABLE autonomos_autorizados (
    telegram_id BIGINT PRIMARY KEY,
    autorizado_por VARCHAR(200),
    created_at TIMESTAMP DEFAULT NOW()
);

ALTER TABLE autonomos_autorizados OWNER TO tugestorai;

-- =============================================================
-- Tabla: clientes
-- Clientes de cada autónomo. Pertenecen a un autónomo.
-- SIN constraint UNIQUE en nombre: puede haber clientes con
-- el mismo nombre. El bot pregunta si es el mismo o uno nuevo.
-- =============================================================
CREATE TABLE clientes (
    id BIGSERIAL PRIMARY KEY,
    autonomo_id BIGINT NOT NULL REFERENCES autonomos(id) ON DELETE CASCADE,
    nombre VARCHAR(200) NOT NULL,
    telefono TEXT,                     -- cifrado
    email TEXT,                        -- cifrado
    direccion TEXT,                    -- cifrado
    nif TEXT,                          -- cifrado, NULL hasta que se facture
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE INDEX idx_clientes_autonomo ON clientes(autonomo_id);
CREATE INDEX idx_clientes_nombre ON clientes(autonomo_id, nombre);

ALTER TABLE clientes OWNER TO tugestorai;

-- =============================================================
-- Tabla: presupuestos
-- Estados: BORRADOR, ENVIADO, ACEPTADO, RECHAZADO, FACTURADO,
--          COBRADO, IMPAGADO, CANCELADO
-- =============================================================
CREATE TABLE presupuestos (
    id BIGSERIAL PRIMARY KEY,
    autonomo_id BIGINT NOT NULL REFERENCES autonomos(id) ON DELETE CASCADE,
    cliente_id BIGINT REFERENCES clientes(id) ON DELETE SET NULL,
    numero VARCHAR(50) UNIQUE NOT NULL,
    estado VARCHAR(20) NOT NULL DEFAULT 'BORRADOR',
    cliente_nombre VARCHAR(200),       -- redundante para consultas rápidas
    subtotal NUMERIC(10,2),
    iva_porcentaje NUMERIC(4,2) DEFAULT 21.00,
    iva_importe NUMERIC(10,2),
    total NUMERIC(10,2),
    audio_transcript TEXT,             -- cifrado
    notas TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE INDEX idx_presupuestos_autonomo ON presupuestos(autonomo_id);
CREATE INDEX idx_presupuestos_estado ON presupuestos(autonomo_id, estado);
CREATE INDEX idx_presupuestos_fecha ON presupuestos(autonomo_id, created_at DESC);

ALTER TABLE presupuestos OWNER TO tugestorai;

ALTER TABLE presupuestos ADD CONSTRAINT chk_presupuesto_estado
    CHECK (estado IN ('BORRADOR', 'ENVIADO', 'ACEPTADO', 'RECHAZADO', 'FACTURADO', 'COBRADO', 'IMPAGADO', 'CANCELADO'));

-- =============================================================
-- Tabla: facturas
-- TuGestorAI NO genera facturas TicketBAI.
-- Tabla para referencia y seguimiento futuro. Por ahora, el
-- estado FACTURADO/COBRADO se gestiona en presupuestos.
-- =============================================================
CREATE TABLE facturas (
    id BIGSERIAL PRIMARY KEY,
    autonomo_id BIGINT NOT NULL REFERENCES autonomos(id) ON DELETE CASCADE,
    presupuesto_id BIGINT REFERENCES presupuestos(id),
    cliente_id BIGINT REFERENCES clientes(id),
    numero VARCHAR(50) UNIQUE NOT NULL,
    cliente_nombre VARCHAR(200),
    subtotal NUMERIC(10,2),
    iva_porcentaje NUMERIC(4,2) DEFAULT 21.00,
    iva_importe NUMERIC(10,2),
    irpf_porcentaje NUMERIC(4,2) DEFAULT 15.00,
    irpf_importe NUMERIC(10,2),
    total NUMERIC(10,2),
    notas TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP
);

CREATE INDEX idx_facturas_autonomo ON facturas(autonomo_id);
CREATE INDEX idx_facturas_fecha ON facturas(autonomo_id, created_at DESC);

ALTER TABLE facturas OWNER TO tugestorai;

-- =============================================================
-- Tabla: lineas_detalle
-- Conceptos de presupuestos y facturas
-- =============================================================
CREATE TABLE lineas_detalle (
    id BIGSERIAL PRIMARY KEY,
    presupuesto_id BIGINT REFERENCES presupuestos(id) ON DELETE CASCADE,
    factura_id BIGINT REFERENCES facturas(id) ON DELETE CASCADE,
    concepto VARCHAR(500) NOT NULL,
    tipo VARCHAR(50),                  -- 'material', 'mano_de_obra', 'desplazamiento', etc.
    cantidad NUMERIC(10,2) DEFAULT 1,
    precio_unitario NUMERIC(10,2) NOT NULL,
    importe NUMERIC(10,2) NOT NULL,    -- cantidad * precio_unitario
    orden INT DEFAULT 0,               -- orden de visualización
    created_at TIMESTAMP DEFAULT NOW(),
    CONSTRAINT chk_linea_referencia CHECK (
        (presupuesto_id IS NOT NULL AND factura_id IS NULL) OR
        (presupuesto_id IS NULL AND factura_id IS NOT NULL)
    )
);

CREATE INDEX idx_lineas_presupuesto ON lineas_detalle(presupuesto_id);
CREATE INDEX idx_lineas_factura ON lineas_detalle(factura_id);

ALTER TABLE lineas_detalle OWNER TO tugestorai;

-- =============================================================
-- Triggers para updated_at automático
-- =============================================================

CREATE OR REPLACE FUNCTION actualizar_updated_at()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_autonomos_updated BEFORE UPDATE ON autonomos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_clientes_updated BEFORE UPDATE ON clientes
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_presupuestos_updated BEFORE UPDATE ON presupuestos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_facturas_updated BEFORE UPDATE ON facturas
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

-- =============================================================
-- Vistas útiles
-- =============================================================

-- Presupuestos pendientes de facturar (estado ACEPTADO)
CREATE OR REPLACE VIEW v_pendientes_facturar AS
SELECT p.id, p.autonomo_id, p.numero, p.cliente_nombre, p.total, p.estado, p.created_at,
       NOW() - p.updated_at AS antiguedad
FROM presupuestos p
WHERE p.estado = 'ACEPTADO'
ORDER BY p.created_at ASC;

ALTER VIEW v_pendientes_facturar OWNER TO tugestorai;

-- Presupuestos pendientes de cobro (estado FACTURADO o IMPAGADO)
CREATE OR REPLACE VIEW v_pendientes_cobro AS
SELECT p.id, p.autonomo_id, p.numero, p.cliente_nombre, p.total, p.estado, p.created_at, p.updated_at,
       NOW() - p.updated_at AS antiguedad
FROM presupuestos p
WHERE p.estado IN ('FACTURADO', 'IMPAGADO')
ORDER BY p.created_at ASC;

ALTER VIEW v_pendientes_cobro OWNER TO tugestorai;

-- Resumen mensual por autónomo
CREATE OR REPLACE VIEW v_resumen_mensual AS
SELECT
    autonomo_id,
    date_trunc('month', created_at) AS mes,
    COUNT(*) AS total_presupuestos,
    COUNT(*) FILTER (WHERE estado = 'ENVIADO') AS enviados,
    COUNT(*) FILTER (WHERE estado = 'ACEPTADO') AS aceptados,
    COUNT(*) FILTER (WHERE estado = 'RECHAZADO') AS rechazados,
    COUNT(*) FILTER (WHERE estado = 'FACTURADO') AS facturados,
    COUNT(*) FILTER (WHERE estado = 'COBRADO') AS cobrados,
    COUNT(*) FILTER (WHERE estado = 'IMPAGADO') AS impagados,
    COUNT(*) FILTER (WHERE estado = 'CANCELADO') AS cancelados,
    COALESCE(SUM(total), 0) AS importe_total,
    COALESCE(SUM(total) FILTER (WHERE estado = 'ACEPTADO'), 0) AS importe_aceptado,
    COALESCE(SUM(total) FILTER (WHERE estado IN ('FACTURADO', 'COBRADO', 'IMPAGADO')), 0) AS importe_facturado,
    COALESCE(SUM(total) FILTER (WHERE estado = 'COBRADO'), 0) AS importe_cobrado,
    COALESCE(SUM(total) FILTER (WHERE estado IN ('FACTURADO', 'IMPAGADO')), 0) AS importe_pendiente_cobro
FROM presupuestos
GROUP BY autonomo_id, date_trunc('month', created_at);

ALTER VIEW v_resumen_mensual OWNER TO tugestorai;

-- =============================================================
-- Datos iniciales
-- =============================================================

INSERT INTO autonomos_autorizados (telegram_id, autorizado_por) VALUES (666004366, 'admin');