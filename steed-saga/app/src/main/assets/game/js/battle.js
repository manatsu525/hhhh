'use strict';
// ============ 实时战斗引擎（俯视角，骑砍式战斗） ============
const B = {
  active: false, units: [], proj: [], fx: [], texts: [], feed: [], obstacles: [], walls: [],
  input: { mx: 0, my: 0, attack: false, attackPressed: false, block: false, switchPressed: false, aimActive: false, ax: 0, ay: 0 },
  orders: [{ inf: 'hold', arc: 'hold', cav: 'hold' }], sel: 'all', timeScale: 1, zoom: 1,
};
B.TEAM_COLORS = ['#3d7be0', '#d23c3c', '#e0a030', '#30b070', '#a050d0', '#30b0c0', '#d070a0', '#909030'];
B.DT = { cut: { soak: 0.5, red: 0.011 }, pierce: { soak: 0.33, red: 0.008 }, blunt: { soak: 0.2, red: 0.006 } };
B.PROJ_SPEED = { bow: 650, xbow: 760, throw: 430 };

B.BIOME_GROUND = {
  plains: ['#6f9a48', '#5f8a3e', '#7fae58'], forest: ['#4c7436', '#3f652e', '#5a8442'], snow: ['#e6ecef', '#d5dde2', '#f3f6f8'],
  steppe: ['#a8a85a', '#999a4f', '#b8b46a'], desert: ['#dcc189', '#cfb27a', '#e6cd98'], hills: ['#8c9058', '#7d8150', '#9aa064'],
  arena: ['#c9a874', '#b99766', '#d6b886'], siege: ['#7b8a52', '#6d7b48', '#8a985c'],
};

// ---------------- 启动 ----------------
// cfg: { mode, biome, teams:[[{troopId, owner}], ...], onEnd, title, siegeAttacker }
B.start = function (cfg) {
  B.cfg = cfg;
  B.mode = cfg.mode || 'field';
  B.units = []; B.proj = []; B.fx = []; B.texts = []; B.feed = []; B.obstacles = []; B.walls = []; B.gaps = [];
  B.time = 0; B.over = false; B.timeScale = 1; B.result = null; B.playerDown = false; B.kills = 0; B.killList = [];
  B.orders = [{ inf: 'hold', arc: 'hold', cav: 'hold' }, null];
  B.anchors = [{}, {}];
  B.sel = 'all';
  B.input = { mx: 0, my: 0, attack: false, attackPressed: false, block: false, switchPressed: false, aimActive: false, ax: 0, ay: 0 };
  B.heroDown = {}; B.heroKills = {};
  B.nextId = 1;
  B.FW = B.mode === 'arena' ? 760 : 1700; B.FH = B.mode === 'arena' ? 760 : B.mode === 'siege' ? 960 : 1100;
  B.fallen = []; B.escaped = [];
  B.buildGround(cfg.biome || 'plains');
  // 部署
  const nTeams = cfg.teams.length;
  B.queues = cfg.teams.map(t => t.slice());
  B.initial = cfg.teams.map(t => t.length + 0);
  B.initial[0] += 1;
  const total = B.queues.reduce((a, q) => a + q.length, 0) + 1;
  const size = G.battleSize || 70;
  B.maxField = [];
  if (B.mode === 'arena') B.maxField = cfg.teams.map(() => 99);
  else {
    let s0 = Math.round(size * U.clamp((B.queues[0].length + 1) / total, 0.3, 0.7));
    let s1 = size - s0;
    if (B.queues[1].length < s1) { s0 += s1 - B.queues[1].length; s1 = B.queues[1].length; }
    if (B.queues[0].length + 1 < s0) { s1 += s0 - B.queues[0].length - 1; s0 = B.queues[0].length + 1; }
    B.maxField = [s0, s1];
  }
  // 玩家
  const pl = B.makePlayer();
  B.player = pl;
  if (B.mode === 'arena') {
    pl.x = B.FW / 2; pl.y = B.FH / 2 + 260; pl.face = -Math.PI / 2;
    for (let t = 1; t < nTeams; t++) {
      const a = -Math.PI / 2 + (t / nTeams) * Math.PI * 2;
      B.queues[t].forEach(d => B.addUnit(B.makeArenaUnit(d, t, B.FW / 2 + Math.cos(a + Math.PI) * 260, B.FH / 2 + Math.sin(a + Math.PI) * 260)));
      B.queues[t] = [];
    }
  } else {
    B.spawnInitial(0, B.maxField[0] - 1);
    B.spawnInitial(1, B.maxField[1]);
    const sp = B.spawnPoint(0);
    pl.x = sp.x; pl.y = sp.y + (pl.horse ? 60 : 40); pl.face = sp.face; pl.aim = sp.face;
  }
  B.addUnit(pl);
  (cfg.heroes || []).forEach((h, i) => {
    const u = B.makeCompanion(h);
    const sp = B.spawnPoint(0);
    u.x = pl.x + (i % 2 ? 1 : -1) * (30 + Math.floor(i / 2) * 26); u.y = pl.y + 14; u.face = sp.face; u.aim = sp.face;
    B.addUnit(u);
  });
  B.cam = { x: pl.x, y: pl.y };
  B.active = true;
  B.msg(cfg.title || '战斗开始！', 3);
  if (B.mode !== 'arena') B.msg('部队在原地待命，点击上方「冲锋」下达命令', 5);
  BUI.show();
};

B.spawnPoint = function (team) {
  const FW = B.FW, FH = B.FH;
  if (B.mode === 'siege') {
    const atk = B.cfg.siegeAttacker === team;
    return atk ? { x: FW / 2, y: B.wallBot + 170, face: -Math.PI / 2 } : { x: FW / 2, y: 250, face: Math.PI / 2 };
  }
  return team === 0 ? { x: FW / 2, y: FH - 170, face: -Math.PI / 2 } : { x: FW / 2, y: 170, face: Math.PI / 2 };
};

B.spawnInitial = function (team, n) {
  const sp = B.spawnPoint(team);
  const take = B.queues[team].splice(0, Math.max(0, n));
  const groups = { inf: [], arc: [], cav: [] };
  take.forEach(d => { const u = B.makeTroopUnit(d.troopId, team, d.owner); groups[u.group].push(u); });
  const dir = sp.face, fx = Math.cos(dir), fy = Math.sin(dir), rx = -fy, ry = fx;
  const place = (list, back, lateral, spacing) => {
    const width = Math.max(1, Math.ceil(Math.sqrt(list.length * 3)));
    list.forEach((u, i) => {
      const row = Math.floor(i / width), col = i % width;
      const lat = lateral + (col - (Math.min(width, list.length) - 1) / 2) * spacing;
      const bk = back + row * spacing;
      u.x = sp.x + rx * lat - fx * bk + U.rr(-3, 3);
      u.y = sp.y + ry * lat - fy * bk + U.rr(-3, 3);
      u.face = dir; u.aim = dir;
      B.addUnit(u);
    });
  };
  if (B.mode === 'siege' && B.cfg.siegeAttacker !== team) {
    // 守军：远程上城墙，步兵守梯口
    const wallTop = B.wallTop;
    groups.arc.forEach((u, i) => {
      u.x = 60 + ((i * 97) % (B.FW - 120)); u.y = wallTop - 14; u.face = Math.PI / 2; u.aim = u.face; u.holdX = u.x; u.holdY = u.y; B.addUnit(u);
    });
    groups.inf.concat(groups.cav).forEach((u, i) => {
      const g = B.gaps[i % B.gaps.length];
      u.x = g + U.rr(-40, 40); u.y = wallTop - 50 - Math.floor(i / B.gaps.length) * 18; u.face = Math.PI / 2; u.aim = u.face;
      u.holdX = u.x; u.holdY = u.y; B.addUnit(u);
    });
    return;
  }
  place(groups.inf, 0, 0, 22);
  place(groups.arc, -60, 0, 20);
  place(groups.cav.slice(0, Math.ceil(groups.cav.length / 2)), 30, -260, 34);
  place(groups.cav.slice(Math.ceil(groups.cav.length / 2)), 30, 260, 34);
};

B.reinforce = function () {
  for (let team = 0; team < 2; team++) {
    if (!B.queues[team] || !B.queues[team].length) continue;
    const alive = B.units.filter(u => u.team === team && u.alive && !u.rout).length;
    if (alive > B.maxField[team] * 0.4) continue;
    const n = Math.min(B.queues[team].length, B.maxField[team] - alive);
    if (n <= 0) continue;
    const sp = B.spawnPoint(team);
    const list = B.queues[team].splice(0, n);
    list.forEach((d, i) => {
      const u = B.makeTroopUnit(d.troopId, team, d.owner);
      u.x = U.clamp(sp.x + (i % 12 - 6) * 26 + U.rr(-5, 5), 30, B.FW - 30);
      u.y = sp.y + (team === 0 || (B.mode === 'siege' && B.cfg.siegeAttacker === team) ? 1 : -1) * (60 + Math.floor(i / 12) * 26);
      if (B.mode === 'siege' && B.cfg.siegeAttacker !== team) { u.y = B.wallTop - 120 - Math.floor(i / 12) * 20; u.holdX = u.x; u.holdY = B.wallTop - 50; }
      u.face = sp.face; u.aim = sp.face;
      B.addUnit(u);
    });
    B.msg(team === 0 ? '我方援军抵达！' : '敌方援军抵达！', 2);
  }
};

// ---------------- 单位 ----------------
B.armorOf = function (eq) {
  const a = k => (eq[k] && D.ITEMS[eq[k]] ? D.ITEMS[eq[k]].armor : 0);
  return { h: a('h'), b: a('b'), l: a('l') };
};
B.baseUnit = function (team) {
  return {
    id: B.nextId++, team, x: 0, y: 0, vx: 0, vy: 0, face: 0, aim: 0, sp: 0, alive: true, state: 'idle', st: 0,
    target: null, think: Math.random() * 0.4, rout: false, couch: false, bumpCd: 0, stun: 0, hitCd: {}, lastAtk: 0,
    aimT: 0, reloadT: 0, loaded: true,
  };
};
B.makeTroopUnit = function (troopId, team, owner) {
  const t = D.TROOPS[troopId];
  const st = D.troopStats(t);
  const u = B.baseUnit(team);
  const eq = t.eq;
  u.troopId = troopId; u.owner = owner; u.name = t.name; u.tier = t.tier;
  u.melee = D.ITEMS[eq.m] || D.ITEMS.knife;
  u.ranged = eq.r ? D.ITEMS[eq.r] : null;
  u.shield = eq.s && u.melee.hands === 1 ? D.ITEMS[eq.s] : null;
  u.shieldHp = u.shield ? u.shield.hp : 0;
  u.horse = eq.horse && B.mode !== 'siege' ? D.ITEMS[eq.horse] : null;
  u.horseHp = u.horse ? u.horse.hp : 0;
  u.armor = B.armorOf(eq);
  u.hp = u.maxHp = st.hp;
  u.skill = t.tier;
  u.kind = t.kind;
  u.group = u.horse ? 'cav' : (t.kind === 'arc' || t.kind === 'ha') && u.ranged ? 'arc' : 'inf';
  if (t.kind === 'ha' && u.horse) u.group = 'cav';
  u.prefRanged = (t.kind === 'arc' || t.kind === 'ha') && !!u.ranged;
  u.ammo = u.ranged ? u.ranged.ammo : 0;
  u.mode = u.prefRanged && B.canUseRanged(u) ? 'ranged' : 'melee';
  u.speedFoot = 68 + t.tier * 2.5 - (u.armor.b >= 40 ? 6 : 0);
  u.dmgMul = 1 + t.tier * 0.06;
  u.rangedMul = 1 + t.tier * 0.06;
  u.atkSpd = 1 + t.tier * 0.03;
  u.maneuver = u.horse ? u.horse.man : 0;
  u.hsp = u.horse ? u.horse.spd : 0;
  u.fac = t.fac;
  return u;
};
B.makePlayer = function () {
  const pl = G.player, eq = pl.eq;
  const u = B.baseUnit(0);
  u.isPlayer = true; u.name = pl.name; u.owner = 'player'; u.tier = 5;
  if (B.mode === 'arena') {
    const kit = B.arenaKit();
    Object.assign(u, kit);
  } else {
    u.melee = D.ITEMS[eq.melee] || D.ITEMS.knife;
    u.ranged = eq.ranged ? D.ITEMS[eq.ranged] : null;
    u.shield = eq.shield && u.melee.hands === 1 ? D.ITEMS[eq.shield] : null;
    u.horse = eq.horse && B.mode !== 'siege' ? D.ITEMS[eq.horse] : null;
    u.armor = { h: eq.head ? D.ITEMS[eq.head].armor : 0, b: eq.body ? D.ITEMS[eq.body].armor : 0, l: eq.legs ? D.ITEMS[eq.legs].armor : 0 };
  }
  u.shieldHp = u.shield ? u.shield.hp : 0;
  u.horseHp = u.horse ? u.horse.hp : 0;
  u.maxHp = W.heroMaxHp();
  u.hp = B.mode === 'arena' ? u.maxHp : Math.max(u.maxHp * 0.35, u.maxHp * pl.hpFrac);
  u.skill = 3 + pl.attrs.agi / 8;
  u.group = 'hero';
  u.ammo = u.ranged ? u.ranged.ammo : 0;
  u.mode = 'melee';
  u.speedFoot = 80 * (1 + pl.skills.athletics * 0.04 + pl.attrs.agi * 0.004);
  u.dmgMul = 1 + pl.skills.powerstrike * 0.08 + pl.attrs.str * 0.005;
  u.rangedMul = 1 + pl.skills.powerdraw * 0.1;
  u.atkSpd = 1 + pl.attrs.agi * 0.01;
  u.maneuver = u.horse ? u.horse.man * (1 + pl.skills.riding * 0.05) : 0;
  u.hsp = u.horse ? u.horse.spd * (1 + pl.skills.riding * 0.02) : 0;
  return u;
};
B.makeCompanion = function (h) {
  const eq = h.eq;
  const u = B.baseUnit(0);
  u.heroId = h.id; u.name = h.name; u.owner = 'hero:' + h.id; u.tier = 5; u.fac = null;
  u.melee = D.ITEMS[eq.melee] || D.ITEMS.knife;
  u.ranged = eq.ranged ? D.ITEMS[eq.ranged] : null;
  u.shield = eq.shield && u.melee.hands === 1 ? D.ITEMS[eq.shield] : null;
  u.shieldHp = u.shield ? u.shield.hp : 0;
  u.horse = eq.horse && B.mode !== 'siege' ? D.ITEMS[eq.horse] : null;
  u.horseHp = u.horse ? u.horse.hp : 0;
  u.armor = { h: eq.head ? D.ITEMS[eq.head].armor : 0, b: eq.body ? D.ITEMS[eq.body].armor : 0, l: eq.legs ? D.ITEMS[eq.legs].armor : 0 };
  u.maxHp = W.heroMaxHpOf(h);
  u.hp = Math.max(u.maxHp * 0.35, u.maxHp * h.hpFrac);
  u.skill = Math.min(6, 2 + h.level / 4);
  u.kind = u.horse && u.ranged && u.ranged.mount ? 'ha' : u.ranged && !eq.shield && !u.horse ? 'arc' : u.horse ? 'cav' : 'inf';
  u.group = u.horse ? 'cav' : u.kind === 'arc' ? 'arc' : 'inf';
  u.prefRanged = (u.kind === 'arc' || u.kind === 'ha') && !!u.ranged;
  u.ammo = u.ranged ? u.ranged.ammo : 0;
  u.mode = u.prefRanged && B.canUseRanged(u) ? 'ranged' : 'melee';
  u.speedFoot = 76 * (1 + h.skills.athletics * 0.04);
  u.dmgMul = 1 + h.skills.powerstrike * 0.08 + h.attrs.str * 0.005;
  u.rangedMul = 1 + h.skills.powerdraw * 0.1;
  u.atkSpd = 1 + h.attrs.agi * 0.01;
  u.maneuver = u.horse ? u.horse.man * (1 + h.skills.riding * 0.05) : 0;
  u.hsp = u.horse ? u.horse.spd * (1 + h.skills.riding * 0.02) : 0;
  u.haSkill = h.skills.horsearchery;
  return u;
};
B.arenaKit = function () {
  const kits = [
    { melee: D.ITEMS.wood_sword, shield: D.ITEMS.arena_shield },
    { melee: D.ITEMS.wood_staff },
    { melee: D.ITEMS.wood_sword, ranged: D.ITEMS.arena_bow },
  ];
  const k = U.pick(kits);
  return { melee: k.melee, ranged: k.ranged || null, shield: k.shield || null, horse: null, armor: { h: 10, b: 14, l: 6 } };
};
B.makeArenaUnit = function (d, team, x, y) {
  const u = B.baseUnit(team);
  Object.assign(u, B.arenaKit());
  u.troopId = null; u.owner = null; u.name = d.name || '角斗士'; u.tier = d.tier || 3;
  u.shieldHp = u.shield ? u.shield.hp : 0;
  u.hp = u.maxHp = 50 + u.tier * 8; u.skill = u.tier; u.group = 'inf'; u.kind = 'inf';
  u.prefRanged = !!u.ranged; u.ammo = u.ranged ? u.ranged.ammo : 0; u.mode = u.prefRanged ? 'ranged' : 'melee';
  u.speedFoot = 72 + u.tier * 2; u.dmgMul = 1 + u.tier * 0.05; u.rangedMul = 1; u.atkSpd = 1;
  u.x = x; u.y = y; u.face = Math.atan2(B.FH / 2 - y, B.FW / 2 - x); u.aim = u.face;
  return u;
};
B.addUnit = function (u) { u.r = u.horse ? 12 : 9; B.units.push(u); };
B.canUseRanged = u => u.ranged && u.ammo > 0 && (!u.horse || u.ranged.mount);
B.hostile = (a, b) => a.team !== b.team;

// ---------------- 战场生成 ----------------
B.buildGround = function (biome) {
  const pal = B.BIOME_GROUND[B.mode === 'arena' ? 'arena' : biome] || B.BIOME_GROUND.plains;
  const S = 0.5; // 地面画布：每世界单位 0.5 像素
  const cv = document.createElement('canvas');
  cv.width = Math.ceil(B.FW * S); cv.height = Math.ceil(B.FH * S);
  const ctx = cv.getContext('2d');
  ctx.scale(S, S);
  ctx.fillStyle = pal[0]; ctx.fillRect(0, 0, B.FW, B.FH);
  for (let i = 0; i < 380; i++) {
    ctx.fillStyle = U.pick(pal) + '88';
    ctx.beginPath(); ctx.ellipse(U.rr(0, B.FW), U.rr(0, B.FH), U.rr(20, 90), U.rr(15, 60), U.rr(0, 3), 0, 7); ctx.fill();
  }
  for (let i = 0; i < 1500; i++) {
    ctx.strokeStyle = 'rgba(0,0,0,0.08)'; ctx.lineWidth = 2;
    const x = U.rr(0, B.FW), y = U.rr(0, B.FH);
    ctx.beginPath(); ctx.moveTo(x, y); ctx.lineTo(x + U.rr(-3, 3), y - U.rr(4, 9)); ctx.stroke();
  }
  B.ground = cv; B.gctx = ctx; B.groundScale = S;
  // 障碍物
  if (B.mode === 'arena') {
    B.arenaR = 340;
  } else if (B.mode === 'siege') {
    B.wallTop = 420; B.wallBot = 452;
    B.gaps = [B.FW * 0.3, B.FW * 0.5, B.FW * 0.7];
    // 城墙分段（梯子处留出缺口）
    let x0 = 0;
    B.gaps.concat([B.FW + 40]).forEach(g => {
      const x1 = g - 22;
      if (x1 > x0) B.walls.push({ x: x0, y: B.wallTop, w: x1 - x0, h: B.wallBot - B.wallTop });
      x0 = g + 22;
    });
    // 城内建筑
    ctx.fillStyle = '#8f8a7e'; ctx.fillRect(0, 0, B.FW, B.wallTop);
    for (let i = 0; i < 400; i++) { ctx.fillStyle = `rgba(${U.ri(90, 130)},${U.ri(85, 120)},${U.ri(75, 105)},0.5)`; ctx.fillRect(U.rr(0, B.FW), U.rr(0, B.wallTop), U.rr(10, 30), U.rr(6, 16)); }
    for (let i = 0; i < 6; i++) {
      const bx = 80 + i * 280 + U.rr(-20, 20), by = U.rr(40, 140);
      const o = { x: bx, y: by, w: U.rr(90, 150), h: U.rr(60, 90), house: true };
      B.walls.push(o);
    }
  } else {
    const nTrees = biome === 'forest' ? 70 : biome === 'plains' ? 18 : biome === 'snow' ? 26 : biome === 'hills' ? 10 : 3;
    const nRocks = biome === 'hills' ? 22 : biome === 'desert' ? 10 : 6;
    for (let i = 0; i < nTrees; i++) B.obstacles.push({ x: U.rr(80, B.FW - 80), y: U.rr(320, B.FH - 320), r: U.rr(14, 22), tree: true, snow: biome === 'snow' });
    for (let i = 0; i < nRocks; i++) B.obstacles.push({ x: U.rr(60, B.FW - 60), y: U.rr(300, B.FH - 300), r: U.rr(10, 20), rock: true });
    B.obstacles.forEach(o => {
      if (o.rock) { ctx.fillStyle = '#7d7a72'; ctx.beginPath(); ctx.arc(o.x, o.y, o.r, 0, 7); ctx.fill(); ctx.fillStyle = '#9a978f'; ctx.beginPath(); ctx.arc(o.x - o.r * 0.25, o.y - o.r * 0.25, o.r * 0.6, 0, 7); ctx.fill(); }
      else { ctx.fillStyle = 'rgba(0,0,0,0.25)'; ctx.beginPath(); ctx.arc(o.x + 8, o.y + 8, o.r * 1.6, 0, 7); ctx.fill(); ctx.fillStyle = '#5a3c22'; ctx.beginPath(); ctx.arc(o.x, o.y, o.r * 0.45, 0, 7); ctx.fill(); }
    });
  }
};

// ---------------- 主循环 ----------------
B.update = function (dtReal) {
  if (!B.active || B.paused) return;
  const steps = B.timeScale > 1 ? B.timeScale : 1;
  for (let s = 0; s < steps; s++) {
    let dt = Math.min(dtReal, 0.05) * (G.battleSpeed || 0.7);
    B.step(dt);
    if (!B.active) return;
  }
};
B.step = function (dt) {
  B.time += dt;
  const pl = B.player;
  if (pl.alive) B.controlPlayer(pl, dt);
  for (const u of B.units) {
    if (!u.alive) continue;
    if (u.stun > 0) u.stun -= dt;
    if (u.bumpCd > 0) u.bumpCd -= dt;
    if (!u.isPlayer) B.ai(u, dt);
    B.updateAction(u, dt);
  }
  B.physics(dt);
  B.updateProjectiles(dt);
  B.updateFx(dt);
  if (Math.floor(B.time) !== Math.floor(B.time - dt)) { B.reinforce(); B.moraleCheck(); B.checkEnd(); }
  B.input.attackPressed = false; B.input.switchPressed = false;
};

// ---------------- 玩家控制 ----------------
B.controlPlayer = function (u, dt) {
  const inp = B.input;
  const mag = Math.min(1, Math.hypot(inp.mx, inp.my));
  const dir = Math.atan2(inp.my, inp.mx);
  if (inp.switchPressed && (u.state === 'idle' || u.state === 'aim')) {
    if (u.mode === 'melee' && B.canUseRanged(u)) { u.mode = 'ranged'; B.msg(`切换为 ${u.ranged.name}`, 1); }
    else if (u.mode === 'ranged') { u.mode = 'melee'; B.msg(`切换为 ${u.melee.name}`, 1); }
    else if (u.ranged && u.horse && !u.ranged.mount) B.msg('该远程武器不能在马上使用', 1.5);
    else if (u.ranged && !u.ammo) B.msg('弹药耗尽', 1.5);
    u.state = 'switch'; u.st = 0.35; u.aimT = 0;
  }
  // 移动
  if (u.horse) {
    const want = mag > 0.15 ? u.hsp * mag : 0;
    if (mag > 0.15) u.face = U.rotTo(u.face, dir, u.maneuver * dt * (u.state === 'block' ? 0.7 : 1));
    B.horseSpeed(u, want, dt);
  } else {
    const slow = u.state === 'block' ? 0.5 : u.state === 'aim' ? 0.45 : u.state === 'windup' ? 0.75 : 1;
    const v = u.speedFoot * slow * (mag > 0.15 ? mag : 0);
    const tvx = Math.cos(dir) * v, tvy = Math.sin(dir) * v;
    u.vx += (tvx - u.vx) * Math.min(1, dt * 10); u.vy += (tvy - u.vy) * Math.min(1, dt * 10);
    if (mag > 0.15 && u.state !== 'windup' && u.state !== 'aim') u.face = U.rotTo(u.face, dir, dt * 12);
    if (u.state === 'idle' || u.state === 'block') u.aim = u.face;
  }
  if (u.horse && (u.state === 'idle' || u.state === 'block')) u.aim = U.rotTo(u.aim, u.face, dt * 6);
  // 格挡
  if (inp.block && u.state === 'idle') { u.state = 'block'; }
  if (!inp.block && u.state === 'block') u.state = 'idle';
  // 攻击
  if (u.mode === 'melee') {
    u.couch = false;
    const fast = u.horse && u.sp > u.hsp * 0.55;
    if (inp.attack && u.melee.couch && fast && (u.state === 'idle' || u.state === 'block')) {
      u.couch = true; u.state = 'idle'; u.aim = u.face;
    } else if (inp.attackPressed && u.state === 'idle') {
      const want = inp.aimActive ? Math.atan2(inp.ay, inp.ax) : u.face;
      const t = B.pickTarget(u, u.melee.reach * 1.8 + 20, inp.aimActive ? 0.9 : u.horse ? 2.6 : 1.25, want);
      if (t) { u.atkTarget = t; u.aim = Math.atan2(t.y - u.y, t.x - u.x); if (!u.horse) u.face = u.aim; }
      else { u.atkTarget = null; u.aim = u.face; }
      B.beginSwing(u);
    }
  } else {
    // 远程：按住瞄准，松开发射
    if (inp.attack && (u.state === 'idle' || u.state === 'aim')) {
      if (u.state === 'idle') { u.state = 'aim'; u.aimT = 0; }
      u.aimT += dt;
      // 拖动攻击键可向任意方向瞄准；骑乘时不拖动则自动瞄准四周最近的敌人
      const want = inp.aimActive ? Math.atan2(inp.ay, inp.ax) : null;
      const t = want != null ? B.pickTarget(u, u.ranged.range, 0.45, want)
        : u.horse ? B.pickTarget(u, u.ranged.range, Math.PI, u.aim) : B.pickTarget(u, u.ranged.range, 0.75, u.face);
      u.aimTarget = t;
      if (t) u.aim = U.rotTo(u.aim, B.leadAngle(u, t), dt * 9);
      else if (want != null) u.aim = U.rotTo(u.aim, want, dt * 10);
      else if (!u.horse) u.aim = u.face;
      if (!u.horse) u.face = u.aim;
    } else if (!inp.attack && u.state === 'aim') {
      const need = u.ranged.kind === 'xbow' ? 0.15 : 0.3;
      if (u.aimT >= need && u.loaded) B.shoot(u, U.clamp(u.aimT / (u.ranged.kind === 'xbow' ? 0.5 : u.ranged.reload * 0.75), 0.3, 1));
      u.state = 'idle'; u.aimTarget = null;
    }
  }
};
B.horseSpeed = function (u, want, dt) {
  const acc = want > u.sp ? 130 : 210;
  u.sp += U.clamp(want - u.sp, -acc * dt, acc * dt);
  u.vx = Math.cos(u.face) * u.sp; u.vy = Math.sin(u.face) * u.sp;
};
// 根据朝向挑选最佳目标
B.pickTarget = function (u, range, arc, face) {
  let best = null, bs = 1e9;
  for (const o of B.units) {
    if (!o.alive || !B.hostile(u, o)) continue;
    const d = Math.hypot(o.x - u.x, o.y - u.y) - o.r;
    if (d > range) continue;
    const a = Math.abs(U.angDiff(face, Math.atan2(o.y - u.y, o.x - u.x)));
    if (a > arc) continue;
    const score = d + a * 90;
    if (score < bs) { bs = score; best = o; }
  }
  return best;
};
B.leadAngle = function (u, t, lead) {
  const spd = B.PROJ_SPEED[u.ranged.kind];
  const d = Math.hypot(t.x - u.x, t.y - u.y);
  const tt = d / spd;
  const k = lead == null ? 0.9 : lead;
  return Math.atan2(t.y + t.vy * tt * k - u.y, t.x + t.vx * tt * k - u.x);
};

// ---------------- 动作 ----------------
B.beginSwing = function (u) {
  u.state = 'windup';
  u.st = (u.isPlayer ? 0.3 : 0.42) / (u.melee.spd * u.atkSpd);
  u.swingDur = u.st;
  // 通知目标（AI 格挡判定）
  const t = u.atkTarget;
  if (t && !t.isPlayer && t.alive && t.state === 'idle' && t.stun <= 0) {
    const p = t.shield && t.mode === 'melee' ? 0.32 + t.skill * 0.08 : 0.06 + t.skill * 0.05;
    if (Math.random() < p) { t.state = 'block'; t.st = u.st + 0.25; t.face = t.horse ? t.face : Math.atan2(u.y - t.y, u.x - t.x); t.aim = Math.atan2(u.y - t.y, u.x - t.x); }
  }
};
B.updateAction = function (u, dt) {
  if (u.state === 'windup') {
    if (u.atkTarget && u.atkTarget.alive) {
      const a = Math.atan2(u.atkTarget.y - u.y, u.atkTarget.x - u.x);
      u.aim = U.rotTo(u.aim, a, dt * 6);
      if (!u.horse) u.face = u.aim;
    }
    u.st -= dt;
    if (u.st <= 0) { B.strike(u); u.state = 'recover'; u.st = 0.28 / (u.melee.spd * u.atkSpd); }
  } else if (u.state === 'recover' || u.state === 'switch') {
    u.st -= dt;
    if (u.st <= 0) u.state = 'idle';
  } else if (u.state === 'block' && !u.isPlayer) {
    u.st -= dt;
    if (u.st <= 0) u.state = 'idle';
  } else if (u.state === 'reload') {
    u.st -= dt;
    if (u.st <= 0) { u.state = 'idle'; u.loaded = true; }
  }
  // 骑枪冲刺命中
  if (u.couch && u.horse) {
    for (const o of B.units) {
      if (!o.alive || !B.hostile(u, o)) continue;
      const dx = o.x - u.x, dy = o.y - u.y, d = Math.hypot(dx, dy);
      if (d > u.melee.reach + o.r) continue;
      if (Math.abs(U.angDiff(u.face, Math.atan2(dy, dx))) > 0.38) continue;
      if ((u.hitCd[o.id] || 0) > B.time) continue;
      u.hitCd[o.id] = B.time + 1.2;
      const rel = Math.max(0, (u.vx - o.vx) * dx / d + (u.vy - o.vy) * dy / d);
      B.applyMelee(u, o, u.melee.dmg * 1.5 * (1 + rel / 85), true);
    }
  }
};
B.strike = function (u) {
  // 在攻击方向弧内寻找最近的敌人
  let best = null, bd = 1e9;
  const arc = u.horse ? 0.9 : 0.85;
  for (const o of B.units) {
    if (!o.alive || !B.hostile(u, o)) continue;
    const dx = o.x - u.x, dy = o.y - u.y;
    const d = Math.hypot(dx, dy) - o.r - u.r * 0.4;
    if (d > u.melee.reach) continue;
    const a = Math.abs(U.angDiff(u.aim, Math.atan2(dy, dx)));
    if (a > arc) continue;
    const sc = d + a * 40;
    if (sc < bd) { bd = sc; best = o; }
  }
  if (!best) { if (u.isPlayer) B.sound('whoosh'); return; }
  const dx = best.x - u.x, dy = best.y - u.y, d = Math.hypot(dx, dy) || 1;
  const rel = Math.max(0, (u.vx - best.vx) * dx / d + (u.vy - best.vy) * dy / d);
  B.applyMelee(u, best, u.melee.dmg * (1 + rel / 220), false);
};
B.isBlocking = function (o, fromX, fromY) {
  if (o.state !== 'block') return false;
  const a = Math.abs(U.angDiff(o.aim, Math.atan2(fromY - o.y, fromX - o.x)));
  return a < (o.shield && o.mode === 'melee' ? 1.25 : 0.8);
};
B.applyMelee = function (u, o, raw, couched) {
  // 格挡
  if (!couched && B.isBlocking(o, u.x, u.y)) {
    if (o.shield && o.mode === 'melee') {
      const red = o.isPlayer ? 1 - G.player.skills.shield * 0.08 : 1;
      o.shieldHp -= raw * red;
      if (o.shieldHp <= 0) { o.shield = null; B.text(o.x, o.y - 20, '盾牌破碎!', '#ffb050'); }
    }
    B.spark(o.x + Math.cos(o.aim) * 10, o.y + Math.sin(o.aim) * 10);
    if (u.isPlayer || o.isPlayer) B.text(o.x, o.y - 18, '格挡', '#e0e8ff');
    u.state = 'recover'; u.st = 0.45;
    return;
  }
  // 骑兵目标：步兵攻击者有概率击中马匹
  if (o.horse && !u.horse && Math.random() < 0.35) {
    B.damageHorse(o, raw * u.dmgMul * U.rr(0.85, 1.15), u);
    return;
  }
  B.damage(o, raw * u.dmgMul, u.melee.dt, u);
};
B.damage = function (o, raw, dt, src) {
  raw *= U.rr(0.85, 1.15);
  const r = Math.random();
  let arm, mul = 1;
  if (r < 0.15) { arm = o.armor.h; mul = 1.25; } else if (r < 0.8) arm = o.armor.b; else { arm = o.armor.l; mul = 0.85; }
  const k = B.DT[dt] || B.DT.cut;
  let eff = Math.max(0, raw - arm * k.soak * U.rr(0.5, 1)) * (1 - Math.min(0.65, arm * k.red * U.rr(0.6, 1)));
  eff = Math.max(dt === 'blunt' ? 2 : 1, eff * mul) * B.dmgTakenMul(o);
  o.hp -= eff;
  if (src && src.isPlayer) B.text(o.x, o.y - 16, '' + Math.round(eff), '#ffe070');
  if (o.isPlayer && eff > 0) B.text(o.x, o.y - 16, '-' + Math.max(1, Math.round(eff)), '#ff6060');
  B.blood(o.x, o.y, eff);
  // 受击打断
  if (o.state === 'windup' && eff > 6) { o.state = 'recover'; o.st = 0.25; }
  if (o.state === 'aim' && eff > 6) { o.state = 'idle'; o.aimT = 0; }
  if (o.hp <= 0) B.fall(o, dt, src);
};
// 玩家只受 1/4 伤害，玩家部队（含同伴）只受 1/2 伤害
B.dmgTakenMul = function (o) {
  if (o.isPlayer) return G.cheats && G.cheats.god ? 0 : 0.25;
  if (o.team === 0 && (o.owner === 'player' || o.heroId)) return 0.5;
  return 1;
};
B.damageHorse = function (o, raw, src) {
  const eff = Math.max(1, raw - o.horse.armor * 0.4) * (1 - Math.min(0.5, o.horse.armor * 0.008)) * B.dmgTakenMul(o);
  o.horseHp -= eff;
  B.blood(o.x, o.y, eff);
  if (src && src.isPlayer) B.text(o.x, o.y - 16, '' + Math.round(eff), '#ffc080');
  if (o.horseHp <= 0) {
    B.corpse(o.x, o.y, o.face, true);
    o.horse = null; o.r = 9; o.sp = 0; o.vx = 0; o.vy = 0; o.couch = false;
    if (o.group === 'cav' && !o.isPlayer) o.group = 'inf';
    if (o.mode === 'ranged' && !B.canUseRanged(o)) o.mode = 'melee';
    o.stun = 1.0; o.state = 'idle';
    B.damage(o, 8, 'blunt', null);
    if (o.isPlayer) B.msg('你的坐骑倒下了！', 2);
  }
};
B.fall = function (o, dt, src) {
  o.alive = false; o.hp = 0; o.state = 'dead';
  B.corpse(o.x, o.y, o.face, false, B.TEAM_COLORS[o.team % B.TEAM_COLORS.length]);
  if (o.horse) B.corpse(o.x + 10, o.y + 6, o.face, true);
  if (o.isPlayer) {
    B.playerDown = true;
    B.msg('你被击倒了！', 4);
    BUI.showDown();
    return;
  }
  if (src && src.heroId && o.troopId) B.heroKills[src.heroId] = (B.heroKills[src.heroId] || 0) + D.TROOPS[o.troopId].tier;
  if (o.heroId) {
    B.heroDown[o.heroId] = true;
    B.feedAdd(`同伴 ${o.name} 负伤倒下了`, '#ffb070');
    return;
  }
  let wounded;
  if (B.mode === 'arena' || dt === 'blunt') wounded = true;
  else if (o.team === 0) wounded = Math.random() < 0.35 + W.pskill('surgery') * 0.05;
  else wounded = Math.random() < 0.5;
  if (o.troopId) B.fallen.push({ team: o.team, owner: o.owner, troopId: o.troopId, wounded });
  if (src && src.isPlayer) {
    B.kills++;
    if (o.troopId) B.killList.push(o.troopId);
    B.feedAdd(`你击倒了 ${o.name}`, '#ffe070');
  } else if (src) {
    B.feedAdd(`${src.name} ${wounded ? '击倒' : '击杀'}了 ${o.name}`, o.team === 0 ? '#ff9a9a' : '#a8d0ff');
  }
};

// ---------------- 远程 ----------------
B.shoot = function (u, quality) {
  const it = u.ranged;
  let spread = u.isPlayer ? Math.max(0.012, 0.11 - u.skill * 0.016) * (1.6 - quality * 0.6)
    : u.heroId ? Math.max(0.03, 0.12 - u.skill * 0.014) : Math.max(0.05, 0.17 - u.skill * 0.02);
  if (u.horse && u.sp > 30) {
    const ha = u.isPlayer ? G.player.skills.horsearchery : u.heroId ? u.haSkill : (u.kind === 'ha' ? u.skill : 1);
    spread += 0.07 * (u.sp / 220) * Math.max(0.15, 1 - ha * 0.15);
  }
  if (!u.horse && Math.hypot(u.vx, u.vy) > 30) spread += 0.04;
  const a = u.aim + (Math.random() + Math.random() - 1) * spread;
  const spd = B.PROJ_SPEED[it.kind];
  B.proj.push({
    x: u.x + Math.cos(a) * 12, y: u.y + Math.sin(a) * 12, vx: Math.cos(a) * spd + u.vx * 0.3, vy: Math.sin(a) * spd + u.vy * 0.3,
    dmg: it.dmg * u.rangedMul * (0.75 + quality * 0.25), dt: it.dt, team: u.team, src: u, life: (it.range * 1.15) / spd, kind: it.kind,
  });
  if (!(u.isPlayer && G.cheats && G.cheats.infAmmo)) u.ammo--;
  if (it.kind === 'xbow') { u.state = 'reload'; u.st = it.reload; u.loaded = false; }
  else { u.state = 'recover'; u.st = 0.2; }
  if (u.ammo <= 0) {
    if (u.isPlayer) B.msg('弹药耗尽！切换为近战武器', 2);
    u.mode = 'melee';
  }
};
B.updateProjectiles = function (dt) {
  const keep = [];
  for (const p of B.proj) {
    p.life -= dt;
    const ox = p.x, oy = p.y;
    p.x += p.vx * dt; p.y += p.vy * dt;
    let hit = false;
    for (const o of B.units) {
      if (!o.alive || o.team === p.team) continue;
      if (Math.abs(o.x - p.x) > 20 || Math.abs(o.y - p.y) > 20) continue;
      // 线段与圆的最近距离
      const dx = p.x - ox, dy = p.y - oy, l2 = dx * dx + dy * dy || 1;
      const t = U.clamp(((o.x - ox) * dx + (o.y - oy) * dy) / l2, 0, 1);
      const cx = ox + dx * t, cy = oy + dy * t;
      if (Math.hypot(o.x - cx, o.y - cy) > o.r + 1.5) continue;
      // 城垛掩护
      if (B.mode === 'siege' && o.team !== B.cfg.siegeAttacker && o.y < B.wallTop && o.y > B.wallTop - 30 && p.vy < 0 && Math.random() < 0.5) continue;
      hit = true;
      const fromA = Math.atan2(-p.vy, -p.vx);
      if (o.shield && o.mode === 'melee' && Math.abs(U.angDiff(o.aim, fromA)) < (o.state === 'block' ? 1.3 : 0.75)) {
        o.shieldHp -= p.dmg * 0.8;
        if (o.shieldHp <= 0) { o.shield = null; B.text(o.x, o.y - 20, '盾牌破碎!', '#ffb050'); }
        B.stickArrow(o.x + Math.cos(fromA) * 9, o.y + Math.sin(fromA) * 9, Math.atan2(p.vy, p.vx));
      } else if (o.horse && Math.random() < 0.45) {
        B.damageHorse(o, p.dmg, p.src);
      } else {
        B.damage(o, p.dmg, p.dt, p.src);
      }
      break;
    }
    if (!hit) {
      if (p.life <= 0 || p.x < 0 || p.y < 0 || p.x > B.FW || p.y > B.FH) { if (p.life <= 0) B.stickArrow(p.x, p.y, Math.atan2(p.vy, p.vx)); }
      else keep.push(p);
    }
  }
  B.proj = keep;
};

// ---------------- AI ----------------
B.nearestEnemy = function (u, maxD) {
  let best = null, bd = maxD || 1e9;
  for (const o of B.units) {
    if (!o.alive || !B.hostile(u, o)) continue;
    const d = Math.abs(o.x - u.x) + Math.abs(o.y - u.y);
    if (d < bd) { bd = d; best = o; }
  }
  return best;
};
B.teamCentroid = function (team, hostileTo) {
  let x = 0, y = 0, n = 0;
  for (const o of B.units) if (o.alive && (hostileTo != null ? o.team !== hostileTo : o.team === team)) { x += o.x; y += o.y; n++; }
  return n ? { x: x / n, y: y / n } : null;
};
B.orderOf = function (u) {
  if (B.mode === 'arena') return 'charge';
  if (u.team === 0) return B.orders[0][u.group] || 'charge';
  // 敌方 AI
  if (B.mode === 'siege' && B.cfg.siegeAttacker !== u.team) return 'defend';
  if (u.group === 'arc' && B.time < 25) return 'skirmish';
  return 'charge';
};
B.ai = function (u, dt) {
  u.think -= dt;
  if (u.rout) { B.aiRout(u, dt); return; }
  if (u.think <= 0) {
    u.think = U.rr(0.25, 0.45);
    const ord = B.orderOf(u);
    u.order = ord;
    const e = B.nearestEnemy(u);
    u.target = e;
    if (!e) return;
    const d = Math.hypot(e.x - u.x, e.y - u.y);
    u.tdist = d;
    // 远程/近战切换
    if (u.ranged && u.ammo > 0 && B.canUseRanged(u)) {
      if (u.prefRanged) u.mode = d < 95 && !u.horse ? 'melee' : 'ranged';
      else u.mode = d > 70 && d < u.ranged.range * 0.8 && Math.random() < 0.6 ? 'ranged' : 'melee';
    } else u.mode = 'melee';
    // 移动目标
    u.dest = null;
    if (ord === 'hold' || ord === 'follow') {
      const slot = B.slotPos(u);
      const engage = u.mode === 'ranged' ? 0 : u.group === 'cav' ? 110 : 70;
      if (d < engage + 20) u.dest = null;
      else u.dest = slot;
    } else if (ord === 'defend') {
      const engage = u.mode === 'ranged' ? 0 : 150;
      if (d < engage && e.y < B.wallBot + 40) u.dest = null; else u.dest = { x: u.holdX, y: u.holdY };
      if (u.mode === 'ranged') u.dest = { x: u.holdX, y: u.holdY };
    } else if (ord === 'skirmish') {
      if (u.mode === 'ranged' && d < u.ranged.range * 0.85) u.dest = { x: u.x, y: u.y };
    }
    // 骑枪冲刺
    u.couch = u.horse && u.melee.couch && u.mode === 'melee' && d < 260 && d > 30 && Math.abs(U.angDiff(u.face, Math.atan2(e.y - u.y, e.x - u.x))) < 0.5;
  }
  const e = u.target;
  if (!e || !e.alive) { B.aiMove(u, null, dt, 0); return; }
  const dx = e.x - u.x, dy = e.y - u.y, d = Math.hypot(dx, dy);
  const ang = Math.atan2(dy, dx);
  if (u.mode === 'ranged') {
    const inRange = d < u.ranged.range * 0.92;
    if (u.dest) B.aiMove(u, u.dest, dt, 0.9);
    else if (!inRange) B.aiMove(u, e, dt, 1);
    else if (u.horse) {
      // 骑射：环绕保持距离
      const keepD = u.ranged.range * 0.5;
      const side = ang + (d < keepD ? Math.PI * 0.7 : Math.PI * 0.45) * (u.id % 2 ? 1 : -1);
      B.aiMove(u, { x: u.x + Math.cos(side) * 100, y: u.y + Math.sin(side) * 100 }, dt, 0.8);
    } else B.aiMove(u, null, dt, 0);
    if (inRange && (u.state === 'idle' || u.state === 'aim') && u.loaded) {
      if (u.state === 'idle') { u.state = 'aim'; u.aimT = 0; u.aimNeed = u.ranged.kind === 'xbow' ? U.rr(0.7, 1.4) : u.ranged.reload * U.rr(1.2, 1.9); u.leadK = U.rr(0.2, 1.1); }
      u.aimT += dt;
      u.aim = B.leadAngle(u, e, u.heroId ? 0.9 : u.leadK);
      if (!u.horse) u.face = u.aim;
      if (u.aimT >= u.aimNeed) { B.shoot(u, 1); }
    } else if (u.state === 'aim' && !inRange) u.state = 'idle';
    return;
  }
  // 近战
  const reach = u.melee.reach + e.r;
  if (u.dest) B.aiMove(u, u.dest, dt, 1);
  else if (u.horse) {
    // 骑兵冲锋：始终全速，靠转向穿插
    B.aiMove(u, e, dt, 1);
  } else if (d > reach * 0.85) B.aiMove(u, e, dt, 1);
  else { B.aiMove(u, null, dt, 0); u.face = U.rotTo(u.face, ang, dt * 8); }
  if (d < reach + 4 && u.state === 'idle' && u.stun <= 0 && !u.couch) {
    if (u.horse && Math.abs(U.angDiff(u.face, ang)) > 2.2) return;
    if (B.time - u.lastAtk < 0.2) return;
    u.lastAtk = B.time;
    u.atkTarget = e; u.aim = ang;
    if (!u.horse) u.face = ang;
    if (Math.random() < 0.85) B.beginSwing(u);
  }
};
B.slotPos = function (u) {
  const team = u.team, g = u.group;
  let a = B.anchors[team][g];
  if (B.orders[team] && B.orders[team][g] === 'follow' && B.player.alive) {
    const p = B.player;
    a = { x: p.x - Math.cos(p.face) * 50, y: p.y - Math.sin(p.face) * 50, face: p.face };
  }
  if (!a) { a = { x: u.spawnX || u.x, y: u.spawnY || u.y, face: u.face }; B.anchors[team][g] = a; }
  if (!u.spawnX) { u.spawnX = u.x; u.spawnY = u.y; }
  const mates = B.units.filter(o => o.alive && o.team === team && o.group === g && !o.rout);
  const i = mates.indexOf(u);
  const n = mates.length;
  const spacing = g === 'cav' ? 34 : 22;
  const width = Math.max(1, Math.ceil(Math.sqrt(n * 3)));
  const row = Math.floor(i / width), col = i % width;
  const enemy = B.teamCentroid(team, team);
  const face = a.face != null ? a.face : enemy ? Math.atan2(enemy.y - a.y, enemy.x - a.x) : -Math.PI / 2;
  const fx = Math.cos(face), fy = Math.sin(face), rx = -fy, ry = fx;
  const back = g === 'arc' ? 45 : g === 'cav' ? 30 : 0;
  const lat = (col - (Math.min(width, n) - 1) / 2) * spacing + (g === 'cav' ? 160 : 0);
  const bk = row * spacing + back;
  return { x: a.x + rx * lat - fx * bk, y: a.y + ry * lat - fy * bk, face };
};
B.aiMove = function (u, tgt, dt, speedFrac) {
  let tx, ty;
  if (tgt) {
    const n = B.navPoint(u, tgt.x, tgt.y);
    tx = n.x; ty = n.y;
  }
  const d = tgt ? Math.hypot(tx - u.x, ty - u.y) : 0;
  if (u.horse) {
    if (tgt && d > 8) {
      const a = Math.atan2(ty - u.y, tx - u.x);
      u.face = U.rotTo(u.face, a, u.maneuver * dt);
      const want = u.dest && d < 60 ? u.hsp * Math.min(1, d / 80) * speedFrac : u.hsp * speedFrac;
      B.horseSpeed(u, want, dt);
    } else B.horseSpeed(u, 0, dt);
    if (u.state === 'idle') u.aim = u.face;
  } else {
    let vx = 0, vy = 0;
    if (tgt && d > 4) {
      const sp = u.speedFoot * speedFrac * (u.state === 'aim' ? 0.4 : 1) * Math.min(1, d / 20);
      vx = (tx - u.x) / d * sp; vy = (ty - u.y) / d * sp;
      if (u.state === 'idle') u.face = U.rotTo(u.face, Math.atan2(vy, vx), dt * 8);
    } else if (u.dest && u.dest.face != null && u.state === 'idle' && !u.target) u.face = U.rotTo(u.face, u.dest.face, dt * 4);
    u.vx += (vx - u.vx) * Math.min(1, dt * 8); u.vy += (vy - u.vy) * Math.min(1, dt * 8);
    if (u.state === 'idle') u.aim = u.face;
  }
};
// 攻城寻路：经过梯子缺口
B.navPoint = function (u, tx, ty) {
  if (B.mode !== 'siege') return { x: tx, y: ty };
  const top = B.wallTop - 6, bot = B.wallBot + 6;
  const uSide = u.y < top ? -1 : u.y > bot ? 1 : 0, tSide = ty < top ? -1 : ty > bot ? 1 : 0;
  if (uSide === tSide && uSide !== 0) return { x: tx, y: ty };
  let g = B.gaps[0], gd = 1e9;
  B.gaps.forEach(x => { const dd = Math.abs(x - u.x) + Math.abs(x - tx) * 0.5; if (dd < gd) { gd = dd; g = x; } });
  if (uSide === 0) return { x: g, y: tSide < 0 ? top - 30 : bot + 30 };
  if (Math.abs(u.x - g) > 10) return { x: g, y: uSide < 0 ? top - 14 : bot + 14 };
  return { x: g, y: uSide < 0 ? bot + 30 : top - 30 };
};
B.aiRout = function (u, dt) {
  const sp = B.spawnPoint(u.team);
  const tgt = { x: u.x + (u.x < B.FW / 2 ? -200 : 200) * 0.3, y: sp.y > B.FH / 2 ? B.FH + 50 : -50 };
  u.mode = 'melee'; u.state = u.state === 'windup' ? 'idle' : u.state;
  B.aiMove(u, tgt, dt, 1);
  if (u.y < 5 || u.y > B.FH - 5 || u.x < 5 || u.x > B.FW - 5) {
    u.alive = false; u.escaped = true;
    if (u.troopId) B.escaped.push({ team: u.team, owner: u.owner, troopId: u.troopId });
  }
};
B.moraleCheck = function () {
  if (B.mode === 'arena') return;
  for (let team = 0; team < 2; team++) {
    const alive = B.units.filter(u => u.team === team && u.alive && !u.rout).length + B.queues[team].length;
    const ratio = alive / Math.max(1, B.initial[team]);
    const other = B.units.filter(u => u.team !== team && u.alive && !u.rout).length + B.queues[1 - team].length;
    if (ratio < 0.35 && other > alive * 1.3) {
      B.units.forEach(u => {
        if (u.team !== team || !u.alive || u.rout || u.isPlayer) return;
        if (Math.random() < (u.hp < u.maxHp * 0.6 ? 0.08 : 0.025)) {
          u.rout = true;
          if (team === 1 && Math.random() < 0.3) B.feedAdd(`${u.name} 溃逃了！`, '#ddd');
        }
      });
    }
  }
};

// ---------------- 物理 ----------------
B.physics = function (dt) {
  const us = B.units;
  for (const u of us) {
    if (!u.alive) continue;
    if (u.stun > 0 && !u.horse) { u.vx *= 0.8; u.vy *= 0.8; }
    u.x += u.vx * dt; u.y += u.vy * dt;
  }
  // 单位碰撞
  for (let i = 0; i < us.length; i++) {
    const a = us[i];
    if (!a.alive) continue;
    for (let j = i + 1; j < us.length; j++) {
      const b = us[j];
      if (!b.alive) continue;
      const dx = b.x - a.x, dy = b.y - a.y;
      const rr = a.r + b.r;
      if (dx > rr || dx < -rr || dy > rr || dy < -rr) continue;
      const d = Math.hypot(dx, dy) || 0.01;
      if (d >= rr) continue;
      // 战马冲撞
      if (B.hostile(a, b)) {
        if (a.horse && !b.horse && a.sp > 90 && a.bumpCd <= 0) B.bump(a, b);
        else if (b.horse && !a.horse && b.sp > 90 && b.bumpCd <= 0) B.bump(b, a);
      }
      const ov = (rr - d) / 2;
      const nx = dx / d, ny = dy / d;
      const wa = a.horse ? 0.3 : 0.5, wb = b.horse ? 0.3 : 0.5;
      const wsum = wa + wb;
      a.x -= nx * ov * 2 * wa / wsum; a.y -= ny * ov * 2 * wa / wsum;
      b.x += nx * ov * 2 * wb / wsum; b.y += ny * ov * 2 * wb / wsum;
    }
  }
  // 障碍物与边界
  for (const u of us) {
    if (!u.alive) continue;
    for (const o of B.obstacles) {
      const dx = u.x - o.x, dy = u.y - o.y, rr = o.r * (o.tree ? 0.55 : 1) + u.r;
      if (Math.abs(dx) > rr || Math.abs(dy) > rr) continue;
      const d = Math.hypot(dx, dy) || 0.01;
      if (d < rr) { u.x = o.x + dx / d * rr; u.y = o.y + dy / d * rr; if (u.horse) u.sp *= 0.9; }
    }
    for (const w of B.walls) {
      const cx = U.clamp(u.x, w.x, w.x + w.w), cy = U.clamp(u.y, w.y, w.y + w.h);
      const dx = u.x - cx, dy = u.y - cy, d = Math.hypot(dx, dy);
      if (d < u.r) {
        if (d > 0.01) { u.x = cx + dx / d * u.r; u.y = cy + dy / d * u.r; }
        else u.y = u.y < w.y + w.h / 2 ? w.y - u.r : w.y + w.h + u.r;
      }
    }
    if (B.mode === 'arena') {
      const dx = u.x - B.FW / 2, dy = u.y - B.FH / 2, d = Math.hypot(dx, dy);
      if (d > B.arenaR - u.r) { u.x = B.FW / 2 + dx / d * (B.arenaR - u.r); u.y = B.FH / 2 + dy / d * (B.arenaR - u.r); }
    } else if (!u.rout) {
      u.x = U.clamp(u.x, u.r, B.FW - u.r); u.y = U.clamp(u.y, u.r, B.FH - u.r);
      if (u.horse && (u.x <= u.r + 1 || u.x >= B.FW - u.r - 1 || u.y <= u.r + 1 || u.y >= B.FH - u.r - 1)) u.sp *= 0.95;
    }
  }
};
B.bump = function (rider, foot) {
  rider.bumpCd = 0.5;
  const dmg = rider.horse.chg * (rider.sp / 200) * U.rr(0.8, 1.2);
  const a = rider.face;
  foot.vx += Math.cos(a) * 160; foot.vy += Math.sin(a) * 160;
  foot.x += Math.cos(a) * 8; foot.y += Math.sin(a) * 8;
  foot.stun = 0.7;
  if (foot.state === 'windup' || foot.state === 'aim') foot.state = 'idle';
  rider.sp *= 0.65;
  B.damage(foot, dmg, 'blunt', rider);
};

// ---------------- 结束判定 ----------------
B.teamActive = team => B.units.some(u => u.team === team && u.alive && !u.rout) || (B.queues[team] && B.queues[team].length > 0);
B.checkEnd = function () {
  if (B.over) return;
  if (B.mode === 'arena') {
    if (!B.player.alive) return B.finish(1);
    if (!B.units.some(u => u.alive && u.team !== 0)) return B.finish(0);
    return;
  }
  const a = B.teamActive(0), b = B.teamActive(1);
  if (!b) return B.finish(0);
  if (!a) return B.finish(1);
};
B.finish = function (winner, retreat) {
  if (B.over) return;
  B.over = true;
  B.timeScale = 1;
  // 统计
  const pl = B.player;
  B.result = {
    winner, retreat: !!retreat, fallen: B.fallen, escaped: B.escaped, playerDown: B.playerDown, kills: B.kills, killList: B.killList,
    playerHpFrac: pl.alive ? pl.hp / pl.maxHp : 0.15,
    arenaWins: B.mode === 'arena' ? B.units.filter(u => !u.alive && u.team !== 0).length : 0,
    heroHp: B.units.filter(u => u.heroId).map(u => [u.heroId, u.alive ? u.hp / u.maxHp : 0.1]), heroKills: B.heroKills,
  };
  // 撤退时未上场的单位不受影响；场上存活的单位也保留
  const text = retreat ? '你撤离了战场' : winner === 0 ? '胜利！' : '战败……';
  B.msg(text, 5);
  setTimeout(() => {
    B.active = false;
    BUI.hide();
    if (B.cfg.onEnd) B.cfg.onEnd(B.result);
  }, 1600);
};
B.retreat = function () {
  // 撤退：场上的我方士兵有一部分来不及撤出
  B.units.forEach(u => {
    if (u.team === 0 && u.alive && !u.isPlayer && u.troopId && Math.random() < 0.25) {
      u.alive = false;
      B.fallen.push({ team: 0, owner: u.owner, troopId: u.troopId, wounded: Math.random() < 0.5 });
    }
  });
  B.finish(1, true);
};

// ---------------- 命令 ----------------
B.giveOrder = function (ord) {
  const groups = B.sel === 'all' ? ['inf', 'arc', 'cav'] : [B.sel];
  const p = B.player;
  groups.forEach(g => {
    B.orders[0][g] = ord;
    if (ord === 'hold') {
      const enemy = B.teamCentroid(0, 0);
      const face = enemy ? Math.atan2(enemy.y - p.y, enemy.x - p.x) : p.face;
      B.anchors[0][g] = { x: p.x + Math.cos(face) * (g === 'arc' ? -10 : 30), y: p.y + Math.sin(face) * (g === 'arc' ? -10 : 30), face };
    }
  });
  const gn = { all: '全军', inf: '步兵', arc: '远程部队', cav: '骑兵' }[B.sel];
  const on = { hold: '在此坚守！', follow: '跟随我！', charge: '冲锋！' }[ord];
  B.msg(`${gn}：${on}`, 2);
  B.units.forEach(u => { if (u.team === 0) u.think = Math.min(u.think, Math.random() * 0.2); });
};

// ---------------- 特效 ----------------
B.msgs = [];
B.msg = function (text, dur) { B.msgs.push({ text, t: dur || 2 }); if (B.msgs.length > 3) B.msgs.shift(); };
B.feedAdd = function (text, color) { B.feed.unshift({ text, color, t: 5 }); if (B.feed.length > 5) B.feed.pop(); };
B.text = function (x, y, s, c) { B.texts.push({ x, y, s, c, t: 0.9 }); };
B.blood = function (x, y, n) {
  for (let i = 0; i < Math.min(8, 2 + n / 6); i++) B.fx.push({ x, y, vx: U.rr(-60, 60), vy: U.rr(-60, 60), t: U.rr(0.2, 0.5), c: '#8b0000', r: U.rr(1.5, 3) });
  if (B.gctx && Math.random() < 0.5) { B.gctx.fillStyle = 'rgba(110,0,0,0.35)'; B.gctx.beginPath(); B.gctx.arc(x + U.rr(-6, 6), y + U.rr(-6, 6), U.rr(2, 5), 0, 7); B.gctx.fill(); }
};
B.spark = function (x, y) { for (let i = 0; i < 6; i++) B.fx.push({ x, y, vx: U.rr(-90, 90), vy: U.rr(-90, 90), t: U.rr(0.1, 0.25), c: '#ffeeaa', r: 1.6 }); };
B.corpse = function (x, y, face, horse, color) {
  const c = B.gctx;
  c.save(); c.translate(x, y); c.rotate(face + U.rr(-0.6, 0.6));
  if (horse) { c.fillStyle = 'rgba(70,45,25,0.85)'; c.beginPath(); c.ellipse(0, 0, 20, 8, 0, 0, 7); c.fill(); }
  else {
    c.fillStyle = 'rgba(90,0,0,0.45)'; c.beginPath(); c.arc(0, 0, 11, 0, 7); c.fill();
    c.fillStyle = color || '#555'; c.globalAlpha = 0.75; c.beginPath(); c.ellipse(0, 0, 10, 6, 0, 0, 7); c.fill();
    c.fillStyle = '#c9a27e'; c.beginPath(); c.arc(9, 0, 4, 0, 7); c.fill();
  }
  c.restore();
};
B.stickArrow = function (x, y, a) {
  const c = B.gctx; c.strokeStyle = 'rgba(60,40,20,0.7)'; c.lineWidth = 1.5;
  c.beginPath(); c.moveTo(x, y); c.lineTo(x - Math.cos(a) * 7, y - Math.sin(a) * 7); c.stroke();
};
B.updateFx = function (dt) {
  B.fx = B.fx.filter(f => { f.t -= dt; f.x += f.vx * dt; f.y += f.vy * dt; return f.t > 0; });
  B.texts = B.texts.filter(f => { f.t -= dt; f.y -= 25 * dt; return f.t > 0; });
  B.msgs = B.msgs.filter(m => (m.t -= dt) > 0);
  B.feed = B.feed.filter(m => (m.t -= dt) > 0);
};
B.sound = function () {};

// ---------------- 渲染 ----------------
B.draw = function (ctx, cw, ch) {
  const pl = B.player;
  let focus = pl;
  if (!pl.alive) {
    if (!B.spectate || !B.spectate.alive) B.spectate = B.units.find(u => u.alive && u.team === 0) || B.units.find(u => u.alive);
    focus = B.spectate || pl;
  }
  const z = Math.min(cw, ch * 1.6) / 640 * B.zoom;
  // 骑马时视野前移
  const lookX = focus.horse ? Math.cos(focus.face) * focus.sp * 0.35 : 0, lookY = focus.horse ? Math.sin(focus.face) * focus.sp * 0.35 : 0;
  B.cam.x += (focus.x + lookX - B.cam.x) * 0.15; B.cam.y += (focus.y + lookY - B.cam.y) * 0.15;
  const hw = cw / 2 / z, hh = ch / 2 / z;
  B.cam.x = B.FW > hw * 2 ? U.clamp(B.cam.x, hw, B.FW - hw) : B.FW / 2;
  B.cam.y = B.FH > hh * 2 ? U.clamp(B.cam.y, hh, B.FH - hh) : B.FH / 2;
  ctx.fillStyle = '#222'; ctx.fillRect(0, 0, cw, ch);
  ctx.save();
  ctx.translate(cw / 2, ch / 2); ctx.scale(z, z); ctx.translate(-B.cam.x, -B.cam.y);
  ctx.drawImage(B.ground, 0, 0, B.FW, B.FH);
  if (B.mode === 'arena') {
    ctx.strokeStyle = '#6b4a2a'; ctx.lineWidth = 16; ctx.beginPath(); ctx.arc(B.FW / 2, B.FH / 2, B.arenaR + 8, 0, 7); ctx.stroke();
    ctx.strokeStyle = '#3a2814'; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(B.FW / 2, B.FH / 2, B.arenaR, 0, 7); ctx.stroke();
  }
  // 单位
  const vis = (u) => Math.abs(u.x - B.cam.x) < cw / z && Math.abs(u.y - B.cam.y) < ch / z;
  for (const u of B.units) if (u.alive && u.horse && vis(u)) B.drawHorse(ctx, u);
  for (const u of B.units) if (u.alive && vis(u)) B.drawUnit(ctx, u);
  // 投射物
  ctx.lineCap = 'round';
  for (const p of B.proj) {
    const a = Math.atan2(p.vy, p.vx);
    ctx.strokeStyle = p.kind === 'throw' ? '#5a3a1a' : '#3a2a10'; ctx.lineWidth = p.kind === 'throw' ? 2.5 : 1.6;
    ctx.beginPath(); ctx.moveTo(p.x, p.y); ctx.lineTo(p.x - Math.cos(a) * 12, p.y - Math.sin(a) * 12); ctx.stroke();
    ctx.strokeStyle = '#eee'; ctx.lineWidth = 1.2; ctx.beginPath(); ctx.moveTo(p.x - Math.cos(a) * 10, p.y - Math.sin(a) * 10); ctx.lineTo(p.x - Math.cos(a) * 13, p.y - Math.sin(a) * 13); ctx.stroke();
  }
  // 树冠 / 城墙
  for (const o of B.obstacles) {
    if (!o.tree || !vis(o)) continue;
    ctx.fillStyle = o.snow ? 'rgba(70,95,85,0.92)' : 'rgba(45,85,40,0.9)';
    ctx.beginPath(); ctx.arc(o.x, o.y, o.r * 1.5, 0, 7); ctx.fill();
    ctx.fillStyle = o.snow ? 'rgba(235,240,240,0.8)' : 'rgba(80,125,60,0.8)';
    ctx.beginPath(); ctx.arc(o.x - o.r * 0.3, o.y - o.r * 0.3, o.r * 0.9, 0, 7); ctx.fill();
  }
  for (const w of B.walls) {
    if (w.house) {
      ctx.fillStyle = '#7a4a30'; ctx.fillRect(w.x, w.y, w.w, w.h);
      ctx.fillStyle = '#8f5a3a'; ctx.fillRect(w.x, w.y, w.w, w.h / 2);
      ctx.strokeStyle = '#3a2416'; ctx.lineWidth = 2; ctx.strokeRect(w.x, w.y, w.w, w.h);
      continue;
    }
    ctx.fillStyle = '#9b958a'; ctx.fillRect(w.x, w.y, w.w, w.h);
    ctx.fillStyle = '#7d776c';
    for (let x = w.x; x < w.x + w.w - 8; x += 18) ctx.fillRect(x, w.y + w.h - 10, 10, 10);
    ctx.strokeStyle = '#4a463e'; ctx.lineWidth = 2; ctx.strokeRect(w.x, w.y, w.w, w.h);
  }
  if (B.mode === 'siege') {
    // 攻城梯
    B.gaps.forEach(g => {
      ctx.strokeStyle = '#6b4a22'; ctx.lineWidth = 4;
      ctx.beginPath(); ctx.moveTo(g - 14, B.wallTop - 4); ctx.lineTo(g - 14, B.wallBot + 26); ctx.moveTo(g + 14, B.wallTop - 4); ctx.lineTo(g + 14, B.wallBot + 26); ctx.stroke();
      ctx.lineWidth = 2.5;
      for (let y = B.wallTop; y < B.wallBot + 24; y += 8) { ctx.beginPath(); ctx.moveTo(g - 14, y); ctx.lineTo(g + 14, y); ctx.stroke(); }
    });
  }
  // 特效
  for (const f of B.fx) { ctx.fillStyle = f.c; ctx.beginPath(); ctx.arc(f.x, f.y, f.r, 0, 7); ctx.fill(); }
  ctx.textAlign = 'center';
  for (const t of B.texts) {
    ctx.font = 'bold 13px sans-serif'; ctx.globalAlpha = Math.min(1, t.t * 2);
    ctx.lineWidth = 3; ctx.strokeStyle = '#000'; ctx.strokeText(t.s, t.x, t.y); ctx.fillStyle = t.c; ctx.fillText(t.s, t.x, t.y);
  }
  ctx.globalAlpha = 1;
  // 玩家瞄准提示
  if (pl.alive && pl.state === 'aim') {
    const len = pl.ranged.range * 0.25;
    ctx.strokeStyle = 'rgba(255,255,200,0.6)'; ctx.lineWidth = 1.5; ctx.setLineDash([5, 5]);
    ctx.beginPath(); ctx.moveTo(pl.x, pl.y); ctx.lineTo(pl.x + Math.cos(pl.aim) * len, pl.y + Math.sin(pl.aim) * len); ctx.stroke(); ctx.setLineDash([]);
    if (pl.aimTarget && pl.aimTarget.alive) {
      const t = pl.aimTarget;
      ctx.strokeStyle = '#ffdd44'; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(t.x, t.y, t.r + 6, 0, 7); ctx.stroke();
    }
  }
  if (pl.alive && pl.couch) {
    ctx.strokeStyle = 'rgba(255,120,60,0.7)'; ctx.lineWidth = 3;
    ctx.beginPath(); ctx.arc(pl.x, pl.y, pl.melee.reach + 10, pl.face - 0.38, pl.face + 0.38); ctx.stroke();
  }
  ctx.restore();
  B.drawHud(ctx, cw, ch);
  if (pl.alive) BUI.setAtkLabel(pl.mode === 'ranged' ? '射击' : pl.horse && pl.melee.couch && pl.sp > pl.hsp * 0.55 ? '冲刺!' : '攻击', pl.couch || (pl.horse && pl.melee.couch && pl.sp > pl.hsp * 0.55 && pl.mode === 'melee'));
};

B.HORSE_COLORS = { sumpter: '#7a5a3a', steppe_horse: '#8a6a40', palfrey: '#5a3a22', desert_horse: '#d8c8a8', courser: '#3a2a1a', hunter: '#6a4a2a', warhorse: '#2a2a2a', charger: '#9aa0a8' };
B.drawHorse = function (ctx, u) {
  ctx.save(); ctx.translate(u.x, u.y); ctx.rotate(u.face);
  ctx.fillStyle = 'rgba(0,0,0,0.25)'; ctx.beginPath(); ctx.ellipse(3, 4, 21, 9, 0, 0, 7); ctx.fill();
  const col = B.HORSE_COLORS[u.horse.id] || '#6a4a2a';
  const gait = Math.sin(B.time * 18) * Math.min(1, u.sp / 120) * 2.5;
  ctx.fillStyle = '#222';
  [[-12, -6], [-12, 6], [11, -6], [11, 6]].forEach(([x, y], i) => { ctx.beginPath(); ctx.arc(x + (i % 2 ? gait : -gait), y, 2.4, 0, 7); ctx.fill(); });
  ctx.fillStyle = col; ctx.beginPath(); ctx.ellipse(0, 0, 18, 7.5, 0, 0, 7); ctx.fill();
  ctx.beginPath(); ctx.ellipse(19, 0, 7, 4, 0, 0, 7); ctx.fill();
  if (u.horse.armor >= 30) { ctx.fillStyle = B.TEAM_COLORS[u.team % 8]; ctx.globalAlpha = 0.6; ctx.beginPath(); ctx.ellipse(-2, 0, 15, 8, 0, 0, 7); ctx.fill(); ctx.globalAlpha = 1; }
  ctx.fillStyle = '#1a1a1a'; ctx.fillRect(-20, -1, 5, 2);
  ctx.restore();
};
B.drawUnit = function (ctx, u) {
  const tc = B.TEAM_COLORS[u.team % 8];
  ctx.save(); ctx.translate(u.x, u.y);
  if (u.isPlayer) { ctx.strokeStyle = '#ffe14a'; ctx.lineWidth = 2.5; ctx.beginPath(); ctx.arc(0, 0, 15, 0, 7); ctx.stroke(); }
  ctx.rotate(u.horse ? u.face : u.aim);
  if (!u.horse) { ctx.fillStyle = 'rgba(0,0,0,0.25)'; ctx.beginPath(); ctx.ellipse(2, 2, 9, 7, 0, 0, 7); ctx.fill(); }
  // 武器
  const local = u.horse ? U.angDiff(u.face, u.aim) : 0;
  let swing = 0;
  if (u.state === 'windup') swing = -1.3 * (1 - u.st / (u.swingDur || 0.3));
  else if (u.state === 'recover') swing = 0.9;
  ctx.save();
  ctx.rotate(local);
  if (u.mode === 'ranged' && u.ranged) {
    if (u.ranged.kind === 'bow') {
      ctx.strokeStyle = '#5a3a16'; ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(8, 0, 10, -1.2, 1.2); ctx.stroke();
      const pull = u.state === 'aim' ? Math.min(1, u.aimT / 0.6) * 7 : 0;
      ctx.strokeStyle = '#ddd'; ctx.lineWidth = 0.8; ctx.beginPath(); ctx.moveTo(8 + Math.cos(-1.2) * 10, Math.sin(-1.2) * 10); ctx.lineTo(8 - pull + 4, 0); ctx.lineTo(8 + Math.cos(1.2) * 10, Math.sin(1.2) * 10); ctx.stroke();
    } else if (u.ranged.kind === 'xbow') {
      ctx.strokeStyle = '#4a3010'; ctx.lineWidth = 3; ctx.beginPath(); ctx.moveTo(0, 2); ctx.lineTo(18, 2); ctx.stroke();
      ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(15, -7); ctx.quadraticCurveTo(18, 2, 15, 11); ctx.stroke();
    } else { ctx.fillStyle = '#555'; ctx.fillRect(4, 5, 8, 3); }
  } else {
    ctx.save(); ctx.translate(2, 6); ctx.rotate(swing - (u.couch ? 0.5 : 0) + (u.state === 'block' && !u.shield ? -1 : 0));
    const len = Math.min(52, u.melee.reach * 0.7);
    ctx.strokeStyle = u.melee.dt === 'pierce' ? '#6b4a26' : '#ccd'; ctx.lineWidth = u.melee.hands === 2 ? 3 : 2.4;
    ctx.beginPath(); ctx.moveTo(0, 0); ctx.lineTo(len, 0); ctx.stroke();
    if (u.melee.dt === 'pierce') { ctx.fillStyle = '#ccd'; ctx.beginPath(); ctx.moveTo(len + 6, 0); ctx.lineTo(len - 1, -2.5); ctx.lineTo(len - 1, 2.5); ctx.fill(); }
    if (u.melee.dt === 'blunt' && u.melee.id !== 'wood_sword') { ctx.fillStyle = '#777'; ctx.beginPath(); ctx.arc(len, 0, 3.2, 0, 7); ctx.fill(); }
    ctx.restore();
  }
  ctx.restore();
  // 身体
  const arm = u.armor.b;
  ctx.fillStyle = arm >= 30 ? '#a8adb5' : arm >= 15 ? '#8a6a48' : '#c8b898';
  ctx.beginPath(); ctx.ellipse(0, 0, 7, 9, 0, 0, 7); ctx.fill();
  ctx.fillStyle = tc; ctx.beginPath(); ctx.ellipse(0, 0, 5, 7.5, 0, 0, 7); ctx.fill();
  if (u.fac && D.FAC[u.fac]) { ctx.fillStyle = D.FAC[u.fac].color2 || '#fff'; ctx.fillRect(-1.5, -4, 3, 8); }
  ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.lineWidth = 1; ctx.beginPath(); ctx.ellipse(0, 0, 7, 9, 0, 0, 7); ctx.stroke();
  // 盾牌
  if (u.shield && u.mode === 'melee') {
    ctx.save(); ctx.rotate(local + (u.state === 'block' ? -0.9 : 0));
    ctx.strokeStyle = '#5a3a1a'; ctx.lineWidth = 4.5;
    ctx.beginPath(); ctx.arc(0, 0, 9.5, -2.2, -0.6); ctx.stroke();
    ctx.strokeStyle = tc; ctx.lineWidth = 2.5; ctx.beginPath(); ctx.arc(0, 0, 9.5, -2.0, -0.8); ctx.stroke();
    ctx.restore();
  }
  // 头
  const helm = u.armor.h;
  ctx.fillStyle = helm >= 20 ? '#9ea4ad' : helm >= 8 ? '#6e5236' : '#d8b48c';
  ctx.beginPath(); ctx.arc(1, 0, 4.6, 0, 7); ctx.fill();
  ctx.strokeStyle = 'rgba(0,0,0,0.6)'; ctx.beginPath(); ctx.arc(1, 0, 4.6, 0, 7); ctx.stroke();
  ctx.restore();
  // 血条
  if (u.isPlayer || (B.player.alive && (u === B.player.atkTarget || u === B.player.aimTarget)) || u.hp < u.maxHp) {
    if (u.hp < u.maxHp || u.isPlayer) {
      const w = 18, f = Math.max(0, u.hp / u.maxHp);
      ctx.fillStyle = 'rgba(0,0,0,0.6)'; ctx.fillRect(u.x - w / 2, u.y - 18, w, 3);
      ctx.fillStyle = u.team === 0 ? '#5ad15a' : '#e04a4a'; ctx.fillRect(u.x - w / 2, u.y - 18, w * f, 3);
    }
  }
  if (u.rout) { ctx.fillStyle = '#fff'; ctx.font = '10px sans-serif'; ctx.textAlign = 'center'; ctx.fillText('溃', u.x, u.y - 20); }
};

B.drawHud = function (ctx, cw, ch) {
  const pl = B.player;
  const s = Math.max(0.8, Math.min(1.4, ch / 420));
  ctx.save();
  ctx.font = `bold ${13 * s}px sans-serif`; ctx.textAlign = 'left';
  // 生命与状态
  const bx = 12 * s, by = ch - 0 - 0;
  const panelY = 10 * s;
  ctx.fillStyle = 'rgba(0,0,0,0.5)'; ctx.fillRect(bx - 4, panelY - 4, 190 * s, (pl.horse ? 58 : 42) * s);
  const bar = (y, f, c, label) => {
    ctx.fillStyle = '#333'; ctx.fillRect(bx, y, 120 * s, 10 * s);
    ctx.fillStyle = c; ctx.fillRect(bx, y, 120 * s * U.clamp(f, 0, 1), 10 * s);
    ctx.fillStyle = '#fff'; ctx.font = `${11 * s}px sans-serif`; ctx.fillText(label, bx + 126 * s, y + 9 * s);
  };
  bar(panelY, pl.hp / pl.maxHp, '#d33', `生命 ${Math.max(0, Math.round(pl.hp))}`);
  let y2 = panelY + 15 * s;
  if (pl.horse) { bar(y2, pl.horseHp / pl.horse.hp, '#b8863b', `坐骑 ${Math.round(pl.horseHp)}`); y2 += 15 * s; }
  ctx.fillStyle = '#fff'; ctx.font = `${11 * s}px sans-serif`;
  const wpn = pl.mode === 'ranged' && pl.ranged ? `${pl.ranged.name} ×${pl.ammo}` : pl.melee.name;
  ctx.fillText(`${wpn}${pl.shield ? '  盾' + Math.max(0, Math.round(pl.shieldHp)) : ''}${pl.horse ? '  速度' + Math.round(pl.sp) : ''}`, bx, y2 + 10 * s);
  // 兵力
  const cnt = t => B.units.filter(u => u.team === t && u.alive && !u.rout).length;
  const q = t => (B.queues[t] ? B.queues[t].length : 0);
  ctx.textAlign = 'center'; ctx.font = `bold ${14 * s}px sans-serif`;
  if (B.mode !== 'arena') {
    const txt = `我方 ${cnt(0)}${q(0) ? '+' + q(0) : ''}   VS   敌方 ${cnt(1)}${q(1) ? '+' + q(1) : ''}`;
    ctx.fillStyle = 'rgba(0,0,0,0.5)'; ctx.fillRect(cw / 2 - 120 * s, 6 * s, 240 * s, 22 * s);
    ctx.fillStyle = '#fff'; ctx.fillText(txt, cw / 2, 22 * s);
  } else {
    const left = B.units.filter(u => u.alive && u.team !== 0).length;
    ctx.fillStyle = '#fff'; ctx.fillText(`竞技场 剩余对手 ${left}`, cw / 2, 22 * s);
  }
  // 消息
  ctx.font = `bold ${16 * s}px sans-serif`;
  B.msgs.forEach((m, i) => {
    ctx.globalAlpha = Math.min(1, m.t);
    ctx.lineWidth = 4; ctx.strokeStyle = '#000'; ctx.strokeText(m.text, cw / 2, ch * 0.3 + i * 22 * s);
    ctx.fillStyle = '#ffe9a0'; ctx.fillText(m.text, cw / 2, ch * 0.3 + i * 22 * s);
  });
  ctx.globalAlpha = 1;
  // 击杀信息
  ctx.textAlign = 'right'; ctx.font = `${11.5 * s}px sans-serif`;
  B.feed.forEach((f, i) => {
    ctx.globalAlpha = Math.min(1, f.t);
    ctx.lineWidth = 3; ctx.strokeStyle = '#000'; ctx.strokeText(f.text, cw - 12 * s, 128 * s + i * 15 * s);
    ctx.fillStyle = f.color; ctx.fillText(f.text, cw - 12 * s, 128 * s + i * 15 * s);
  });
  ctx.globalAlpha = 1;
  // 小地图
  const mw = 110 * s, mh = mw * B.FH / B.FW, mx = cw - mw - 10 * s, my = 10 * s;
  ctx.fillStyle = 'rgba(0,0,0,0.45)'; ctx.fillRect(mx, my, mw, mh);
  ctx.strokeStyle = 'rgba(255,255,255,0.4)'; ctx.strokeRect(mx, my, mw, mh);
  for (const u of B.units) {
    if (!u.alive) continue;
    ctx.fillStyle = u.isPlayer ? '#ffe14a' : B.TEAM_COLORS[u.team % 8];
    const r = u.isPlayer ? 2.5 : 1.4;
    ctx.fillRect(mx + u.x / B.FW * mw - r, my + u.y / B.FH * mh - r, r * 2, r * 2);
  }
  ctx.restore();
};
