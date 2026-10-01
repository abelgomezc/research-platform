-- =====================================================================
-- V2: dataset historico de demostracion para la tool query_database.
--
-- Son datos SINTETICOS para probar el flujo, no datos reales de ninguna
-- institucion. Todos los valores son inventados.
--
-- El agente consulta estas tablas con un usuario de solo lectura.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Catalogo de tecnologias de deteccion de fraude
-- ---------------------------------------------------------------------
CREATE TABLE demo_tecnologias (
    id          BIGSERIAL PRIMARY KEY,
    nombre      TEXT       NOT NULL UNIQUE,
    categoria   TEXT       NOT NULL,
    descripcion TEXT       NOT NULL
);

-- ---------------------------------------------------------------------
-- Transacciones historicas sinteticas
-- ---------------------------------------------------------------------
CREATE TABLE demo_transacciones (
    id                  BIGSERIAL PRIMARY KEY,
    fecha               DATE          NOT NULL,
    monto               NUMERIC(14,2) NOT NULL,
    moneda              VARCHAR(3)    NOT NULL DEFAULT 'USD',
    canal               VARCHAR(32)   NOT NULL,
    pais                VARCHAR(2)    NOT NULL,
    score_riesgo        NUMERIC(5,4)  NOT NULL,
    es_fraude           BOOLEAN       NOT NULL,
    tecnologia_detectada VARCHAR(64),
    tiempo_deteccion_ms INTEGER
);

CREATE INDEX idx_demo_transacciones_fecha ON demo_transacciones (fecha);
CREATE INDEX idx_demo_transacciones_fraude ON demo_transacciones (es_fraude);
CREATE INDEX idx_demo_transacciones_tecnologia ON demo_transacciones (tecnologia_detectada);

-- ---------------------------------------------------------------------
-- Incidentes reportados (sinteticos)
-- ---------------------------------------------------------------------
CREATE TABLE demo_incidentes (
    id             BIGSERIAL PRIMARY KEY,
    fecha          DATE          NOT NULL,
    institucion    VARCHAR(64)   NOT NULL,
    tipo_fraude    VARCHAR(64)   NOT NULL,
    monto_estimado NUMERIC(14,2),
    descripcion    TEXT          NOT NULL
);

CREATE INDEX idx_demo_incidentes_fecha ON demo_incidentes (fecha);

-- ---------------------------------------------------------------------
-- Datos sinteticos.
--
-- El generador usa (g * 7919) % 2400000 para dispersar montos y el modulo
-- para elegir canal, pais y etiquetas. Con 1200 filas y g % 20 < 3 quedan
-- 180 transaksiones marcadas como fraude (15 por ciento).
-- ---------------------------------------------------------------------

INSERT INTO demo_tecnologias (nombre, categoria, descripcion) VALUES
    ('Reglas de umbral',     'Reglas',        'Conjunto de reglas estaticas de umbrales y patrones conocidos.'),
    ('Machine Learning',      'Modelado',      'Modelos supervisados que aprenden de transacciones etiquetadas.'),
    ('Grafeno de relaciones', 'Grafo',         'Detecta redes de cuentas conectadas mediante caminos cortos.'),
    ('Biometria behavioral',  'Comportamiento','Perfiles de comportamiento del usuario y deteccion de desvios.'),
    ('Deteccion de anomalias','No supervisado', 'Modelos no supervisados para senales sin etiqueta previa.');

INSERT INTO demo_transacciones
    (fecha, monto, canal, pais, score_riesgo, es_fraude, tecnologia_detectada, tiempo_deteccion_ms)
SELECT
    DATE '2025-01-01' + (g / 4)::int AS fecha,
    ROUND((10 + ((g * 7919) % 2400000) / 100.0)::numeric, 2) AS monto,
    (ARRAY['WEB', 'MOVIL', 'ATM', 'API', 'POS'])[1 + (g % 5)] AS canal,
    (ARRAY['MX', 'CO', 'BR', 'CL', 'AR', 'PE'])[1 + (g % 6)] AS pais,
    ROUND(((g % 100) / 100.0)::numeric, 4) AS score_riesgo,
    (g % 20) < 3 AS es_fraude,
    CASE WHEN (g % 20) < 3
         THEN (ARRAY['Reglas de umbral', 'Machine Learning', 'Grafeno de relaciones'])[1 + (g % 3)]
         ELSE NULL END AS tecnologia_detectada,
    CASE WHEN (g % 20) < 3 THEN 40 + (g % 900) ELSE NULL END AS tiempo_deteccion_ms
FROM generate_series(1, 1200) AS g;

INSERT INTO demo_incidentes (fecha, institucion, tipo_fraude, monto_estimado, descripcion) VALUES
    (DATE '2025-02-14', 'Banco Alfa',      'Apropiacion de cuenta', 185000.00, 'Acceso desde dispositivos no habituales con transferencia inmediata.'),
    (DATE '2025-03-02', 'Fintech Delta',   'Punto de venta',         42000.00, 'Multiples compras de alto valor en minutos con la misma tarjeta.'),
    (DATE '2025-04-18', 'Banco Gamma',     'Phishing',               96500.00, 'Campana de phishing con suplantacion de institucion financiera.'),
    (DATE '2025-05-07', 'Cooperativa Sur', 'Lavado de activos',     312000.00, 'Red de cuentas con movimientos fraccionados bajo umbral.'),
    (DATE '2025-06-11', 'Banco Epsilon',   'Sim Swap',                77000.00, 'Sustitucion de SIM y recuperacion de cuenta en linea.'),
    (DATE '2025-07-23', 'Fintech Zeta',    'Punto de venta',         28400.00, 'Transacciones sin presencia fisica en el exterior del pais.'),
    (DATE '2025-08-05', 'Banco Eta',       'Phishing',              153900.00, 'Suplantacion de institucion y robo de credenciales.'),
    (DATE '2025-09-16', 'Banco Theta',     'Apropiacion de cuenta', 210000.00, 'Recuperacion de cuenta tras compromiso de correo.'),
    (DATE '2025-10-09', 'Wallet Iota',     'Lavado de activos',     141500.00, 'Transferencias a exchanges sin verificacion de origen.'),
    (DATE '2025-11-21', 'Banco Kappa',     'Sim Swap',                62800.00, 'Cambio de linea y operaciones en horario inusual.'),
    (DATE '2025-12-04', 'Banco Lambda',    'Punto de venta',         47500.00, 'Reutilizacion de terminal comprometida.'),
    (DATE '2025-12-28', 'Fintech Mu',      'Apropiacion de cuenta',  89300.00, 'Robo de token de sesion mediante aplicacion no autorizada.');

-- ---------------------------------------------------------------------
-- Vistas de conveniencia.
--
-- Una vista es tan legible como una tabla y evita que el LLM tenga que
-- generar SQL complejo, que es justo lo que se busca evitar.
-- ---------------------------------------------------------------------
CREATE VIEW demo_tasa_fraude_por_pais AS
SELECT pais,
       COUNT(*) AS total_transacciones,
       SUM(CASE WHEN es_fraude THEN 1 ELSE 0 END) AS transacciones_fraude,
       ROUND(AVG(score_riesgo)::numeric, 4) AS score_promedio,
       ROUND(AVG(CASE WHEN es_fraude THEN tiempo_deteccion_ms END)::numeric, 1) AS deteccion_promedio_ms
FROM demo_transacciones
GROUP BY pais;

CREATE VIEW demo_tecnologia_mas_efectiva AS
SELECT tecnologia_detectada AS tecnologia,
       COUNT(*) AS detecciones,
       ROUND(AVG(tiempo_deteccion_ms)::numeric, 1) AS deteccion_promedio_ms
FROM demo_transacciones
WHERE es_fraude AND tecnologia_detectada IS NOT NULL
GROUP BY tecnologia_detectada;

CREATE VIEW demo_tipos_fraude AS
SELECT tipo_fraude,
       COUNT(*) AS incidentes,
       ROUND(AVG(monto_estimado)::numeric, 2) AS monto_promedio,
       ROUND(SUM(monto_estimado)::numeric, 2) AS monto_total
FROM demo_incidentes
GROUP BY tipo_fraude;

-- ---------------------------------------------------------------------
-- Usuario de solo lectura para la tool query_database.
-- Solo puede leer, y nunca escribir.
--
-- La contrasena es de demostracion y esta pensada para entorno local.
-- En un despliegue real se cambia por variable de entorno.
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'research_ro') THEN
        CREATE ROLE research_ro LOGIN PASSWORD 'solo_lectura_demo';
    END IF;
END
$$;

-- El nombre de la base se resuelve en tiempo de ejecucion para que la
-- migracion funcione igual en el contenedor de Testcontainers, cuya base se
-- llama research_platform_test.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO research_ro', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO research_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO research_ro;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO research_ro;
