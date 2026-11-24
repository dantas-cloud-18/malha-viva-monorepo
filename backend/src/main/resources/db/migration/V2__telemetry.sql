-- Telemetria anônima: sem user_id, handle, IP ou modelo de aparelho.
CREATE TABLE telemetry_points (
    anon_epoch_id TEXT        NOT NULL,
    seq           INT         NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL,
    received_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    corridor_id   TEXT        NOT NULL,
    geom          GEOMETRY(Point, 4326) NOT NULL,
    accuracy_m    REAL        NOT NULL,
    speed_mps     REAL,
    bearing_deg   REAL,
    motion_state  TEXT,
    PRIMARY KEY (anon_epoch_id, seq, recorded_at)
) PARTITION BY RANGE (recorded_at);

CREATE INDEX telemetry_points_time_idx ON telemetry_points (corridor_id, recorded_at);

-- Cria partições diárias à frente e apaga as mais antigas que a retenção.
-- Chamada pelo backend a cada hora; não depende de pg_cron nem pg_partman.
CREATE FUNCTION telemetry_maintain_partitions(retention interval, days_ahead int DEFAULT 2)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    d     date;
    part  record;
    limit_day date := (now() - retention)::date;
BEGIN
    FOR i IN 0..days_ahead LOOP
        d := current_date + i;
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF telemetry_points FOR VALUES FROM (%L) TO (%L)',
            'telemetry_points_' || to_char(d, 'YYYYMMDD'), d, d + 1);
    END LOOP;

    FOR part IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'telemetry_points'::regclass
          AND to_date(right(c.relname, 8), 'YYYYMMDD') < limit_day
    LOOP
        EXECUTE format('DROP TABLE %I', part.relname);
    END LOOP;
END;
$$;

SELECT telemetry_maintain_partitions(interval '30 days');

-- Nonce de uso único para anti-replay (janela de 60 s; o backend apaga os vencidos).
CREATE TABLE telemetry_nonces (
    nonce      TEXT PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX telemetry_nonces_exp_idx ON telemetry_nonces (expires_at);

-- Eventos consolidados da linha (agregado, sem identidade).
CREATE TABLE train_events (
    event_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    train_id          TEXT        NOT NULL,
    corridor_id       TEXT        NOT NULL,
    direction         TEXT        NOT NULL CHECK (direction IN ('SANTA_CRUZ_TO_CENTRAL', 'CENTRAL_TO_SANTA_CRUZ')),
    event_type        TEXT        NOT NULL CHECK (event_type IN
                          ('STATION_ARRIVAL', 'STATION_DEPARTURE', 'PAUSE_START', 'PAUSE_END')),
    station_id        TEXT REFERENCES stations,
    segment_id        TEXT REFERENCES track_segments,
    occurred_at       TIMESTAMPTZ NOT NULL,
    consensus_devices INT         NOT NULL CHECK (consensus_devices >= 0),
    confidence        NUMERIC(4, 3) NOT NULL CHECK (confidence BETWEEN 0 AND 1)
);
CREATE INDEX train_events_time_idx ON train_events (corridor_id, occurred_at);
