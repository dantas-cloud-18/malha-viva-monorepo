CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE corridors (
    corridor_id TEXT PRIMARY KEY,
    name        TEXT NOT NULL
);

CREATE TABLE stations (
    station_id  TEXT PRIMARY KEY,
    corridor_id TEXT NOT NULL REFERENCES corridors,
    name        TEXT NOT NULL,
    seq_order   INT  NOT NULL,
    km          NUMERIC(8, 3) NOT NULL,
    platform    GEOMETRY(Polygon, 4326) NOT NULL,
    UNIQUE (corridor_id, seq_order)
);
CREATE INDEX stations_platform_gix ON stations USING gist (platform);

-- Uma geometria por trecho; o sentido é atributo da viagem, não da via.
CREATE TABLE track_segments (
    segment_id      TEXT PRIMARY KEY,
    corridor_id     TEXT NOT NULL REFERENCES corridors,
    from_station_id TEXT NOT NULL REFERENCES stations,
    to_station_id   TEXT NOT NULL REFERENCES stations,
    km_start        NUMERIC(8, 3) NOT NULL,
    km_end          NUMERIC(8, 3) NOT NULL,
    line            GEOMETRY(LineString, 4326) NOT NULL,
    CHECK (km_end > km_start)
);
CREATE INDEX track_segments_line_gix ON track_segments USING gist (line);

CREATE TABLE segment_baselines (
    segment_id   TEXT NOT NULL REFERENCES track_segments,
    direction    TEXT NOT NULL CHECK (direction IN ('SANTA_CRUZ_TO_CENTRAL', 'CENTRAL_TO_SANTA_CRUZ')),
    day_type     TEXT NOT NULL CHECK (day_type IN ('WEEKDAY', 'SATURDAY', 'SUNDAY_HOLIDAY')),
    hour_of_day  SMALLINT NOT NULL CHECK (hour_of_day BETWEEN 0 AND 23),
    travel_sec   INT NOT NULL CHECK (travel_sec > 0),
    dwell_sec    INT NOT NULL CHECK (dwell_sec >= 0),
    samples      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (segment_id, direction, day_type, hour_of_day)
);

CREATE TABLE holidays (
    day  DATE PRIMARY KEY,
    name TEXT NOT NULL
);
