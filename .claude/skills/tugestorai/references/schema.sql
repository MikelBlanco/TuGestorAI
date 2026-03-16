-- =============================================
-- TuGestorAI - Creación de base de datos y esquema
-- PostgreSQL
--
-- Ejecutar como superusuario (postgres):
--   psql -U postgres -f setup_completo.sql
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

-- Control de acceso: usuarios autorizados a usar el bot
CREATE TABLE usuarios_autorizados (
                                      telegram_id BIGINT PRIMARY KEY,
                                      autorizado_por VARCHAR(200),
                                      created_at TIMESTAMP DEFAULT NOW()
);

-- Usuarios (autónomos)
CREATE TABLE usuarios (
                          id BIGSERIAL PRIMARY KEY,
                          telegram_id BIGINT UNIQUE NOT NULL,
                          nombre VARCHAR(200) NOT NULL,
                          nif VARCHAR(20),
                          direccion TEXT,
                          telefono VARCHAR(20),
                          email VARCHAR(200),
                          nombre_comercial VARCHAR(200),
                          logo_url VARCHAR(500),
                          plan VARCHAR(20) DEFAULT 'free',
                          presupuestos_mes INT DEFAULT 0,
                          created_at TIMESTAMP DEFAULT NOW(),
                          updated_at TIMESTAMP
);

-- Clientes del autónomo
CREATE TABLE clientes (
                          id BIGSERIAL PRIMARY KEY,
                          usuario_id BIGINT NOT NULL REFERENCES usuarios(id),
                          nombre VARCHAR(200) NOT NULL,
                          nif VARCHAR(20),
                          direccion TEXT,
                          telefono VARCHAR(20),
                          email VARCHAR(200),
                          created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_clientes_usuario ON clientes(usuario_id);

-- Presupuestos
CREATE TABLE presupuestos (
                              id BIGSERIAL PRIMARY KEY,
                              numero VARCHAR(50) NOT NULL,
                              usuario_id BIGINT NOT NULL REFERENCES usuarios(id),
                              cliente_id BIGINT REFERENCES clientes(id),
                              cliente_nombre VARCHAR(200),
                              descripcion TEXT,
                              subtotal DECIMAL(10,2),
                              iva_porcentaje DECIMAL(5,2) DEFAULT 21.00,
                              iva_importe DECIMAL(10,2),
                              total DECIMAL(10,2),
                              estado VARCHAR(20) DEFAULT 'borrador',
                              audio_transcript TEXT,
                              created_at TIMESTAMP DEFAULT NOW(),
                              updated_at TIMESTAMP,
                              enviado_at TIMESTAMP
);

CREATE INDEX idx_presupuestos_usuario ON presupuestos(usuario_id);
CREATE INDEX idx_presupuestos_estado ON presupuestos(usuario_id, estado);

-- Facturas
CREATE TABLE facturas (
                          id BIGSERIAL PRIMARY KEY,
                          numero VARCHAR(50) NOT NULL,
                          usuario_id BIGINT NOT NULL REFERENCES usuarios(id),
                          presupuesto_id BIGINT REFERENCES presupuestos(id),
                          cliente_id BIGINT REFERENCES clientes(id),
                          subtotal DECIMAL(10,2),
                          iva_porcentaje DECIMAL(5,2) DEFAULT 21.00,
                          iva_importe DECIMAL(10,2),
                          irpf_porcentaje DECIMAL(5,2) DEFAULT 15.00,
                          irpf_importe DECIMAL(10,2),
                          total DECIMAL(10,2),
                          estado VARCHAR(20) DEFAULT 'borrador',
                          created_at TIMESTAMP DEFAULT NOW(),
                          updated_at TIMESTAMP
);

CREATE INDEX idx_facturas_usuario ON facturas(usuario_id);

-- Líneas de detalle (compartidas entre presupuestos y facturas)
CREATE TABLE lineas_detalle (
                                id BIGSERIAL PRIMARY KEY,
                                presupuesto_id BIGINT REFERENCES presupuestos(id) ON DELETE CASCADE,
                                factura_id BIGINT REFERENCES facturas(id) ON DELETE CASCADE,
                                concepto VARCHAR(500) NOT NULL,
                                cantidad DECIMAL(10,2) DEFAULT 1,
                                precio_unitario DECIMAL(10,2) NOT NULL,
                                importe DECIMAL(10,2) NOT NULL,
                                tipo VARCHAR(20) DEFAULT 'servicio',
                                orden INT DEFAULT 0,
                                CONSTRAINT chk_linea_padre CHECK (
                                    (presupuesto_id IS NOT NULL AND factura_id IS NULL) OR
                                    (presupuesto_id IS NULL AND factura_id IS NOT NULL)
                                    )
);

CREATE INDEX idx_lineas_presupuesto ON lineas_detalle(presupuesto_id);
CREATE INDEX idx_lineas_factura ON lineas_detalle(factura_id);

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

CREATE TRIGGER trg_usuarios_updated BEFORE UPDATE ON usuarios
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_presupuestos_updated BEFORE UPDATE ON presupuestos
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

CREATE TRIGGER trg_facturas_updated BEFORE UPDATE ON facturas
    FOR EACH ROW EXECUTE FUNCTION actualizar_updated_at();

-- =============================================
-- Asignar propiedad de las tablas al usuario
-- =============================================

ALTER TABLE usuarios_autorizados OWNER TO tugestorai;
ALTER TABLE usuarios OWNER TO tugestorai;
ALTER TABLE clientes OWNER TO tugestorai;
ALTER TABLE presupuestos OWNER TO tugestorai;
ALTER TABLE facturas OWNER TO tugestorai;
ALTER TABLE lineas_detalle OWNER TO tugestorai;

-- =============================================
-- Datos iniciales
-- =============================================

INSERT INTO usuarios_autorizados (telegram_id, autorizado_por) VALUES (666004366, 'admin');