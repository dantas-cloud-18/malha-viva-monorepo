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

-- Partições por hora (UTC): a partição sai inteira quando sua hora final passa da retenção,
-- então nenhum ponto vive mais que retenção + 1 h.
-- Chamada pelo backend a cada hora; não depende de pg_cron nem pg_partman.
CREATE FUNCTION telemetry_maintain_partitions(retention interval, hours_ahead int DEFAULT 3)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    h    timestamptz;
    part record;
BEGIN
    FOR i IN 0..hours_ahead LOOP
        h := date_trunc('hour', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC' + make_interval(hours => i);
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF telemetry_points FOR VALUES FROM (%L) TO (%L)',
            'telemetry_points_' || to_char(h AT TIME ZONE 'UTC', 'YYYYMMDDHH24'), h, h + interval '1 hour');
    END LOOP;

    FOR part IN
        SELECT c.relname
        FROM pg_inherits i
        JOIN pg_class c ON c.oid = i.inhrelid
        WHERE i.inhparent = 'telemetry_points'::regclass
          AND (to_timestamp(right(c.relname, 10), 'YYYYMMDDHH24')::timestamp AT TIME ZONE 'UTC') + interval '1 hour'
              <= now() - retention
    LOOP
        EXECUTE format('DROP TABLE %I', part.relname);
    END LOOP;
END;
$$;

SELECT telemetry_maintain_partitions(interval '24 hours');

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
