#!/usr/bin/env python3
"""Gera SQL idempotente (rede do Ramal Santa Cruz) a partir do base.json do piloto.

Uso: python scripts/migrate_network.py caminho/base.json > rede.sql
     psql ... -v ON_ERROR_STOP=1 -f rede.sql

Só geometria e tempos nominais; nenhum dado pessoal. O SQL roda numa transação e aborta
se a contagem ou a integridade não conferir.
"""
import json
import re
import sys
import unicodedata

CORRIDOR_ID = "santa-cruz"
PLATFORM_RADIUS_M = 40
DAY_TYPES = ("WEEKDAY", "SATURDAY", "SUNDAY_HOLIDAY")
DIRECTIONS = ("SANTA_CRUZ_TO_CENTRAL", "CENTRAL_TO_SANTA_CRUZ")


def slug(name):
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_name.lower()).strip("-")


def q(text):
    return "'" + text.replace("'", "''") + "'"


def point(lat, lon):
    return f"{lon} {lat}"


def main(path):
    with open(path, encoding="utf-8") as f:
        base = json.load(f)
    stations = base["estacoes"]
    legs = base["trechos"]
    line = base["linha"]  # [lat, lon, km]
    ids = [slug(s["nome"]) for s in stations]
    if len(set(ids)) != len(ids):
        sys.exit("slug de estacao duplicado")
    if len(legs) != len(stations) - 1:
        sys.exit("trechos != estacoes - 1")
    for i, leg in enumerate(legs):
        if leg["de"] != stations[i]["nome"] or leg["para"] != stations[i + 1]["nome"]:
            sys.exit(f"trecho {i} fora de ordem")

    out = ["BEGIN;"]
    out.append(
        f"INSERT INTO corridors (corridor_id, name) VALUES ({q(CORRIDOR_ID)}, 'Ramal Santa Cruz') "
        "ON CONFLICT (corridor_id) DO UPDATE SET name = EXCLUDED.name;"
    )
    for i, s in enumerate(stations):
        out.append(
            "INSERT INTO stations (station_id, corridor_id, name, seq_order, km, platform) VALUES "
            f"({q(ids[i])}, {q(CORRIDOR_ID)}, {q(s['nome'])}, {i}, {s['km']}, "
            f"ST_Buffer(ST_SetSRID(ST_MakePoint({s['lon']}, {s['lat']}), 4326)::geography, "
            f"{PLATFORM_RADIUS_M})::geometry) "
            "ON CONFLICT (station_id) DO UPDATE SET corridor_id = EXCLUDED.corridor_id, "
            "name = EXCLUDED.name, seq_order = EXCLUDED.seq_order, km = EXCLUDED.km, "
            "platform = EXCLUDED.platform;"
        )
    for i, leg in enumerate(legs):
        a, b = stations[i], stations[i + 1]
        inner = [p for p in line if a["km"] < p[2] < b["km"]]
        coords = [point(a["lat"], a["lon"])] + [point(p[0], p[1]) for p in inner]
        coords.append(point(b["lat"], b["lon"]))
        seg_id = f"{ids[i]}__{ids[i + 1]}"
        out.append(
            "INSERT INTO track_segments (segment_id, corridor_id, from_station_id, to_station_id, "
            "km_start, km_end, line) VALUES "
            f"({q(seg_id)}, {q(CORRIDOR_ID)}, {q(ids[i])}, {q(ids[i + 1])}, {a['km']}, {b['km']}, "
            f"ST_GeomFromText('LINESTRING({', '.join(coords)})', 4326)) "
            "ON CONFLICT (segment_id) DO UPDATE SET corridor_id = EXCLUDED.corridor_id, "
            "from_station_id = EXCLUDED.from_station_id, to_station_id = EXCLUDED.to_station_id, "
            "km_start = EXCLUDED.km_start, km_end = EXCLUDED.km_end, line = EXCLUDED.line;"
        )
        # Tempo nominal do piloto, igual nos dois sentidos e em todas as horas (samples = 0).
        # Parada = a da estacao de chegada em cada sentido.
        for direction, dwell in zip(DIRECTIONS, (b["parada_s"], a["parada_s"])):
            out.append(
                "INSERT INTO segment_baselines (segment_id, direction, day_type, hour_of_day, "
                "travel_sec, dwell_sec, samples) "
                f"SELECT {q(seg_id)}, {q(direction)}, d, h, {round(leg['seg'])}, {round(dwell)}, 0 "
                f"FROM unnest(ARRAY[{', '.join(q(d) for d in DAY_TYPES)}]) AS d, generate_series(0, 23) AS h "
                "ON CONFLICT (segment_id, direction, day_type, hour_of_day) DO UPDATE SET "
                "travel_sec = EXCLUDED.travel_sec, dwell_sec = EXCLUDED.dwell_sec, samples = 0;"
            )

    n_st, n_seg = len(stations), len(legs)
    n_base = n_seg * len(DIRECTIONS) * len(DAY_TYPES) * 24
    km_total = stations[-1]["km"] - stations[0]["km"]
    out.append(
        f"""DO $$
DECLARE bad INT;
BEGIN
  IF (SELECT count(*) FROM stations WHERE corridor_id = {q(CORRIDOR_ID)}) <> {n_st} THEN
    RAISE EXCEPTION 'contagem de estacoes diverge (esperado {n_st})';
  END IF;
  IF (SELECT count(*) FROM track_segments WHERE corridor_id = {q(CORRIDOR_ID)}) <> {n_seg} THEN
    RAISE EXCEPTION 'contagem de trechos diverge (esperado {n_seg})';
  END IF;
  IF (SELECT count(*) FROM segment_baselines b JOIN track_segments t USING (segment_id)
      WHERE t.corridor_id = {q(CORRIDOR_ID)}) <> {n_base} THEN
    RAISE EXCEPTION 'contagem de tempos nominais diverge (esperado {n_base})';
  END IF;
  -- cada trecho comeca na estacao de origem e termina na de destino (tolerancia 1 m)
  SELECT count(*) INTO bad FROM track_segments t
    JOIN stations a ON a.station_id = t.from_station_id
    JOIN stations b ON b.station_id = t.to_station_id
    WHERE t.corridor_id = {q(CORRIDOR_ID)}
      AND (ST_Distance(ST_StartPoint(t.line)::geography, ST_Centroid(a.platform)::geography) > 1
        OR ST_Distance(ST_EndPoint(t.line)::geography, ST_Centroid(b.platform)::geography) > 1);
  IF bad > 0 THEN RAISE EXCEPTION '% trechos nao ligam suas estacoes', bad; END IF;
  -- trechos contiguos e na ordem das estacoes
  SELECT count(*) INTO bad FROM track_segments t
    JOIN stations a ON a.station_id = t.from_station_id
    JOIN stations b ON b.station_id = t.to_station_id
    WHERE t.corridor_id = {q(CORRIDOR_ID)} AND b.seq_order <> a.seq_order + 1;
  IF bad > 0 THEN RAISE EXCEPTION '% trechos fora de sequencia', bad; END IF;
  -- comprimento geometrico x km declarado (tolerancia 5%)
  IF abs((SELECT sum(ST_Length(line::geography)) FROM track_segments WHERE corridor_id = {q(CORRIDOR_ID)})
         / 1000.0 - {km_total}) > {km_total} * 0.05 THEN
    RAISE EXCEPTION 'comprimento total diverge do km declarado';
  END IF;
END $$;
COMMIT;"""
    )
    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
