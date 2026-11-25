#!/usr/bin/env python3
"""Gera a migracao Flyway da rede do Ramal Santa Cruz a partir do OpenStreetMap.

Fonte: (c) OpenStreetMap contributors, ODbL 1.0 (https://www.openstreetmap.org/copyright).
Relacao OSM 1609112 "Linha Santa Cruz: Central do Brasil -> Santa Cruz" (SuperVia): os vias (ways) em ordem
formam o traçado e os membros role=stop sao as estacoes, de Central do Brasil a Santa Cruz.

Uso (de qualquer pasta, sem dependencias externas):
    python -I scripts/seed_ramal_santa_cruz.py fetch   # baixa data/osm/*.json (Overpass; so para atualizar)
    python -I scripts/seed_ramal_santa_cruz.py build   # le data/osm/*.json e escreve a migracao V11

Convencao de sentido: `km` e a distancia ao longo do traçado a partir de Central do Brasil (km 0) e cresce em
direcao a Santa Cruz (mesma regra do BatchIngestor/JdbcCorridorGeometry: along crescente = Central -> Santa Cruz).
Nenhuma coordenada e inventada: estacoes sao os nos stop_position do OSM; o traçado sao os vertices dos ways.
"""
import json
import math
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OSM_DIR = ROOT / "data" / "osm"
MIGRATION = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration" / "V11__ramal_santa_cruz_network.sql"
RELATION_ID = 1609112
CORRIDOR_ID = "RAMAL_SANTA_CRUZ"
PLATFORM_RADIUS_M = 40
MAX_STOP_OFFSET_M = 25  # parada (stop_position) a mais que isto do traçado = dado suspeito
OVERPASS = "https://overpass-api.de/api/interpreter"


def overpass(query, out_path):
    body = urllib.parse.urlencode({"data": query}).encode()
    for attempt in range(5):
        req = urllib.request.Request(OVERPASS, body, {"User-Agent": "malha-viva-seed/1.0"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
            json.loads(data)
            out_path.write_bytes(data)
            return
        except Exception as e:  # servidor publico lotado: espera e tenta de novo
            print(f"tentativa {attempt + 1} falhou: {e}", file=sys.stderr)
            time.sleep(8 * (attempt + 1))
    sys.exit("Overpass indisponivel")


def fetch():
    OSM_DIR.mkdir(parents=True, exist_ok=True)
    overpass(f"[out:json][timeout:90];rel({RELATION_ID});out geom;", OSM_DIR / "rel_raw.json")
    rel = json.loads((OSM_DIR / "rel_raw.json").read_text(encoding="utf-8"))["elements"][0]
    ids = ",".join(str(m["ref"]) for m in rel["members"] if m["type"] == "node" and m["role"] == "stop")
    overpass(f"[out:json][timeout:60];node(id:{ids});out;", OSM_DIR / "stops_raw.json")


def slug(name):
    ascii_name = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_name.lower()).strip("-")


def q(text):
    return "'" + text.replace("'", "''") + "'"


def metres_xy(lat0, lat, lon, lon0):
    return (lon - lon0) * 111320.0 * math.cos(math.radians(lat0)), (lat - lat0) * 111320.0


def dist(a, b):
    x, y = metres_xy((a[0] + b[0]) / 2, b[0], b[1], a[1])
    return math.hypot(x, y)


def chain_ways(ways):
    """Encadeia os ways na ordem da relacao, invertendo os que vierem ao contrario."""
    pts = [(p["lat"], p["lon"]) for p in ways[0]["geometry"]]
    nxt = [(p["lat"], p["lon"]) for p in ways[1]["geometry"]]
    if pts[0] in (nxt[0], nxt[-1]):
        pts.reverse()
    for w in ways[1:]:
        g = [(p["lat"], p["lon"]) for p in w["geometry"]]
        if g[-1] == pts[-1]:
            g.reverse()
        if g[0] != pts[-1]:
            sys.exit(f"way {w['ref']} nao encosta no anterior: relacao com lacuna")
        pts.extend(g[1:])
    return pts


def project(line, cum, lat, lon):
    """(along_m, lateral_m, ponto projetado, indice do vertice anterior) do ponto sobre a polilinha."""
    best = None
    for i in range(len(line) - 1):
        ax, ay = metres_xy(lat, line[i][0], line[i][1], lon)
        bx, by = metres_xy(lat, line[i + 1][0], line[i + 1][1], lon)
        dx, dy = bx - ax, by - ay
        len2 = dx * dx + dy * dy
        t = 0.0 if len2 == 0 else max(0.0, min(1.0, (-ax * dx - ay * dy) / len2))
        lateral = math.hypot(ax + t * dx, ay + t * dy)
        if best is None or lateral < best[1]:
            seg = cum[i + 1] - cum[i]
            point = (line[i][0] + t * (line[i + 1][0] - line[i][0]), line[i][1] + t * (line[i + 1][1] - line[i][1]))
            best = (cum[i] + t * seg, lateral, point, i)
    return best


def build():
    rel = json.loads((OSM_DIR / "rel_raw.json").read_text(encoding="utf-8"))["elements"][0]
    stop_nodes = {e["id"]: e for e in json.loads((OSM_DIR / "stops_raw.json").read_text(encoding="utf-8"))["elements"]}
    stops = [stop_nodes[m["ref"]] for m in rel["members"] if m["type"] == "node" and m["role"] == "stop"]
    ways = [m for m in rel["members"] if m["type"] == "way" and m["role"] == ""]
    if rel["tags"].get("from") != "Central do Brasil" or rel["tags"].get("to") != "Santa Cruz":
        sys.exit("relacao nao vai de Central do Brasil a Santa Cruz")

    line = chain_ways(ways)
    cum = [0.0]
    for i in range(1, len(line)):
        cum.append(cum[-1] + dist(line[i - 1], line[i]))

    st = []
    for s in stops:
        along, lateral, point, idx = project(line, cum, s["lat"], s["lon"])
        if lateral > MAX_STOP_OFFSET_M:
            sys.exit(f"{s['tags']['name']} a {lateral:.0f} m do traçado")
        st.append({"name": s["tags"]["name"], "osm": s["id"], "lat": s["lat"], "lon": s["lon"],
                   "along": along, "lateral": lateral, "point": point, "idx": idx})
    if st[0]["name"] != "Central do Brasil" or st[-1]["name"] != "Santa Cruz":
        sys.exit("extremos inesperados")
    for a, b in zip(st, st[1:]):
        if b["along"] <= a["along"]:
            sys.exit(f"estacoes fora de ordem: {a['name']} / {b['name']}")
    ids = [slug(x["name"]) for x in st]
    if len(set(ids)) != len(ids):
        sys.exit("slug duplicado")

    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    out = [
        f"-- Rede do Ramal Santa Cruz (SuperVia): {len(st)} estacoes e {len(st) - 1} trechos.",
        "-- GERADO por scripts/seed_ramal_santa_cruz.py",
        f"-- a partir de data/osm/ (relacao OSM {RELATION_ID}, baixada em {stamp}); nao edite a mao, regenere.",
        "-- Fonte: (c) OpenStreetMap contributors, ODbL 1.0, https://www.openstreetmap.org/copyright",
        "-- Base derivada de dados ODbL: manter esta atribuicao e a mesma licenca na base derivada.",
        "-- Sentido: km = distancia ao longo do tracado a partir de Central do Brasil (km 0), crescente rumo a",
        "-- Santa Cruz, como BatchIngestor/JdbcCorridorGeometry. seq_order 1 = Central do Brasil.",
        f"-- Plataforma = circulo de {PLATFORM_RADIUS_M} m em torno do stop_position OSM (nao o poligono real).",
        "-- Cada trecho comeca e termina na projecao da estacao sobre o tracado (contiguos, sem lacuna).",
        "",
        f"INSERT INTO corridors (corridor_id, name) VALUES ({q(CORRIDOR_ID)}, 'Ramal Santa Cruz');",
        "",
    ]
    for i, s in enumerate(st):
        out.append(
            "INSERT INTO stations (station_id, corridor_id, name, seq_order, km, platform) VALUES "
            f"({q(ids[i])}, {q(CORRIDOR_ID)}, {q(s['name'])}, {i + 1}, {s['along'] / 1000:.3f}, "
            f"ST_Buffer(ST_SetSRID(ST_MakePoint({s['lon']}, {s['lat']}), 4326)::geography, "
            f"{PLATFORM_RADIUS_M})::geometry);"
        )
    out.append("")
    for i in range(len(st) - 1):
        a, b = st[i], st[i + 1]
        inner = line[a["idx"] + 1: b["idx"] + 1]
        coords = [a["point"]] + inner + [b["point"]]
        coords = [c for j, c in enumerate(coords) if j == 0 or c != coords[j - 1]]
        wkt = ", ".join(f"{lon:.7f} {lat:.7f}" for lat, lon in coords)
        out.append(
            "INSERT INTO track_segments (segment_id, corridor_id, from_station_id, to_station_id, "
            "km_start, km_end, line) VALUES "
            f"({q(ids[i] + '__' + ids[i + 1])}, {q(CORRIDOR_ID)}, {q(ids[i])}, {q(ids[i + 1])}, "
            f"{a['along'] / 1000:.3f}, {b['along'] / 1000:.3f}, ST_GeomFromText('LINESTRING({wkt})', 4326));"
        )
    MIGRATION.write_text("\n".join(out) + "\n", encoding="utf-8", newline="\n")
    print(f"{len(st)} estacoes, {len(st) - 1} trechos, {st[-1]['along'] / 1000:.2f} km, {len(line)} vertices")
    for i, s in enumerate(st):
        print(f"{i + 1:2d} {s['name']:28s} km {s['along'] / 1000:7.3f}  osm {s['osm']}  fora {s['lateral']:.1f} m")


if __name__ == "__main__":
    {"fetch": fetch, "build": build}.get(sys.argv[1] if len(sys.argv) == 2 else "", lambda: sys.exit(__doc__))()
