#!/usr/bin/env node
// Gera tools/paridade/fixtures/fixture.json: insumo SINTETICO e deterministico para a paridade do motor.
// Nao ha posicoes brutas no repositorio (so tempos de parada em viagens-medidas.json), entao os celulares
// sao simulados sobre o traçado de Base.gs (Apps Script) seguindo o horario real da viagem v-08d8e02a
// e cenarios construidos. Nenhum id de aparelho real nem posicao real de pessoa entra no arquivo.
//
// Uso: node tools/paridade/gerar-fixture.js [pasta-com-Base.gs]
//   (ou MV_APPS_SCRIPT_DIR). Padrao: pasta do projeto no Drive.
'use strict';
const fs = require('fs'), vm = require('vm'), path = require('path');

const RAIZ = path.resolve(__dirname, '..', '..');
const DIR = process.argv[2] || process.env.MV_APPS_SCRIPT_DIR || 'G:/Meu Drive/PROJETOS/Malha Viva - Projeto Trem';
const ctx = {}; vm.createContext(ctx);
vm.runInContext(fs.readFileSync(path.join(DIR, 'Malha Viva · Teste ao vivo · Base.gs'), 'utf8') + '\nthis.B = BASE_RAMAL;', ctx);
const B = ctx.B;

// Gerador pseudoaleatorio fixo (LCG): a mesma saida em qualquer maquina.
let semente = 20261125;
const rnd = () => { semente = (semente * 1664525 + 1013904223) % 4294967296; return semente / 4294967296; };
const entre = (a, b) => a + (b - a) * rnd();

const L = B.linha; // [lat, lon, km] com km 0 = Santa Cruz
function ponto(km) {
  km = Math.max(0, Math.min(L[L.length - 1][2], km));
  for (let i = 0; i < L.length - 1; i++) {
    if (km <= L[i + 1][2]) {
      const f = (km - L[i][2]) / ((L[i + 1][2] - L[i][2]) || 1e-9);
      return [L[i][0] + f * (L[i + 1][0] - L[i][0]), L[i][1] + f * (L[i + 1][1] - L[i][1])];
    }
  }
  return [L[L.length - 1][0], L[L.length - 1][1]];
}
const M_POR_GRAU = 111320;

/** Cronograma [{t, km}] -> km do trem em t (interpolacao linear, fora do intervalo fica no extremo). */
function kmEm(cron, t) {
  if (t <= cron[0].t) return cron[0].km;
  for (let i = 0; i < cron.length - 1; i++) {
    if (t <= cron[i + 1].t) { const f = (t - cron[i].t) / ((cron[i + 1].t - cron[i].t) || 1); return cron[i].km + f * (cron[i + 1].km - cron[i].km); }
  }
  return cron[cron.length - 1].km;
}

/** Celulares a bordo de um trem. lateralM: deslocamento lateral fixo extra (cenario fora do corredor). */
function celulares(prefixo, n, cron, ini, fim, { lateralM = 0, passoParadoS = 10, passoAndandoS = 5 } = {}) {
  const lista = [];
  for (let i = 1; i <= n; i++) {
    const offKm = entre(-25, 25) / 1000, latM = entre(-12, 12) + lateralM;
    const amostras = [];
    let t = ini + Math.floor(entre(0, 4000));
    while (t <= fim) {
      const km = kmEm(cron, t), km2 = kmEm(cron, t + 1000);
      const v = Math.abs(km2 - km) * 1000; // m/s (janela de 1 s)
      const [la, lo] = ponto(km + offKm);
      const ruido = () => entre(-3, 3) / M_POR_GRAU;
      amostras.push({
        t, lat: +(la + latM / M_POR_GRAU + ruido()).toFixed(6), lon: +(lo + (latM / M_POR_GRAU) * 0.3 + ruido() / Math.cos(la * Math.PI / 180)).toFixed(6),
        acc: +entre(5, 12).toFixed(1), v: +v.toFixed(2)
      });
      t += (v >= 1 ? passoAndandoS : passoParadoS) * 1000;
    }
    lista.push({ id: prefixo + i, amostras });
  }
  return lista;
}

const cenarios = [];

// ---- S0: viagem v-08d8e02a (horarios reais de parada, posicoes simuladas) ----
const viagens = JSON.parse(fs.readFileSync(path.join(RAIZ, 'android/core/data/src/main/assets/viagens-medidas.json'), 'utf8'));
const v0 = viagens.viagens.find(v => v.id === 'v-08d8e02a');
const slug = s => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/ /g, '-');
const kmEstacao = Object.fromEntries(B.estacoes.map(e => [slug(e.nome), e.km]));
const cron0 = [];
v0.paradas.forEach(p => {
  const km = kmEstacao[p.estacao];
  if (km === undefined) throw new Error('estacao fora da Base.gs: ' + p.estacao);
  cron0.push({ t: Date.parse(p.chegada), km }, { t: Date.parse(p.saida), km });
});
const ini0 = cron0[0].t, fim0 = cron0[cron0.length - 1].t;
cenarios.push({
  id: 'S0-viagem-v08d8e02a', descricao: 'Viagem real v-08d8e02a (Santa Cruz a Padre Miguel): horarios reais de parada, 4 celulares simulados a bordo.',
  sentido_esperado: 'Central', trens_esperados: 1, inicio_ms: ini0, fim_ms: fim0, passo_ms: 30000,
  paradas_verdade: v0.paradas.map(p => ({ estacao: p.estacao, nome: B.estacoes.find(e => slug(e.nome) === p.estacao).nome, km: kmEstacao[p.estacao], parada_s: p.parada_s })),
  verdade: [cron0], celulares: celulares('s0-', 4, cron0, ini0, fim0)
});

// ---- cenarios construidos (relogio proprio, 2026-11-25 12:00 -03:00) ----
const T0 = Date.parse('2026-11-25T12:00:00-03:00');
const cte = (kmIni, vMs, dir, durS) => [{ t: T0, km: kmIni }, { t: T0 + durS * 1000, km: kmIni + dir * vMs * durS / 1000 }];

cenarios.push({
  id: 'S1-dois-trens-mesmo-sentido', descricao: 'Dois trens para Central, 3 km de distancia, 4 celulares cada, 12 m/s.',
  sentido_esperado: 'Central', trens_esperados: 2, inicio_ms: T0, fim_ms: T0 + 600000, passo_ms: 30000,
  verdade: [cte(4, 12, 1, 600), cte(1, 12, 1, 600)],
  celulares: celulares('s1a-', 4, cte(4, 12, 1, 600), T0, T0 + 600000).concat(celulares('s1b-', 4, cte(1, 12, 1, 600), T0, T0 + 600000))
});
cenarios.push({
  id: 'S2-sentidos-opostos', descricao: 'Um trem para Central (km 8) e outro para Santa Cruz (km 20), 4 celulares cada, 11 m/s; cruzam perto do km 14.',
  trens_esperados: 2, inicio_ms: T0, fim_ms: T0 + 720000, passo_ms: 30000,
  verdade: [cte(8, 11, 1, 720), cte(20, 11, -1, 720)],
  celulares: celulares('s2a-', 4, cte(8, 11, 1, 720), T0, T0 + 720000).concat(celulares('s2b-', 4, cte(20, 11, -1, 720), T0, T0 + 720000))
});
cenarios.push({
  id: 'S3-celular-isolado', descricao: 'Um unico celular andando a 12 m/s (k-anonimato: o backend novo com K=3 nao publica).',
  sentido_esperado: 'Central', trens_esperados: 1, inicio_ms: T0, fim_ms: T0 + 480000, passo_ms: 30000,
  verdade: [cte(5, 12, 1, 480)],
  celulares: celulares('s3-', 1, cte(5, 12, 1, 480), T0, T0 + 480000)
});
cenarios.push({
  id: 'S4-dois-celulares', descricao: 'Trem com 2 celulares a bordo (K=3 esconde; K=2 mostraria).',
  sentido_esperado: 'Central', trens_esperados: 1, inicio_ms: T0, fim_ms: T0 + 480000, passo_ms: 30000,
  verdade: [cte(5, 12, 1, 480)],
  celulares: celulares('s4-', 2, cte(5, 12, 1, 480), T0, T0 + 480000)
});
cenarios.push({
  id: 'S5-lateral-100m', descricao: '4 celulares a ~100 m da linha (antigo aceita ate 150 m; novo rejeita acima de 60 m).',
  sentido_esperado: 'Central', trens_esperados: 1, inicio_ms: T0, fim_ms: T0 + 480000, passo_ms: 30000,
  verdade: [cte(5, 12, 1, 480)],
  celulares: celulares('s5-', 4, cte(5, 12, 1, 480), T0, T0 + 480000, { lateralM: 100 })
});

const saida = {
  versao: 1,
  descricao: 'Fixture SINTETICA e deterministica da paridade do motor (Fase 7.2). Geometria: Base.gs do Apps Script. Gerada por tools/paridade/gerar-fixture.js.',
  geometria: {
    nome: B.trecho, km_total: L[L.length - 1][2],
    linha: L.map(p => [p[0], p[1], p[2]]), // lat, lon, km desde Santa Cruz
    estacoes: B.estacoes.map(e => ({ nome: e.nome, lat: e.lat, lon: e.lon, km: e.km }))
  },
  cenarios
};
const destino = path.join(__dirname, 'fixtures', 'fixture.json');
fs.mkdirSync(path.dirname(destino), { recursive: true });
fs.writeFileSync(destino, JSON.stringify(saida));
console.log('fixture:', destino, (fs.statSync(destino).size / 1024).toFixed(0) + ' KiB,', cenarios.map(c => c.id + ':' + c.celulares.reduce((s, x) => s + x.amostras.length, 0)).join(' '));
