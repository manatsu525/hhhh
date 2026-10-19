'use strict';
// ============ 大地图世界：地形 / 据点 / 领主 / 部队 / AI / 经济 ============
let G = null; // 当前存档状态
const W = {};

W.TER = { WATER: 0, PLAINS: 1, FOREST: 2, SNOW: 3, STEPPE: 4, DESERT: 5, HILLS: 6, MOUNTAIN: 7, SNOWFOREST: 8, BEACH: 9 };
W.TER_NAME = ['水域', '平原', '森林', '雪原', '草原', '沙漠', '丘陵', '山地', '雪林', '海岸'];
W.TER_SPEED = [0, 1, 0.72, 0.82, 1.05, 0.85, 0.86, 0.55, 0.62, 0.95];
W.TER_BIOME = ['plains', 'plains', 'forest', 'snow', 'steppe', 'desert', 'hills', 'hills', 'snow', 'plains'];
W.CELL = 6;
W.GW = Math.ceil(D.MAP_W / W.CELL);
W.GH = Math.ceil(D.MAP_H / W.CELL);
W.HOURS_PER_SEC = 2;

// ---------------- 地形生成 ----------------
W.genTerrain = function (seed) {
  const nLand = U.makeNoise(seed + 1), nWarp = U.makeNoise(seed + 2), nMt = U.makeNoise(seed + 3), nFor = U.makeNoise(seed + 4);
  const grid = new Uint8Array(W.GW * W.GH);
  const height = new Float32Array(W.GW * W.GH);
  const owner = new Uint8Array(W.GW * W.GH);
  const facs = D.FACTIONS;
  for (let gy = 0; gy < W.GH; gy++) {
    for (let gx = 0; gx < W.GW; gx++) {
      const x = gx * W.CELL + W.CELL / 2, y = gy * W.CELL + W.CELL / 2;
      const i = gy * W.GW + gx;
      const dx = (x - 1500) / 1440, dy = (y - 1000) / 940;
      const d = Math.sqrt(dx * dx + dy * dy) + (nLand(x / 380, y / 380, 4) - 0.5) * 0.42;
      const wx = x + (nWarp(x / 500, y / 500, 3) - 0.5) * 520, wy = y + (nWarp(x / 500 + 30, y / 500 + 30, 3) - 0.5) * 520;
      let best = 0, bd = 1e18;
      for (let f = 0; f < facs.length; f++) {
        const fd = (wx - facs[f].cx) ** 2 + (wy - facs[f].cy) ** 2;
        if (fd < bd) { bd = fd; best = f; }
      }
      owner[i] = best;
      const mt = nMt(x / 210, y / 210, 4);
      height[i] = mt;
      if (d > 1) { grid[i] = W.TER.WATER; height[i] = -1; continue; }
      if (d > 0.965) { grid[i] = W.TER.BEACH; continue; }
      const biome = facs[best].biome;
      const fo = nFor(x / 130, y / 130, 3);
      let t;
      if (mt > 0.665) t = W.TER.MOUNTAIN;
      else if (biome === 'snow') t = fo > 0.6 ? W.TER.SNOWFOREST : W.TER.SNOW;
      else if (biome === 'forest') t = fo > 0.47 ? W.TER.FOREST : W.TER.PLAINS;
      else if (biome === 'steppe') t = fo > 0.72 ? W.TER.FOREST : W.TER.STEPPE;
      else if (biome === 'desert') t = mt > 0.6 ? W.TER.HILLS : W.TER.DESERT;
      else if (biome === 'hills') t = mt > 0.5 ? W.TER.HILLS : fo > 0.62 ? W.TER.FOREST : W.TER.PLAINS;
      else t = fo > 0.63 ? W.TER.FOREST : W.TER.PLAINS;
      grid[i] = t;
    }
  }
  W.grid = grid; W.height = height; W.owner = owner;
};
W.terAt = function (x, y) {
  const gx = Math.floor(x / W.CELL), gy = Math.floor(y / W.CELL);
  if (gx < 0 || gy < 0 || gx >= W.GW || gy >= W.GH) return 0;
  return W.grid[gy * W.GW + gx];
};
W.regionAt = function (x, y) {
  const gx = U.clamp(Math.floor(x / W.CELL), 0, W.GW - 1), gy = U.clamp(Math.floor(y / W.CELL), 0, W.GH - 1);
  return D.FACTIONS[W.owner[gy * W.GW + gx]].id;
};
W.isLand = (x, y) => W.terAt(x, y) !== 0;

// ---------------- 新游戏 ----------------
W.newGame = function (opts) {
  const seed = opts.seed || Math.floor(Math.random() * 1e9);
  W.genTerrain(seed);
  const rnd = U.mulberry(seed + 99);
  G = {
    version: 1, seed, time: 8, nextId: 1, log: [], battles: [], settlements: [], lords: [], parties: [],
    factions: {}, war: {}, battleSize: 70, speed: 1, quests: [], cheats: {}, battleSpeed: 0.7,
  };
  // 势力
  const ids = D.FACTIONS.map(f => f.id).concat(['player']);
  ids.forEach(a => { G.war[a] = {}; ids.forEach(b => (G.war[a][b] = false)); });
  D.FACTIONS.forEach(f => (G.factions[f.id] = { id: f.id, alive: true, king: null, campaign: null, warDays: {} }));
  // 初始战争关系
  const pairs = [['leon', 'high'], ['leon', 'sultan'], ['frost', 'birch'], ['khan', 'birch'], ['khan', 'sultan'], ['frost', 'leon'], ['high', 'sultan']];
  pairs.forEach(([a, b]) => W.setWar(a, b, true, true));

  // 据点
  const placed = [];
  const okSpot = (x, y, minD) => {
    const t = W.terAt(x, y);
    if (t === 0 || t === W.TER.MOUNTAIN || t === W.TER.BEACH) return false;
    for (const p of placed) if (U.dist(x, y, p.x, p.y) < minD) return false;
    return true;
  };
  D.FACTIONS.forEach(f => {
    const names = D.SETTLEMENT_NAMES[f.id];
    const place = (type, list, rad, minD) => {
      list.forEach(name => {
        let x, y, tries = 0, md = minD;
        while (true) {
          const a = rnd() * Math.PI * 2, r = Math.sqrt(rnd()) * rad;
          x = f.cx + Math.cos(a) * r; y = f.cy + Math.sin(a) * r * 0.85;
          if (okSpot(x, y, md) && W.regionAt(x, y) === f.id) break;
          if (++tries > 400) { md *= 0.9; tries = 0; rad *= 1.08; }
        }
        const s = { id: 's' + G.nextId++, name, type, fac: f.id, cult: f.id, owner: null, x, y, garrison: [], prosperity: 50 + Math.floor(rnd() * 30),
          prices: {}, shop: [], horses: [], merc: null, recruits: 4, looted: 0, siege: null, bound: null, restock: 0, wealth: 0 };
        placed.push(s); G.settlements.push(s);
      });
    };
    place('town', names.town, 260, 170);
    place('castle', names.castle, 420, 130);
    place('village', names.village, 480, 85);
  });
  // 村庄归属
  G.settlements.filter(s => s.type === 'village').forEach(v => {
    let best = null, bd = 1e9;
    G.settlements.forEach(s => {
      if (s.type === 'village' || s.fac !== v.fac) return;
      const d = U.dist(v.x, v.y, s.x, s.y);
      if (d < bd) { bd = d; best = s; }
    });
    v.bound = best.id;
  });
  // 领主
  D.FACTIONS.forEach(f => {
    const ln = D.LORD_NAMES[f.id];
    const king = W.makeLord(f.id, ln.king, true, 600);
    G.factions[f.id].king = king.id;
    ln.names.slice(0, 5).forEach(n => W.makeLord(f.id, ln.title + n, false, U.ri(150, 450)));
    const lords = G.lords.filter(l => l.fac === f.id);
    const fiefs = G.settlements.filter(s => s.fac === f.id && s.type !== 'village');
    fiefs.sort((a, b) => (a.type === 'town' ? 0 : 1) - (b.type === 'town' ? 0 : 1));
    fiefs.forEach((s, i) => (s.owner = lords[i % lords.length].id));
  });
  G.settlements.forEach(s => {
    if (s.type === 'village') s.owner = W.S(s.bound).owner;
    W.initPrices(s);
    if (s.type !== 'village') {
      s.garrison = W.genTroops(s.fac, s.type === 'town' ? 95 : 55, 0.5);
      W.restockShop(s);
    }
  });
  // 领主部队
  G.lords.forEach(l => W.spawnLordParty(l));
  // 商队
  G.settlements.filter(s => s.type === 'town').forEach(s => W.spawnCaravan(s));
  // 匪徒
  for (let i = 0; i < 26; i++) W.spawnBandit();
  W.initHeroes();

  // 玩家
  const bg = D.BACKGROUNDS.find(b => b.id === opts.bg) || D.BACKGROUNDS[0];
  const start = G.settlements.find(s => s.type === 'town' && s.fac === (opts.startFac || 'leon')) || G.settlements[0];
  const skills = {}; D.SKILLS.forEach(k => (skills[k.id] = bg.skills[k.id] || 0));
  G.player = {
    name: opts.name || '无名骑士', bg: bg.id, level: 1, xp: 0, attrs: Object.assign({}, bg.attrs), skills,
    attrPts: 1, skillPts: 2, gold: bg.gold, renown: bg.renown, eq: Object.assign({}, bg.eq), inv: [], goods: { grain: 3, bacon: 1 },
    foodAcc: 0, morale: 70, liege: null, status: 'free', rel: {}, hpFrac: 1, kills: 0, battlesWon: 0, foodType: 0,
    lastWeek: 0, quest: null, captiveUntil: 0, siege: null,
  };
  D.FACTIONS.forEach(f => (G.player.rel[f.id] = 0));
  const pp = W.addParty({ id: 'player', kind: 'player', fac: 'player', name: G.player.name, x: start.x + 18, y: start.y + 22,
    troops: [], prisoners: [], gold: 0 });
  if (bg.id === 'noble') W.addTroops(pp, 'leon_militia', 4);
  else if (bg.id === 'merc') W.addTroops(pp, 'merc_inf', 3);
  else W.addTroops(pp, start.fac + '_recruit', 4);
  W.log(`你来到了${start.name}，开始了在大陆上的征途。`);
  return G;
};

W.makeLord = function (fac, name, isKing, renown) {
  const l = { id: 'l' + G.nextId++, name, fac, isKing, renown, partyId: null, respawn: 0, rel: 0 };
  G.lords.push(l);
  return l;
};

// ---------------- 查询工具 ----------------
W.S = id => G.settlements.find(s => s.id === id);
W.L = id => G.lords.find(l => l.id === id);
W.P = id => G.parties.find(p => p.id === id);
W.player = () => W.P('player');
W.facName = f => (f === 'player' ? (G.player.kingdomName || '你的王国') : (D.FAC[f] ? D.FAC[f].name : f));
W.facColor = f => (D.FAC[f] ? D.FAC[f].color : '#999');
W.log = function (msg) {
  G.log.unshift({ t: G.time, msg });
  if (G.log.length > 80) G.log.length = 80;
  if (W.onLog) W.onLog(msg);
};
W.dateStr = function (t) {
  t = t == null ? G.time : t;
  const day = Math.floor(t / 24) + 1, h = Math.floor(t % 24);
  return `第${day}天 ${h < 10 ? '0' : ''}${h}:00`;
};
W.day = () => Math.floor(G.time / 24);

// 玩家所属阵营（用于敌我判断）
W.playerFac = () => (G.player.liege ? G.player.liege : 'player');
W.setWar = function (a, b, v, silent) {
  if (a === b || !G.war[a] || !G.war[b]) return;
  if (G.war[a][b] === v) return;
  G.war[a][b] = v; G.war[b][a] = v;
  if (G.factions[a]) G.factions[a].warDays[b] = 0;
  if (G.factions[b]) G.factions[b].warDays[a] = 0;
  if (!silent) W.log(v ? `⚔ ${W.facName(a)} 向 ${W.facName(b)} 宣战！` : `🕊 ${W.facName(a)} 与 ${W.facName(b)} 签订了和约。`);
};
W.partyFac = p => (p.kind === 'player' ? W.playerFac() : p.fac);
W.hostile = function (fa, fb) {
  if (fa === fb) return false;
  if (fa === 'bandit' || fb === 'bandit') return true;
  if (fa === 'neutral' || fb === 'neutral') return false;
  return !!(G.war[fa] && G.war[fa][fb]);
};
W.partiesHostile = (a, b) => W.hostile(W.partyFac(a), W.partyFac(b));

// ---------------- 部队编制 ----------------
W.addParty = function (p) {
  p.id = p.id || 'p' + G.nextId++;
  p.tx = p.tx == null ? p.x : p.tx; p.ty = p.ty == null ? p.y : p.ty;
  p.ai = p.ai || { mode: 'idle', target: null, until: 0 };
  p.prisoners = p.prisoners || [];
  p.inSettlement = null;
  G.parties.push(p);
  return p;
};
W.removeParty = function (p) {
  const i = G.parties.indexOf(p);
  if (i >= 0) G.parties.splice(i, 1);
  if (p.lordId) { const l = W.L(p.lordId); if (l) { l.partyId = null; l.respawn = G.time + U.rr(40, 90); } }
};
W.addTroops = function (p, id, n, xp, wounded) {
  if (n <= 0) return;
  const list = p.troops || p;
  let st = list.find(s => s.id === id);
  if (!st) { st = { id, n: 0, w: 0, xp: 0 }; list.push(st); }
  st.n += n; st.w += wounded || 0; st.xp += xp || 0;
};
W.removeTroops = function (list, id, n) {
  const st = list.find(s => s.id === id);
  if (!st) return 0;
  const k = Math.min(n, st.n);
  st.n -= k; st.w = Math.min(st.w, st.n);
  if (st.n <= 0) list.splice(list.indexOf(st), 1);
  return k;
};
W.count = list => list.reduce((a, s) => a + s.n, 0);
W.healthy = list => list.reduce((a, s) => a + s.n - s.w, 0);
W.strength = function (list) {
  let s = 0;
  for (const st of list) s += D.troopStrength(D.TROOPS[st.id]) * (st.n - st.w);
  return s;
};
W.partyStrength = function (p) {
  let s = W.strength(p.troops);
  if (p.kind === 'player') s += W.heroStrength();
  if (p.lordId) s += 6;
  return s;
};
W.heroStrength = () => 4 + G.player.level * 0.6 * G.player.hpFrac + W.companions().reduce((a, h) => a + 3 + h.level * 0.5 * h.hpFrac, 0);
W.partySize = p => W.count(p.troops) + (p.kind === 'player' ? 1 + W.companions().length : p.lordId ? 1 : 0);

// ---------------- 同伴（NPC） ----------------
W.initHeroes = function () {
  const towns = G.settlements.filter(s => s.type === 'town');
  G.heroes = D.HEROES.map(h => {
    const skills = {}; D.SKILLS.forEach(k => (skills[k.id] = h.skills[k.id] || 0));
    return { id: h.id, name: h.name, level: h.level, xp: h.level > 1 ? W.xpForLevel(h.level - 1) : 0, attrs: Object.assign({}, h.attrs), skills,
      eq: Object.assign({}, h.eq), hpFrac: 1, attrPts: 0, skillPts: 0, hired: false, where: U.pick(towns).id };
  });
};
W.companions = () => (G && G.heroes ? G.heroes.filter(h => h.hired) : []);
W.heroAt = sid => (G.heroes || []).filter(h => !h.hired && h.where === sid);
W.heroDef = h => D.HERO[h.id];
W.heroMaxHpOf = h => 55 + h.attrs.str + h.skills.ironflesh * 4 + h.level;
W.heroHireCost = h => D.HERO[h.id].cost + h.level * 20;
// 队伍技能：取你和同伴中最高的一项（骑砍规则）
W.pskill = function (id) {
  let v = G.player.skills[id] || 0;
  for (const h of W.companions()) if ((h.skills[id] || 0) > v) v = h.skills[id];
  return v;
};
W.giveHeroXp = function (h, xp) {
  h.xp += Math.round(xp);
  while (h.xp >= W.xpForLevel(h.level)) { h.level++; h.attrPts++; h.skillPts++; W.log(`⭐ 同伴 ${h.name} 升到了 ${h.level} 级。`); }
};
W.hireHero = function (h) {
  h.hired = true; h.where = null; h.hpFrac = Math.max(h.hpFrac, 0.5);
  W.log(`🤝 ${h.name} 加入了你的队伍。`);
};
W.dismissHero = function (h) {
  h.hired = false;
  h.where = U.pick(G.settlements.filter(s => s.type === 'town')).id;
  W.log(`${h.name} 离开了队伍，据说去了 ${W.S(h.where).name}。`);
};
W.partyLimit = function () {
  const pl = G.player;
  return 10 + pl.skills.leadership * 5 + pl.attrs.cha + Math.floor(pl.renown / 25);
};
W.prisonerLimit = () => 5 + G.player.skills.prisoner * 5;

// 按目标人数生成某势力的混编部队
W.genTroops = function (fac, n, quality) {
  const list = [];
  const troops = Object.values(D.TROOPS).filter(t => t.fac === fac);
  if (!troops.length) return list;
  for (let i = 0; i < n; i++) {
    const r = Math.random();
    let tier = r < 0.35 - quality * 0.2 ? 1 : r < 0.62 - quality * 0.15 ? 2 : r < 0.84 - quality * 0.1 ? 3 : r < 0.96 ? 4 : 5;
    const cands = troops.filter(t => t.tier === tier);
    W.addTroops(list, U.pick(cands.length ? cands : troops).id, 1);
  }
  return list;
};

W.spawnLordParty = function (l, at) {
  const fiefs = G.settlements.filter(s => s.owner === l.id && s.type !== 'village');
  let home = at || fiefs[0] || U.pick(G.settlements.filter(s => s.fac === l.fac && s.type !== 'village'));
  if (!home) return null;
  const size = Math.round(18 + l.renown / 12 + (l.isKing ? 30 : 0));
  const p = W.addParty({ kind: 'lord', fac: l.fac, lordId: l.id, name: l.name, x: home.x + U.rr(-15, 15), y: home.y + U.rr(-15, 15),
    troops: W.genTroops(l.fac, Math.round(size * U.rr(0.6, 0.9)), Math.min(1, l.renown / 600)), gold: 500 });
  l.partyId = p.id;
  return p;
};
W.spawnCaravan = function (from) {
  const p = W.addParty({ kind: 'caravan', fac: from.fac, name: `${D.FAC[from.fac].short}商队`, x: from.x, y: from.y,
    troops: [], gold: U.ri(400, 900), home: from.id });
  W.addTroops(p, 'caravan_master', 1); W.addTroops(p, 'caravan_guard', U.ri(6, 14));
  p.goods = {}; for (let i = 0; i < 4; i++) { const g = U.pick(D.PRODUCE[from.cult].cheap); p.goods[g] = (p.goods[g] || 0) + U.ri(1, 3); }
  return p;
};
W.BANDIT_TYPES = {
  plains: ['looter', 'bandit', 'deserter'], forest: ['forest_bandit', 'looter', 'bandit'], snow: ['sea_raider', 'looter'],
  steppe: ['steppe_raider', 'looter'], desert: ['desert_raider', 'looter'], hills: ['mountain_bandit', 'bandit', 'looter'],
};
W.BANDIT_NAMES = { looter: '劫掠者', bandit: '山贼', deserter: '逃兵', forest_bandit: '林间盗匪', sea_raider: '海寇', steppe_raider: '草原马匪', desert_raider: '沙漠盗匪', mountain_bandit: '山地匪帮' };
W.spawnBandit = function () {
  let x, y, t = 0;
  do {
    x = U.rr(150, D.MAP_W - 150); y = U.rr(120, D.MAP_H - 120); t++;
  } while (t < 200 && (!W.isLand(x, y) || G.settlements.some(s => U.dist(x, y, s.x, s.y) < 90)));
  const biome = D.FAC[W.regionAt(x, y)].biome;
  const tid = U.pick(W.BANDIT_TYPES[biome]);
  const n = tid === 'looter' ? U.ri(5, 16) : U.ri(6, 24);
  const p = W.addParty({ kind: 'bandit', fac: 'bandit', name: W.BANDIT_NAMES[tid], x, y, troops: [], gold: n * U.ri(8, 20), homeX: x, homeY: y });
  W.addTroops(p, tid, n);
  if (tid !== 'looter' && U.chance(0.4)) W.addTroops(p, 'looter', U.ri(2, 6));
  return p;
};

// ---------------- 经济 ----------------
W.initPrices = function (s) {
  D.GOODS.forEach(g => {
    const pr = D.PRODUCE[s.cult];
    let m = 1;
    if (pr.cheap.includes(g.id)) m = 0.6; else if (pr.dear.includes(g.id)) m = 1.55;
    s.prices[g.id] = m * U.rr(0.9, 1.12);
  });
};
W.basePriceMul = function (s, gid) {
  const pr = D.PRODUCE[s.cult];
  return pr.cheap.includes(gid) ? 0.6 : pr.dear.includes(gid) ? 1.55 : 1;
};
W.buyPrice = (s, gid) => Math.max(1, Math.round(D.GOOD[gid].price * s.prices[gid] * (1.1 - W.pskill('trade') * 0.012)));
W.sellPrice = (s, gid) => Math.max(1, Math.round(D.GOOD[gid].price * s.prices[gid] * (0.9 + W.pskill('trade') * 0.012)));
W.itemBuyPrice = it => Math.round(it.price * (1.15 - W.pskill('trade') * 0.015));
W.itemSellPrice = it => Math.round(it.price * (0.4 + W.pskill('trade') * 0.02));
W.cargoCount = () => Object.values(G.player.goods).reduce((a, b) => a + b, 0);
W.cargoLimit = () => 40 + (G.player.eq.horse ? 10 : 0);

W.restockShop = function (s) {
  const cult = s.cult;
  const pool = Object.values(D.ITEMS).filter(it => !it.noShop && it.slot !== 'horse' && (it.cult === cult || it.cult === 'any' || (it.cult === 'rare' && U.chance(0.15)) || U.chance(0.06)));
  U.shuffle(pool);
  s.shop = pool.slice(0, 14 + (s.type === 'town' ? 6 : 0)).map(it => it.id);
  const hp = Object.values(D.ITEMS).filter(it => it.slot === 'horse' && (it.cult === cult || it.cult === 'any'));
  U.shuffle(hp);
  s.horses = hp.slice(0, 4).map(it => it.id);
  const mercs = ['merc_inf', 'merc_xbow', 'merc_cav', 'merc_inf', 'merc_xbow'];
  s.merc = s.type === 'town' ? { id: U.pick(mercs), n: U.ri(3, 9) } : null;
  s.restock = G.time + 24 * 7;
};

// ---------------- 移动速度 ----------------
W.partySpeed = function (p) {
  let base;
  const n = W.partySize(p);
  const list = p.troops;
  const mounted = list.reduce((a, s) => a + (D.TROOPS[s.id].eq.horse ? s.n - s.w : 0), 0);
  const total = Math.max(1, W.count(list));
  const wounded = list.reduce((a, s) => a + s.w, 0);
  base = 5.2 + (mounted / total) * 1.1 - Math.min(1.8, n / 55) - (wounded / total) * 0.6;
  if (p.kind === 'player') {
    base *= 1 + W.pskill('pathfinding') * 0.03;
    if (G.player.eq.horse && n <= 2) base += 0.7;
    const cargo = W.cargoCount();
    if (cargo > W.cargoLimit()) base *= 0.75;
  } else if (p.kind === 'caravan') base = 4.4;
  else if (p.kind === 'bandit') base += 0.4;
  base *= W.TER_SPEED[W.terAt(p.x, p.y)] || 0.5;
  return Math.max(1.2, base);
};
W.UNITS_PER_SPEED = 6; // 每小时移动距离 = 速度 × 6

// ---------------- 时间推进 ----------------
W.advance = function (hours) {
  let left = hours;
  while (left > 0) {
    const step = Math.min(0.25, left);
    left -= step;
    const before = G.time;
    G.time += step;
    W.moveParties(step);
    if (Math.floor(before) !== Math.floor(G.time)) W.hourly();
    if (Math.floor(before / 24) !== Math.floor(G.time / 24)) W.daily();
    if (W.pendingEvent) return;
  }
};

W.moveParties = function (h) {
  for (const p of G.parties) {
    if (p.battleId || p.frozen) continue;
    if (p.kind === 'player' && W.playerWaiting) continue;
    if (p.ai.mode === 'siege' || (p.ai.mode === 'rest' && p.inSettlement)) continue;
    // 追踪目标部队
    if (p.chase) {
      const t = W.P(p.chase);
      if (t && !t.inSettlement) { p.tx = t.x; p.ty = t.y; } else p.chase = null;
    }
    const dx = p.tx - p.x, dy = p.ty - p.y, d = Math.hypot(dx, dy);
    if (d < 0.5) { p.moving = false; continue; }
    const v = W.partySpeed(p) * W.UNITS_PER_SPEED * h;
    const k = Math.min(1, v / d);
    let nx = p.x + dx * k, ny = p.y + dy * k;
    if (!W.isLand(nx, ny)) {
      // 沿岸滑行
      if (W.isLand(nx, p.y)) ny = p.y; else if (W.isLand(p.x, ny)) nx = p.x; else { nx = p.x; ny = p.y; p.tx = p.x; p.ty = p.y; }
    }
    p.x = nx; p.y = ny; p.moving = true;
    p.inSettlement = null;
  }
  W.checkContacts();
};

W.CONTACT = 11;
W.checkContacts = function () {
  const pl = W.player();
  const ps = G.parties;
  // 玩家接触
  if (pl && !pl.battleId && !pl.inSettlement && !W.pendingEvent && G.time >= (G.player.captiveUntil || 0)) {
    for (const p of ps) {
      if (p === pl || p.inSettlement) continue;
      const d = U.dist(p.x, p.y, pl.x, pl.y);
      if (d > W.CONTACT) continue;
      if (p.battleId) { if (pl.chase === p.id || pl.chaseBattle === p.battleId) { W.pendingEvent = { type: 'joinBattle', battleId: p.battleId }; return; } continue; }
      const hostileToMe = W.partiesHostile(p, pl) && p.chase === 'player';
      if (pl.chase === p.id || hostileToMe) {
        W.pendingEvent = { type: 'encounter', partyId: p.id, attacked: hostileToMe && pl.chase !== p.id };
        pl.chase = null; pl.tx = pl.x; pl.ty = pl.y;
        return;
      }
    }
    if (pl.targetSettlement) {
      const s = W.S(pl.targetSettlement);
      if (s && U.dist(s.x, s.y, pl.x, pl.y) < 9) {
        W.pendingEvent = { type: 'settlement', id: s.id };
        pl.targetSettlement = null; pl.tx = pl.x; pl.ty = pl.y;
        return;
      }
    }
  }
  // AI之间接触
  for (let i = 0; i < ps.length; i++) {
    const a = ps[i];
    if (a.kind === 'player' || a.battleId || a.inSettlement) continue;
    for (let j = i + 1; j < ps.length; j++) {
      const b = ps[j];
      if (b.kind === 'player' || b.battleId || b.inSettlement) continue;
      if (Math.abs(a.x - b.x) > W.CONTACT || Math.abs(a.y - b.y) > W.CONTACT) continue;
      if (!W.partiesHostile(a, b)) continue;
      if (a.chase === b.id || b.chase === a.id) W.startAIBattle(a, b);
    }
  }
};

W.startAIBattle = function (a, b) {
  const bt = { id: 'b' + G.nextId++, A: [a.id], B: [b.id], x: (a.x + b.x) / 2, y: (a.y + b.y) / 2, end: G.time + U.rr(2, 4) };
  a.battleId = bt.id; b.battleId = bt.id; a.chase = null; b.chase = null;
  G.battles.push(bt);
};
W.resolveAIBattle = function (bt) {
  const A = bt.A.map(W.P).filter(Boolean), B = bt.B.map(W.P).filter(Boolean);
  G.battles.splice(G.battles.indexOf(bt), 1);
  A.concat(B).forEach(p => (p.battleId = null));
  if (!A.length || !B.length) return;
  const res = W.autoResolve(A.map(p => p.troops), B.map(p => p.troops), { bonusA: 1, bonusB: 1 });
  const loser = res.winner === 0 ? B : A, winner = res.winner === 0 ? A : B;
  loser.forEach(p => W.defeatParty(p, winner[0]));
  if (A[0] && B[0] && (A[0].kind === 'lord' || B[0].kind === 'lord')) {
    const w = winner[0], l = loser[0];
    if (w && l && (w.kind === 'lord' || l.kind === 'lord')) W.log(`${w.name} 在战斗中击败了 ${l.name}。`);
  }
  winner.forEach(p => { if (W.healthy(p.troops) <= 0) W.defeatParty(p, null); });
};
W.defeatParty = function (p, by) {
  if (by && by.kind !== 'player') {
    // 胜者获得一部分俘虏/金钱
    by.gold = (by.gold || 0) + Math.round((p.gold || 0) * 0.5);
  }
  if (p.kind === 'lord') {
    const l = W.L(p.lordId);
    if (l) l.renown = Math.max(50, l.renown - 10);
  }
  W.removeParty(p);
};

// 自动战斗结算：A、B 是部队列表的数组（会被直接修改）
W.autoResolve = function (Alists, Blists, opts) {
  opts = opts || {};
  const sideStr = lists => lists.reduce((a, l) => a + W.strength(l), 0);
  const cas = [{}, {}];
  const killOne = (lists, sideIdx, woundChance) => {
    const pool = [];
    lists.forEach(l => l.forEach(st => { const h = st.n - st.w; if (h > 0) pool.push([l, st, h]); }));
    const tot = pool.reduce((a, x) => a + x[2], 0);
    if (!tot) return 0;
    let r = Math.random() * tot;
    for (const [l, st, h] of pool) {
      if ((r -= h) <= 0) {
        const t = D.TROOPS[st.id];
        const c = cas[sideIdx][st.id] || (cas[sideIdx][st.id] = { killed: 0, wounded: 0 });
        if (Math.random() < woundChance) { st.w++; c.wounded++; } else { st.n--; c.killed++; if (st.n <= 0) l.splice(l.indexOf(st), 1); }
        return D.troopStrength(t);
      }
    }
    return 0;
  };
  let hA = (opts.heroA || 0), hB = (opts.heroB || 0);
  for (let round = 0; round < 80; round++) {
    const sa = sideStr(Alists) + hA, sb = sideStr(Blists) + hB;
    if (sideStr(Alists) <= 0 && round > 0 || sa <= 0) return { winner: 1, cas };
    if (sideStr(Blists) <= 0 || sb <= 0) return { winner: 0, cas };
    let dmgB = sa * U.rr(0.04, 0.09) * (opts.bonusA || 1);
    let dmgA = sb * U.rr(0.04, 0.09) * (opts.bonusB || 1);
    while (dmgB > 0) { const k = killOne(Blists, 1, opts.woundB != null ? opts.woundB : 0.45); if (!k) break; dmgB -= k; }
    dmgA *= opts.dmgMulA || 1;
    while (dmgA > 0) { const k = killOne(Alists, 0, opts.woundA != null ? opts.woundA : 0.45); if (!k) break; dmgA -= k; }
    if (sideStr(Blists) <= 0) return { winner: 0, cas };
    if (sideStr(Alists) <= 0) return { winner: 1, cas };
  }
  return { winner: sideStr(Alists) >= sideStr(Blists) ? 0 : 1, cas };
};

// ---------------- 每小时：AI ----------------
W.hourly = function () {
  // 战斗结算
  for (const bt of G.battles.slice()) if (G.time >= bt.end) W.resolveAIBattle(bt);
  for (const p of G.parties) {
    if (p.kind === 'player' || p.battleId) continue;
    if (G.time < (p.ai.next || 0)) continue;
    p.ai.next = G.time + U.rr(0.8, 1.6);
    if (p.kind === 'lord') W.aiLord(p);
    else if (p.kind === 'bandit') W.aiBandit(p);
    else if (p.kind === 'caravan') W.aiCaravan(p);
  }
};

W.nearbyHostile = function (p, radius, maxRatio) {
  let best = null, bd = radius;
  const myS = W.partyStrength(p);
  for (const o of G.parties) {
    if (o === p || o.inSettlement || !W.partiesHostile(p, o)) continue;
    const d = U.dist(p.x, p.y, o.x, o.y);
    if (d >= bd) continue;
    if (o.kind === 'player' && G.time < (G.player.captiveUntil || 0)) continue;
    const os = W.partyStrength(o);
    if (os * (maxRatio || 0.8) < myS || o.battleId) { best = o; bd = d; }
  }
  return best;
};
W.nearbyThreat = function (p, radius) {
  const myS = W.partyStrength(p);
  for (const o of G.parties) {
    if (o === p || o.inSettlement || o.battleId || !W.partiesHostile(p, o)) continue;
    if (U.dist(p.x, p.y, o.x, o.y) < radius && W.partyStrength(o) > myS * 1.25) return o;
  }
  return null;
};
W.fleeFrom = function (p, o) {
  const a = Math.atan2(p.y - o.y, p.x - o.x) + U.rr(-0.4, 0.4);
  p.tx = U.clamp(p.x + Math.cos(a) * 120, 20, D.MAP_W - 20);
  p.ty = U.clamp(p.y + Math.sin(a) * 120, 20, D.MAP_H - 20);
  if (!W.isLand(p.tx, p.ty)) { p.tx = p.x - Math.cos(a) * 60; p.ty = p.y - Math.sin(a) * 60; }
  p.chase = null;
};
W.goTo = function (p, s) { p.tx = s.x + U.rr(-4, 4); p.ty = s.y + U.rr(-4, 4); p.chase = null; p.ai.dest = s.id; };

W.aiLord = function (p) {
  const l = W.L(p.lordId);
  const fac = G.factions[p.fac];
  const ai = p.ai;
  // 围城中
  if (ai.mode === 'siege') {
    const s = W.S(ai.target);
    if (!s || !W.hostile(p.fac, s.fac) || !s.siege) { ai.mode = 'idle'; p.inSettlement = null; }
    return;
  }
  const threat = W.nearbyThreat(p, 70);
  if (threat && ai.mode !== 'campaign') { W.fleeFrom(p, threat); ai.mode = 'flee'; return; }
  const prey = W.nearbyHostile(p, 110, 0.9);
  if (prey && !prey.battleId) { p.chase = prey.id; ai.mode = 'chase'; p.inSettlement = null; return; }
  if (prey && prey.battleId) {
    // 加入附近的战斗
    const bt = G.battles.find(b => b.id === prey.battleId);
    if (bt && U.dist(p.x, p.y, bt.x, bt.y) < 15) {
      const side = bt.A.some(id => { const q = W.P(id); return q && W.partiesHostile(q, p); }) ? 'B' : 'A';
      bt[side].push(p.id); p.battleId = bt.id; return;
    }
    p.tx = bt ? bt.x : prey.x; p.ty = bt ? bt.y : prey.y; return;
  }
  if (ai.mode === 'chase') ai.mode = 'idle';
  const size = W.healthy(p.troops);
  const want = 18 + l.renown / 12 + (l.isKing ? 30 : 0);
  // 太弱：回封地休整
  if (size < want * 0.45 && ai.mode !== 'recruit') {
    const home = G.settlements.find(s => s.owner === l.id && s.type !== 'village') || W.nearestFriendly(p);
    if (home) { W.goTo(p, home); ai.mode = 'recruit'; ai.target = home.id; return; }
  }
  if (ai.mode === 'recruit') {
    const s = W.S(ai.target);
    if (s && U.dist(p.x, p.y, s.x, s.y) < 8) {
      p.inSettlement = s.id;
      if (size >= want * 0.85 || U.chance(0.05)) { ai.mode = 'idle'; p.inSettlement = null; }
      return;
    }
    if (s && s.fac === p.fac) return;
    ai.mode = 'idle';
  }
  // 战役
  const camp = fac && fac.campaign;
  if (camp && camp.lords.includes(l.id)) {
    const s = W.S(camp.target);
    if (!s || !W.hostile(p.fac, s.fac)) { ai.mode = 'idle'; return; }
    ai.mode = 'campaign';
    if (U.dist(p.x, p.y, s.x, s.y) < 14) {
      p.tx = p.x; p.ty = p.y;
      const near = G.parties.filter(q => q.kind === 'lord' && q.fac === p.fac && U.dist(q.x, q.y, s.x, s.y) < 30);
      const atk = near.reduce((a, q) => a + W.partyStrength(q), 0);
      if (atk > W.defenseStrength(s) * 0.9 || s.siege) {
        W.beginSiege(s, p.fac);
        near.forEach(q => { q.ai.mode = 'siege'; q.ai.target = s.id; q.tx = q.x; q.ty = q.y; q.chase = null; });
      }
    } else { p.tx = s.x + U.rr(-10, 10); p.ty = s.y + 12 + U.rr(-4, 4); }
    return;
  }
  // 巡逻 / 去封地
  if (ai.mode !== 'patrol' || U.dist(p.x, p.y, p.tx, p.ty) < 6) {
    const own = G.settlements.filter(s => s.fac === p.fac);
    if (!own.length) { ai.mode = 'idle'; return; }
    const s = U.pick(own);
    ai.mode = 'patrol';
    p.tx = s.x + U.rr(-40, 40); p.ty = s.y + U.rr(-40, 40);
    if (!W.isLand(p.tx, p.ty)) { p.tx = s.x; p.ty = s.y; }
    p.chase = null;
  }
};
W.nearestFriendly = function (p) {
  let best = null, bd = 1e9;
  G.settlements.forEach(s => {
    if (s.fac !== W.partyFac(p) || s.type === 'village') return;
    const d = U.dist(p.x, p.y, s.x, s.y);
    if (d < bd) { bd = d; best = s; }
  });
  return best;
};
W.aiBandit = function (p) {
  const threat = W.nearbyThreat(p, 60);
  if (threat) { W.fleeFrom(p, threat); return; }
  const prey = W.nearbyHostile(p, 75, 0.75);
  if (prey && !prey.battleId && prey.kind !== 'lord') { p.chase = prey.id; return; }
  p.chase = null;
  if (U.dist(p.x, p.y, p.tx, p.ty) < 5 || U.chance(0.1)) {
    p.tx = U.clamp(p.homeX + U.rr(-120, 120), 30, D.MAP_W - 30);
    p.ty = U.clamp(p.homeY + U.rr(-120, 120), 30, D.MAP_H - 30);
    if (!W.isLand(p.tx, p.ty)) { p.tx = p.homeX; p.ty = p.homeY; }
  }
};
W.aiCaravan = function (p) {
  const threat = W.nearbyThreat(p, 55);
  if (threat) { W.fleeFrom(p, threat); return; }
  const dest = p.ai.dest && W.S(p.ai.dest);
  if (dest && U.dist(p.x, p.y, dest.x, dest.y) < 8) {
    // 到达：贸易
    p.gold += U.ri(80, 220);
    dest.prosperity = Math.min(100, dest.prosperity + 1);
    dest.wealth += 60;
    p.ai.dest = null;
  }
  if (!p.ai.dest || !W.S(p.ai.dest)) {
    const towns = G.settlements.filter(s => s.type === 'town' && !W.hostile(p.fac, s.fac) && U.dist(s.x, s.y, p.x, p.y) > 50);
    if (towns.length) W.goTo(p, U.pick(towns));
  }
};

// ---------------- 围城 ----------------
W.defenseStrength = function (s) {
  let st = W.strength(s.garrison) * 1.7;
  G.parties.forEach(p => { if (p.inSettlement === s.id && p.kind === 'lord') st += W.partyStrength(p) * 1.4; });
  return st + (s.type === 'town' ? 12 : 6);
};
W.beginSiege = function (s, fac) {
  if (s.siege && s.siege.fac === fac) return;
  s.siege = { fac, start: G.time, by: 'ai' };
  W.log(`🏰 ${W.facName(fac)} 的军队开始围攻 ${s.name}！`);
};
W.siegeTick = function () {
  G.settlements.forEach(s => {
    if (!s.siege || s.siege.by === 'player') return;
    const besiegers = G.parties.filter(p => p.ai.mode === 'siege' && p.ai.target === s.id && !p.battleId);
    if (!besiegers.length) { s.siege = null; return; }
    if (G.time - s.siege.start < 30) return;
    // 攻城战自动结算
    const defLists = [s.garrison].concat(G.parties.filter(p => p.inSettlement === s.id && p.kind === 'lord' && !W.hostile(p.fac, s.fac)).map(p => p.troops));
    const res = W.autoResolve(besiegers.map(p => p.troops), defLists, { bonusA: 0.8, bonusB: 1.5 });
    const atkFac = s.siege.fac;
    besiegers.forEach(p => { p.ai.mode = 'idle'; p.inSettlement = null; });
    if (res.winner === 0) {
      W.captureSettlement(s, atkFac, besiegers[0].lordId);
    } else {
      W.log(`🛡 ${s.name} 击退了 ${W.facName(atkFac)} 的进攻。`);
      besiegers.forEach(p => { if (W.healthy(p.troops) < 3) W.removeParty(p); });
    }
    const f = G.factions[atkFac]; if (f) f.campaign = null;
    s.siege = null;
  });
};
W.captureSettlement = function (s, fac, newOwner) {
  const oldFac = s.fac;
  // 城内的敌方领主被赶出
  G.parties.filter(p => p.inSettlement === s.id && W.hostile(p.fac, fac)).forEach(p => W.removeParty(p));
  s.fac = fac;
  s.owner = newOwner || null;
  if (!s.owner || (s.owner !== 'player' && !W.L(s.owner))) {
    const lords = G.lords.filter(l => l.fac === fac && l.partyId !== undefined);
    s.owner = lords.length ? U.pick(lords).id : null;
  }
  s.siege = null;
  s.garrison = newOwner === 'player' ? [] : W.genTroops(fac, 25, 0.3);
  G.settlements.filter(v => v.bound === s.id).forEach(v => { v.fac = fac; v.owner = s.owner; });
  W.log(`🚩 ${s.name} 被 ${W.facName(fac)} 攻占了！（原属 ${W.facName(oldFac)}）`);
  W.checkFactionAlive(oldFac);
};
W.checkFactionAlive = function (fac) {
  if (fac === 'player') return;
  const f = G.factions[fac];
  if (!f || !f.alive) return;
  if (G.settlements.some(s => s.fac === fac && s.type !== 'village')) return;
  f.alive = false;
  W.log(`☠ ${W.facName(fac)} 灭亡了！其领主们流亡他国。`);
  const others = D.FACTIONS.filter(x => G.factions[x.id].alive).map(x => x.id);
  G.lords.filter(l => l.fac === fac).forEach(l => {
    const p = l.partyId && W.P(l.partyId);
    if (p) W.removeParty(p);
    if (others.length) { l.fac = U.pick(others); l.isKing = false; l.respawn = G.time + 72; }
  });
  Object.keys(G.war[fac]).forEach(o => W.setWar(fac, o, false, true));
  if (G.player.liege === fac) { G.player.liege = null; G.player.status = 'free'; W.log('你效忠的国家已经灭亡，你重获自由身。'); }
};

// ---------------- 每日 ----------------
W.daily = function () {
  const pl = G.player, pp = W.player();
  // 食物
  const size = W.partySize(pp);
  if (!G.cheats.noFood) pl.foodAcc += size;
  let ate = true;
  while (pl.foodAcc >= D.SERVINGS_PER_FOOD) {
    const foods = D.GOODS.filter(g => g.food && pl.goods[g.id] > 0);
    if (!foods.length) { ate = false; pl.foodAcc = 0; break; }
    const g = foods[pl.foodType++ % foods.length];
    pl.goods[g.id]--; if (!pl.goods[g.id]) delete pl.goods[g.id];
    pl.foodAcc -= D.SERVINGS_PER_FOOD;
  }
  // 士气
  const variety = D.GOODS.filter(g => g.food && pl.goods[g.id] > 0).length;
  let target = 55 + pl.skills.leadership * 6 + (G.cheats.noFood ? 12 : variety * 4) - Math.floor(W.count(pp.troops) / 4) + (pl.recentWin || 0);
  if (!ate && !G.cheats.noFood) { target -= 40; W.log('⚠ 队伍断粮了！士气大幅下降。'); }
  if (pl.unpaid) target -= 25;
  pl.morale = U.clamp(Math.round(pl.morale + (target - pl.morale) * 0.3), 0, 100);
  pl.recentWin = Math.max(0, (pl.recentWin || 0) - 3);
  if (pl.morale < 20 && W.count(pp.troops) > 0) {
    const n = U.ri(1, Math.max(1, Math.floor(W.count(pp.troops) / 10)));
    for (let i = 0; i < n; i++) { const st = U.pick(pp.troops); if (st) W.removeTroops(pp.troops, st.id, 1); }
    W.log(`士气低落，有 ${n} 名士兵逃跑了。`);
  }
  // 伤员恢复
  const heal = 0.12 + W.pskill('firstaid') * 0.04;
  pp.troops.forEach(st => { if (st.w > 0) st.w = Math.max(0, st.w - Math.max(1, Math.round(st.w * heal))); });
  pl.hpFrac = Math.min(1, pl.hpFrac + 0.15 + W.pskill('firstaid') * 0.03);
  W.companions().forEach(h => (h.hpFrac = Math.min(1, h.hpFrac + 0.15 + W.pskill('firstaid') * 0.03)));
  // 教练
  if (W.pskill('trainer') > 0) pp.troops.forEach(st => (st.xp += W.pskill('trainer') * 4 * st.n));
  // AI 部队补员/升级
  G.parties.forEach(p => {
    if (p.kind !== 'lord') return;
    const l = W.L(p.lordId);
    const want = 18 + l.renown / 12 + (l.isKing ? 30 : 0);
    const inOwn = G.settlements.some(s => s.fac === p.fac && U.dist(s.x, s.y, p.x, p.y) < 160);
    if (inOwn && W.count(p.troops) < want) {
      const add = W.genTroops(p.fac, U.ri(1, p.inSettlement ? 6 : 3), Math.min(1, l.renown / 700));
      add.forEach(st => W.addTroops(p, st.id, st.n));
    }
    p.troops.forEach(st => { if (st.w) st.w = Math.max(0, st.w - Math.ceil(st.w * 0.2)); });
    if (U.chance(0.12)) W.upgradeRandom(p.troops);
  });
  // 领主复活
  G.lords.forEach(l => {
    if (!l.partyId && G.time >= l.respawn && G.factions[l.fac] && G.factions[l.fac].alive) W.spawnLordParty(l);
  });
  // 匪徒补充
  const bandits = G.parties.filter(p => p.kind === 'bandit').length;
  if (bandits < 30) for (let i = 0; i < 2; i++) W.spawnBandit();
  // 商队补充
  G.settlements.filter(s => s.type === 'town').forEach(s => {
    if (!G.parties.some(p => p.kind === 'caravan' && p.home === s.id) && U.chance(0.15)) W.spawnCaravan(s);
  });
  // 价格回归 & 村庄恢复
  G.settlements.forEach(s => {
    D.GOODS.forEach(g => { const b = W.basePriceMul(s, g.id); s.prices[g.id] += (b - s.prices[g.id]) * 0.06 + U.rr(-0.02, 0.02); });
    if (s.type === 'village') {
      if (s.looted && G.time > s.looted) s.looted = 0;
      if (!s.looted && s.recruits < 8 && U.chance(0.5)) s.recruits++;
    }
    if (s.restock && G.time > s.restock) W.restockShop(s);
  });
  W.siegeTick();
  W.factionAI();
  // 每周
  if (W.day() % 7 === 0) W.weekly();
};

W.upgradeRandom = function (list) {
  const st = U.pick(list.filter(s => D.TROOPS[s.id].up.length && s.n - s.w > 0));
  if (!st) return;
  const t = D.TROOPS[st.id];
  const k = Math.max(1, Math.floor(st.n * 0.2));
  W.removeTroops(list, st.id, k);
  W.addTroops(list, U.pick(t.up), k);
};

W.weeklyWages = function () {
  const pp = W.player();
  let w = pp.troops.reduce((a, st) => a + D.troopWage(D.TROOPS[st.id]) * st.n, 0);
  G.settlements.forEach(s => { if (s.owner === 'player' && s.type !== 'village') w += s.garrison.reduce((a, st) => a + D.troopWage(D.TROOPS[st.id]) * st.n, 0) * 0.5; });
  w += W.companions().reduce((a, h) => a + D.heroWage(h), 0);
  return Math.round(w * (1 - G.player.skills.leadership * 0.05));
};
W.fiefIncome = function (s) {
  if (s.type === 'village') return s.looted ? 0 : Math.round(80 + s.prosperity * 2.5);
  return Math.round((s.type === 'town' ? 450 : 150) + s.prosperity * (s.type === 'town' ? 6 : 2) + s.wealth * 0.2);
};
W.weekly = function () {
  const pl = G.player;
  let income = 0;
  G.settlements.forEach(s => {
    if (s.owner === 'player') income += W.fiefIncome(s);
    s.wealth = 0;
    // 驻军增长
    if (s.type !== 'village' && s.owner !== 'player' && s.fac !== 'player') {
      const cap = s.type === 'town' ? 140 : 80;
      if (W.count(s.garrison) < cap) W.genTroops(s.fac, U.ri(4, 9), 0.4).forEach(st => W.addTroops(s.garrison, st.id, st.n));
    } else if (s.type !== 'village' && s.owner === 'player' && W.count(s.garrison) < 30) {
      W.addTroops(s.garrison, s.cult + '_recruit', 2);
    }
  });
  if (pl.status === 'merc') income += W.mercPay();
  const towns = G.settlements.filter(s => s.type === 'town');
  (G.heroes || []).forEach(h => { if (!h.hired && U.chance(0.5)) h.where = U.pick(towns).id; });
  const wages = W.weeklyWages();
  pl.gold += income;
  if (pl.gold >= wages) { pl.gold -= wages; pl.unpaid = false; }
  else { pl.gold = 0; pl.unpaid = true; W.log('⚠ 你无力支付全部军饷，士兵们怨声载道。'); }
  W.log(`📜 周结算：收入 ${income} 金币，支付军饷 ${wages} 金币。`);
};

// ---------------- 势力外交 AI ----------------
W.factionAI = function () {
  const live = D.FACTIONS.filter(f => G.factions[f.id].alive).map(f => f.id);
  live.forEach(a => {
    const fa = G.factions[a];
    live.forEach(b => {
      if (a >= b) return;
      fa.warDays[b] = (fa.warDays[b] || 0) + 1;
      const days = fa.warDays[b];
      const atWar = G.war[a][b];
      const wars = live.filter(x => G.war[a][x]).length;
      if (atWar && days > 25 && U.chance(0.025 + wars * 0.01)) W.setWar(a, b, false);
      else if (!atWar && days > 20 && wars < 2 && U.chance(0.02)) W.setWar(a, b, true);
    });
    // 战役
    const enemies = live.filter(x => G.war[a][x]).concat(G.war[a].player ? ['player'] : []);
    if (fa.campaign) {
      const t = W.S(fa.campaign.target);
      if (!t || !W.hostile(a, t.fac) || G.time - fa.campaign.start > 24 * 12) fa.campaign = null;
    }
    if (!fa.campaign && enemies.length && U.chance(0.1)) {
      const myCenter = G.settlements.filter(s => s.fac === a && s.type !== 'village');
      if (!myCenter.length) return;
      const targets = G.settlements.filter(s => s.type !== 'village' && enemies.includes(s.fac));
      if (!targets.length) return;
      // 选择离自己领土最近的敌方据点
      let best = null, bd = 1e9;
      targets.forEach(t => {
        const d = Math.min(...myCenter.map(m => U.dist(m.x, m.y, t.x, t.y))) + W.defenseStrength(t) * 0.6;
        if (d < bd) { bd = d; best = t; }
      });
      const lords = G.lords.filter(l => l.fac === a && l.partyId && W.P(l.partyId)).map(l => l.id);
      const chosen = lords.filter(() => U.chance(0.7));
      if (chosen.length >= 2) {
        fa.campaign = { target: best.id, lords: chosen, start: G.time };
        W.log(`🎺 ${W.facName(a)} 集结大军，矛头直指 ${best.name}。`);
      }
    }
  });
  // 对玩家王国：偶尔求和或宣战
  if (G.player.status === 'king') {
    live.forEach(f => {
      if (G.war[f].player && U.chance(0.01)) W.setWar(f, 'player', false);
    });
  }
};

// ---------------- 玩家相关 ----------------
W.playerMoveTo = function (x, y, opts) {
  const pp = W.player();
  W.playerWaiting = false;
  pp.tx = U.clamp(x, 5, D.MAP_W - 5); pp.ty = U.clamp(y, 5, D.MAP_H - 5);
  pp.chase = opts && opts.chase || null;
  pp.chaseBattle = opts && opts.battle || null;
  pp.targetSettlement = opts && opts.settlement || null;
  pp.inSettlement = null;
};
W.xpForLevel = n => Math.round(300 * n * (n + 1) / 2 + 100 * n * n);
W.giveXp = function (xp) {
  const pl = G.player;
  pl.xp += Math.round(xp);
  let ups = 0;
  while (pl.xp >= W.xpForLevel(pl.level)) {
    pl.level++; pl.attrPts++; pl.skillPts++; ups++;
  }
  if (ups) W.log(`⭐ 你升到了 ${pl.level} 级！获得属性点与技能点。`);
};
W.addRenown = function (n) {
  n = Math.round(n);
  if (n <= 0) return;
  G.player.renown += n;
};
W.heroMaxHp = () => 60 + G.player.attrs.str + G.player.skills.ironflesh * 4 + G.player.level;

W.changeRel = function (fac, d) {
  if (!G.player.rel.hasOwnProperty(fac)) return;
  G.player.rel[fac] = U.clamp(G.player.rel[fac] + d, -100, 100);
};

// 玩家攻击某部队前的外交后果
W.declarePersonalWar = function (fac) {
  if (fac === 'bandit' || fac === 'neutral') return true;
  if (W.hostile(W.playerFac(), fac)) return true;
  if (G.player.liege) return false; // 有君主时不能私自开战
  W.setWar('player', fac, true);
  W.changeRel(fac, -15);
  return true;
};

// 玩家成为君主（独立攻占）
W.becomeKing = function () {
  if (G.player.status === 'king') return;
  G.player.status = 'king';
  G.player.liege = null;
  G.player.kingdomName = G.player.kingdomName || `${G.player.name}王国`;
  D.FAC.player.name = G.player.kingdomName;
  W.log(`👑 你建立了自己的国家：${G.player.kingdomName}！`);
};

W.checkVictory = function () {
  const f = W.playerFac();
  const towns = G.settlements.filter(s => s.type === 'town');
  return towns.every(s => s.fac === f);
};

// ---------------- 存档 ----------------
W.SAVE_PREFIX = 'steedsaga_save_';
W.save = function (slot) {
  const data = JSON.stringify(G);
  const ok = U.store.set(W.SAVE_PREFIX + slot, data);
  U.store.set(W.SAVE_PREFIX + slot + '_meta', JSON.stringify({ name: G.player.name, level: G.player.level, date: W.dateStr(), real: Date.now() }));
  return ok;
};
W.load = function (slot) {
  const data = U.store.get(W.SAVE_PREFIX + slot);
  if (!data) return false;
  G = JSON.parse(data);
  W.genTerrain(G.seed);
  if (G.player.kingdomName) D.FAC.player.name = G.player.kingdomName;
  if (!G.heroes) W.initHeroes();
  if (!G.cheats) G.cheats = {};
  if (!G.battleSpeed) G.battleSpeed = 0.7;
  W.pendingEvent = null;
  return true;
};
W.saveMeta = function (slot) {
  try { return JSON.parse(U.store.get(W.SAVE_PREFIX + slot + '_meta')); } catch (e) { return null; }
};
