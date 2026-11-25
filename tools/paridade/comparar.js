#!/usr/bin/env node
// Paridade do motor (Fase 7.2): roda a fixture no Motor.gs do Apps Script (vm, como "teste do motor.js")
// e compara com a saida do backend novo (backend/build/paridade/novo.json, gerada por ParityHarnessTest).
// Somente leitura. Sem dependencias. Uso:
//   node tools/paridade/comparar.js [pasta-com-Motor.gs-e-Base.gs]   (ou MV_APPS_SCRIPT_DIR)
// Saida: relatorio em texto no stdout e JSON em tools/paridade/out/resultado.json. Codigo de saida 0 sempre
// (o veredito esta no texto); 2 se faltar insumo.
'use strict';
const fs = require('fs'), vm = require('vm'), path = require('path');

const DIR = process.argv[2] || process.env.MV_APPS_SCRIPT_DIR || 'G:/Meu Drive/PROJETOS/Malha Viva - Projeto Trem';
const RAIZ = path.resolve(__dirname, '..', '..');
const FIXTURE = path.join(__dirname, 'fixtures', 'fixture.json');
const NOVO = path.join(RAIZ, 'backend', 'build', 'paridade', 'novo.json');
for (const f of [FIXTURE, NOVO]) if (!fs.existsSync(f)) { console.error('Falta ' + f + ' (rode gerar-fixture.js e o ParityHarnessTest).'); process.exit(2); }

// Tolerancias (documentadas em docs/fase7/PARIDADE.md)
const TOL = {
  ALONG_P95_M: 100,    // p95 da diferenca de posicao ao longo da linha entre trens pareados
  ALONG_MAX_M: 250,    // maximo tolerado (um trem a 12 m/s anda 60 m em 5 s)
  CONTAGEM_IGUAL: 0.9, // fracao minima de ticks com o mesmo numero de trens
  PAREAR_M: 1500,      // distancia maxima para parear um trem antigo com um novo
  PARADO_MPS: 1.0,     // velocidade abaixo da qual o backend novo considera o trem parado
  ESTACAO_M: 250       // mesmo raio do Motor.gs (RAIO_ESTACAO_KM)
};

const fx = JSON.parse(fs.readFileSync(FIXTURE, 'utf8'));
const novo = JSON.parse(fs.readFileSync(NOVO, 'utf8'));

// ---- lado antigo: Motor.gs em sandbox ----
const ctx = {}; vm.createContext(ctx);
const ler = n => fs.readFileSync(path.join(DIR, 'Malha Viva · Teste ao vivo · ' + n), 'utf8');
vm.runInContext(ler('Base.gs') + '\n' + ler('Motor.gs'), ctx);
const { motorPasso_ } = ctx;

function rodarAntigo(cen) {
  const pos = [];
  cen.celulares.forEach(c => c.amostras.forEach(a => pos.push({ id: c.id, t: a.t, lat: a.lat, lon: a.lon })));
  pos.sort((a, b) => a.t - b.t);
  let est = {}; const ticks = [];
  for (let t = cen.inicio_ms; t <= cen.fim_ms; t += cen.passo_ms) {
    est = motorPasso_(pos.filter(p => p.t <= t), est, t);
    ticks.push({ t, trens: est.saida.trens.filter(x => x.confirmado && !x.situacao.startsWith('No terminal')), todos: est.saida.trens });
  }
  return ticks;
}

// ---- mapa metros (backend novo, desde Santa Cruz) -> km da Base.gs, vertice a vertice ----
const L = fx.geometria.linha; // [lat, lon, km]
const cum = [0];
for (let i = 1; i < L.length; i++) {
  const k = Math.cos(((L[i - 1][0] + L[i][0]) / 2) * Math.PI / 180);
  cum.push(cum[i - 1] + Math.hypot((L[i][1] - L[i - 1][1]) * k, L[i][0] - L[i - 1][0]) * 111320);
}
const derivaM = cum[cum.length - 1] - novo.corridor_length_m;
const deltaKmTotalM = L[L.length - 1][2] * 1000 - novo.corridor_length_m;
function kmDeAlong(alongCentralM) { // along novo (0 = Central/Realengo) -> km Base.gs (0 = Santa Cruz)
  const c = novo.corridor_length_m - alongCentralM;
  for (let i = 0; i < cum.length - 1; i++) if (c <= cum[i + 1]) return L[i][2] + (c - cum[i]) / ((cum[i + 1] - cum[i]) || 1e-9) * (L[i + 1][2] - L[i][2]);
  return L[L.length - 1][2];
}
const DIR_ANTIGA = { 'Central': 'SANTA_CRUZ_TO_CENTRAL', 'Santa Cruz': 'CENTRAL_TO_SANTA_CRUZ' };
const norm = tr => tr.map(t => ({ id: t.id, km: t.km, dir: DIR_ANTIGA[t.sentido], v: null, sit: t.situacao, prox: t.proxima }));
const normNovo = tr => tr.map(t => ({ id: t.id, km: kmDeAlong(t.along_m), dir: t.direction, v: t.speed_mps, conf: t.confidence }));

// ---- utilidades ----
const pct = (a, p) => { if (!a.length) return null; const s = [...a].sort((x, y) => x - y); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };
const f1 = x => x == null ? 'n/d' : x.toFixed(1);
function parear(ant, nov) { // guloso por distancia crescente, ignora o sentido (o sentido e comparado depois)
  const pares = [], ua = new Set(), un = new Set();
  const c = [];
  ant.forEach((a, i) => nov.forEach((n, j) => c.push([Math.abs(a.km - n.km) * 1000, i, j])));
  c.sort((x, y) => x[0] - y[0]);
  for (const [d, i, j] of c) { if (d > TOL.PAREAR_M) break; if (ua.has(i) || un.has(j)) continue; ua.add(i); un.add(j); pares.push({ a: ant[i], n: nov[j], d }); }
  return pares;
}
const proximaNovo = (km, dir) => { // mesma regra de vizinhas_ do Motor.gs
  const E = fx.geometria.estacoes; let prox = null;
  if (dir === 'SANTA_CRUZ_TO_CENTRAL') { for (const e of E) if (e.km > km + 0.05) { prox = e; break; } }
  else { for (let i = E.length - 1; i >= 0; i--) if (E[i].km < km - 0.05) { prox = E[i]; break; } }
  return prox ? prox.nome : null;
};
const estacaoPerto = km => { const e = fx.geometria.estacoes.find(e => Math.abs(e.km - km) * 1000 <= TOL.ESTACAO_M); return e ? e.nome : null; };


function kmVerdade(cron, t) {
  if (t <= cron[0].t) return cron[0].km;
  for (let i = 0; i < cron.length - 1; i++) if (t <= cron[i + 1].t) return cron[i].km + (t - cron[i].t) / ((cron[i + 1].t - cron[i].t) || 1) * (cron[i + 1].km - cron[i].km);
  return cron[cron.length - 1].km;
}

// ---- desvio entre o tracado do Apps Script (Base.gs) e o corredor OSM (V11), ponto a ponto a cada ~50 m ----
function desvioOsm() {
  const arq = path.join(RAIZ, 'data', 'osm', 'rel_raw.json');
  if (!fs.existsSync(arq)) return null;
  const segs = [];
  JSON.parse(fs.readFileSync(arq, 'utf8')).elements[0].members.forEach(m => {
    if (m.type !== 'way' || !m.geometry) return;
    for (let i = 0; i < m.geometry.length - 1; i++) segs.push([m.geometry[i].lat, m.geometry[i].lon, m.geometry[i + 1].lat, m.geometry[i + 1].lon]);
  });
  const distM = (lat, lon, s) => {
    const k = Math.cos(lat * Math.PI / 180) * 111320, ax = (s[1] - lon) * k, ay = (s[0] - lat) * 111320, bx = (s[3] - lon) * k, by = (s[2] - lat) * 111320;
    const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy, t = l2 ? Math.max(0, Math.min(1, (-ax * dx - ay * dy) / l2)) : 0;
    return Math.hypot(ax + t * dx, ay + t * dy);
  };
  const ds = [];
  for (let i = 0; i < L.length - 1; i++) {
    const n = Math.max(1, Math.round((L[i + 1][2] - L[i][2]) * 1000 / 50));
    for (let j = 0; j <= n; j++) {
      const f = j / n, lat = L[i][0] + f * (L[i + 1][0] - L[i][0]), lon = L[i][1] + f * (L[i + 1][1] - L[i][1]);
      ds.push(segs.reduce((m, s) => Math.min(m, distM(lat, lon, s)), Infinity));
    }
  }
  return { pontos: ds.length, mediana_m: pct(ds, 0.5), p95_m: pct(ds, 0.95), max_m: Math.max(...ds), acima_60m: ds.filter(d => d > 60).length / ds.length };
}

// ---- comparacao ----
const resultado = { tolerancias: TOL, geometria: { km_total_base: L[L.length - 1][2], comprimento_novo_m: novo.corridor_length_m, delta_km_total_m: deltaKmTotalM, deriva_interna_m: derivaM }, cenarios: [] };
const linhas = [];
const out = s => linhas.push(s);

out('PARIDADE DO MOTOR  Apps Script (Motor.gs v0) x backend novo (BatchIngestor + LiveHub + TrainClusterer)');
out('Insumo: fixture SINTETICA deterministica (nao ha posicoes brutas no repositorio). Ver docs/fase7/PARIDADE.md.');
out(`Geometria: Base.gs km_total=${(deltaKmTotalM + novo.corridor_length_m).toFixed(0)} m, polilinha nova=${novo.corridor_length_m.toFixed(0)} m (delta ${deltaKmTotalM.toFixed(0)} m; mapeado vertice a vertice).`);
const osm = desvioOsm(); resultado.geometria.osm = osm;
if (osm) out(`Base.gs x corredor OSM (V11): ${osm.pontos} pontos, desvio mediano ${f1(osm.mediana_m)} m, p95 ${f1(osm.p95_m)} m, max ${f1(osm.max_m)} m, ${(osm.acima_60m * 100).toFixed(1)}% acima de 60 m (limite lateral do backend).`);
out(`Tolerancias: along p95<=${TOL.ALONG_P95_M} m, max<=${TOL.ALONG_MAX_M} m, ticks com mesma contagem>=${TOL.CONTAGEM_IGUAL * 100}%, sentido igual em 100% dos pares.\n`);

for (const cen of fx.cenarios) {
  const antigo = rodarAntigo(cen);
  const nc = novo.scenarios.find(s => s.id === cen.id);
  const entrada = { id: cen.id, descricao: cen.descricao, ticks: antigo.length, antigo: { ids: new Set(antigo.flatMap(t => t.trens.map(x => x.id))).size, max_trens: Math.max(...antigo.map(t => t.trens.length)) }, runs: [] };
  out(`== ${cen.id}: ${cen.descricao}`);
  out(`   antigo: ${entrada.antigo.max_trens} trem(ns) no maximo, ${entrada.antigo.ids} id(s) distintos em ${antigo.length} ticks de ${cen.passo_ms / 1000} s`);
  for (const run of nc.runs) {
    const difs = [], contDif = [], errA = [], errN = [];
    let igualCont = 0, paresN = 0, dirOk = 0, dirEsperadaA = 0, dirEsperadaN = 0, trensA = 0, trensN = 0, proxOk = 0, proxN = 0;
    antigo.forEach((ta, i) => {
      const tn = run.ticks[i];
      if (ta.t !== tn.t) throw new Error('ticks desalinhados em ' + cen.id);
      const A = norm(ta.trens), N = normNovo(tn.trains);
      const tv = (cen.verdade || []).map(c => kmVerdade(c, ta.t));
      const errTv = km => tv.length ? Math.min(...tv.map(x => Math.abs(x - km))) * 1000 : null;
      if (tv.length) { A.forEach(a => errA.push(errTv(a.km))); N.forEach(n => errN.push(errTv(n.km))); }
      if (A.length === N.length) igualCont++;
      contDif.push(Math.abs(A.length - N.length));
      trensA += A.length; trensN += N.length;
      A.forEach(a => { if (cen.sentido_esperado && a.dir === DIR_ANTIGA[cen.sentido_esperado]) dirEsperadaA++; });
      N.forEach(n => { if (cen.sentido_esperado && n.dir === DIR_ANTIGA[cen.sentido_esperado]) dirEsperadaN++; });
      parear(A, N).forEach(p => {
        paresN++; difs.push(p.d); if (p.a.dir === p.n.dir) dirOk++;
        if (/^Andando/.test(p.a.sit) && p.a.prox) { proxN++; if (proximaNovo(p.n.km, p.n.dir) === p.a.prox) proxOk++; }
      });
    });
    const ticks = antigo.length, r = {
      k: run.k, lote_ms: run.lote_ms, ticks_contagem_igual: igualCont / ticks, dif_contagem_max: Math.max(...contDif), trens_antigo: trensA, trens_novo: trensN,
      ids_novo: new Set(run.ticks.flatMap(t => t.trains.map(x => x.id))).size, rejeitadas: run.rejected,
      pares: paresN, sentido_igual: paresN ? dirOk / paresN : null,
      along_mediana_m: pct(difs, 0.5), along_p95_m: pct(difs, 0.95), along_max_m: difs.length ? Math.max(...difs) : null,
      erro_verdade_antigo_mediana_m: pct(errA, 0.5), erro_verdade_antigo_p95_m: pct(errA, 0.95), erro_verdade_novo_mediana_m: pct(errN, 0.5), erro_verdade_novo_p95_m: pct(errN, 0.95),
      proxima_estacao_igual: proxN ? proxOk / proxN : null, proxima_estacao_n: proxN,
      sentido_esperado_ok_antigo: cen.sentido_esperado ? dirEsperadaA / (trensA || 1) : null, sentido_esperado_ok_novo: cen.sentido_esperado ? dirEsperadaN / (trensN || 1) : null
    };
    r.ok_contagem = r.ticks_contagem_igual >= TOL.CONTAGEM_IGUAL;
    r.ok_posicao = r.pares === 0 ? null : (r.along_p95_m <= TOL.ALONG_P95_M && r.along_max_m <= TOL.ALONG_MAX_M);
    r.ok_sentido = r.pares === 0 ? null : r.sentido_igual === 1;
    entrada.runs.push(r);
    const tag = b => b === null ? 'n/a ' : b ? 'OK  ' : 'DIF ';
    out(`   K=${run.k} lote ${run.lote_ms / 1000} s: contagem igual em ${(r.ticks_contagem_igual * 100).toFixed(0)}% dos ticks [${tag(r.ok_contagem)}] (max dif ${r.dif_contagem_max}; trens-tick antigo ${trensA} x novo ${trensN}; ids novo ${r.ids_novo})`);
    out(`         posicao: ${paresN} pares, mediana ${f1(r.along_mediana_m)} m, p95 ${f1(r.along_p95_m)} m, max ${f1(r.along_max_m)} m [${tag(r.ok_posicao)}]; sentido igual ${r.sentido_igual == null ? 'n/d' : (r.sentido_igual * 100).toFixed(0) + '%'} [${tag(r.ok_sentido)}]`);
    if (errN.length) out(`         erro vs verdade do simulador: antigo mediana ${f1(r.erro_verdade_antigo_mediana_m)} m / p95 ${f1(r.erro_verdade_antigo_p95_m)} m; novo mediana ${f1(r.erro_verdade_novo_mediana_m)} m / p95 ${f1(r.erro_verdade_novo_p95_m)} m`);
    if (r.proxima_estacao_n) out(`         proxima estacao igual em ${(r.proxima_estacao_igual * 100).toFixed(0)}% (${r.proxima_estacao_n} ticks andando)`);
    if (Object.keys(run.rejected).length) out(`         amostras rejeitadas no novo: ${JSON.stringify(run.rejected)}`);
  }

  // paradas (so quando a fixture traz a verdade de campo)
  if (cen.paradas_verdade) {
    const runK = k => nc.runs.find(r => r.k === k && r.lote_ms === 15000);
    const ant = new Set(), nov = { 1: new Set(), 3: new Set() };
    antigo.forEach(ta => ta.todos.forEach(x => { const m = /^Parado em (.+)$/.exec(x.situacao); if (m) ant.add(m[1]); }));
    [1, 3].forEach(k => runK(k).ticks.forEach(tk => tk.trains.forEach(t => { if (t.speed_mps < TOL.PARADO_MPS) { const e = estacaoPerto(kmDeAlong(t.along_m)); if (e) nov[k].add(e); } })));
    out('   paradas em estacao (verdade = horario real da viagem; deteccao em ticks de 30 s):');
    const paradas = cen.paradas_verdade.map(p => ({ estacao: p.nome, parada_s: p.parada_s, antigo: ant.has(p.nome), novo_k1: nov[1].has(p.nome), novo_k3: nov[3].has(p.nome) }));
    paradas.forEach(p => out(`     ${p.estacao.padEnd(22)} ${String(p.parada_s).padStart(4)} s  antigo ${p.antigo ? 'sim' : 'nao'}  novo(K=1) ${p.novo_k1 ? 'sim' : 'nao'}  novo(K=3) ${p.novo_k3 ? 'sim' : 'nao'}`));
    const longas = paradas.filter(p => p.parada_s >= 45);
    const taxa = k => longas.filter(p => p[k]).length + '/' + longas.length;
    out(`     paradas >= 45 s detectadas: antigo ${taxa('antigo')}, novo(K=1) ${taxa('novo_k1')}, novo(K=3) ${taxa('novo_k3')}`);
    entrada.paradas = paradas;
  }
  out('');
  resultado.cenarios.push(entrada);
}

// ---- veredito ----
const cada = (id, k) => resultado.cenarios.find(c => c.id.startsWith(id)).runs.find(r => r.k === k && r.lote_ms === 15000);
const equivalencia = resultado.cenarios.map(c => ({ id: c.id, r: c.runs.find(r => r.k === 1 && r.lote_ms === 15000) }));
const eqOk = equivalencia.filter(e => e.r.ok_contagem && e.r.ok_posicao !== false && e.r.ok_sentido !== false).map(e => e.id);
const eqDif = equivalencia.filter(e => !(e.r.ok_contagem && e.r.ok_posicao !== false && e.r.ok_sentido !== false)).map(e => e.id);
resultado.veredito = { equivalencia_K1_ok: eqOk, equivalencia_K1_divergente: eqDif, producao_K3_oculta: ['S3', 'S4'].map(s => ({ cenario: s, trens_novo: cada(s, 3).trens_novo, trens_antigo: cada(s, 3).trens_antigo })) };
out('VEREDITO (envio a cada 15 s; K=1 = padrao de producao e equivalencia do algoritmo; K=3 = limiar opcional por MALHAVIVA_TRAIN_MIN_CONTRIBUTORS)');
out('  K=1 equivalentes: ' + (eqOk.join(', ') || 'nenhum'));
out('  K=1 divergentes : ' + (eqDif.join(', ') || 'nenhum'));
out(`  K=3: S3 (1 celular) antigo ${cada('S3', 3).trens_antigo} trem-ticks x novo ${cada('S3', 3).trens_novo}; S4 (2 celulares) antigo ${cada('S4', 3).trens_antigo} x novo ${cada('S4', 3).trens_novo}  (so com limiar >= 2 no servidor; o padrao K=1 mostra)`);
fs.mkdirSync(path.join(__dirname, 'out'), { recursive: true });
fs.writeFileSync(path.join(__dirname, 'out', 'resultado.json'), JSON.stringify(resultado, null, 1));
console.log(linhas.join('\n'));
