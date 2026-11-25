# Dados do OpenStreetMap (Ramal Santa Cruz)

(c) OpenStreetMap contributors, licenca ODbL 1.0: https://www.openstreetmap.org/copyright

- `rel_raw.json`: relacao OSM 1609112 ("Linha Santa Cruz: Central do Brasil -> Santa Cruz", SuperVia) com geometria
  dos ways e membros, via Overpass API em 2026-11-25.
- `stops_raw.json`: os 23 nos `stop_position` da relacao (nome e coordenadas).
- Reproduzir: `python -I scripts/seed_ramal_santa_cruz.py fetch` e depois `build`, que regenera
  `backend/src/main/resources/db/migration/V11__ramal_santa_cruz_network.sql`.

A rede gravada no banco (estacoes e trechos) e base derivada de dados ODbL: manter a atribuicao acima no app
(tela de creditos/privacidade) e nos docs, e a mesma licenca para a base derivada.
