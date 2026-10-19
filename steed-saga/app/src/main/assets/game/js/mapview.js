'use strict';
// ============ 大地图渲染与触控 ============
const MV = {
  cam: { x: 1500, y: 1000, z: 1 }, img: null, follow: true, lastPan: 0, pointers: new Map(), pinch: null, tapStart: null,
  marker: null,
};
MV.TER_COLOR = [
  [47, 95, 138], [126, 166, 84], [78, 122, 58], [232, 238, 242], [181, 181, 101], [224, 199, 138],
  [154, 154, 98], [138, 128, 110], [196, 210, 210], [216, 201, 154],
];

MV.buildImage = function () {
  const S = 2; // 每像素 2 个世界单位
  const w = Math.ceil(D.MAP_W / S), h = Math.ceil(D.MAP_H / S);
  const cv = document.createElement('canvas'); cv.width = w; cv.height = h;
  const ctx = cv.getContext('2d');
  const im = ctx.createImageData(w, h);
  const noise = U.makeNoise(G.seed + 11);
  const data = im.data;
  const GW = W.GW;
  for (let py = 0; py < h; py++) {
    for (let px = 0; px < w; px++) {
      const x = px * S, y = py * S;
      const n = noise(x / 70, y / 70, 2);
      // 边缘抖动，使地形过渡自然
      const hf = noise(x / 9, y / 9, 1) - 0.5;
      const jx = x + (n - 0.5) * 16 + hf * 9, jy = y + (noise(x / 70 + 9, y / 70 + 9, 2) - 0.5) * 16 - hf * 9;
      const gx = U.clamp(Math.floor(jx / W.CELL), 0, GW - 1), gy = U.clamp(Math.floor(jy / W.CELL), 0, W.GH - 1);
      const gi = gy * GW + gx;
      const t = W.grid[gi];
      const c = MV.TER_COLOR[t];
      let shade = 0.88 + n * 0.24;
      if (t !== 0) {
        // 山体光照
        const hR = W.height[Math.min(gi + 1, W.grid.length - 1)], hD = W.height[Math.min(gi + GW, W.grid.length - 1)];
        const hh = W.height[gi];
        const k = t === W.TER.MOUNTAIN ? 6 : t === W.TER.HILLS ? 3.5 : 1.5;
        shade += ((hh - hR) + (hh - hD)) * k;
        if (t === W.TER.MOUNTAIN) shade += (hh - 0.66) * 1.2;
      } else {
        // 浅海更亮
        let near = 0;
        for (let k = -3; k <= 3; k += 3) for (let m = -3; m <= 3; m += 3) {
          const ii = U.clamp(gy + m, 0, W.GH - 1) * GW + U.clamp(gx + k, 0, GW - 1);
          if (W.grid[ii] !== 0) near++;
        }
        shade = 0.85 + n * 0.15 + near * 0.06;
      }
      const o = (py * w + px) * 4;
      data[o] = U.clamp(c[0] * shade, 0, 255);
      data[o + 1] = U.clamp(c[1] * shade, 0, 255);
      data[o + 2] = U.clamp(c[2] * shade, 0, 255);
      data[o + 3] = 255;
    }
  }
  ctx.putImageData(im, 0, 0);
  // 装饰：树木与山峰
  const rnd = U.mulberry(G.seed + 5);
  ctx.save();
  ctx.scale(1 / S, 1 / S);
  for (let y = 0; y < D.MAP_H; y += 13) {
    for (let x = 0; x < D.MAP_W; x += 13) {
      const xx = x + rnd() * 10, yy = y + rnd() * 10;
      const t = W.terAt(xx, yy);
      if ((t === W.TER.FOREST || t === W.TER.SNOWFOREST) && rnd() < 0.75) {
        ctx.fillStyle = t === W.TER.FOREST ? 'rgba(30,62,28,0.85)' : 'rgba(40,70,60,0.85)';
        ctx.beginPath(); ctx.moveTo(xx, yy - 9); ctx.lineTo(xx - 5, yy + 3); ctx.lineTo(xx + 5, yy + 3); ctx.fill();
        if (t === W.TER.SNOWFOREST) { ctx.fillStyle = 'rgba(255,255,255,0.8)'; ctx.beginPath(); ctx.moveTo(xx, yy - 9); ctx.lineTo(xx - 2, yy - 5); ctx.lineTo(xx + 2, yy - 5); ctx.fill(); }
      } else if (t === W.TER.MOUNTAIN && rnd() < 0.22) {
        const s = 10 + rnd() * 10;
        ctx.fillStyle = 'rgba(95,85,72,0.9)';
        ctx.beginPath(); ctx.moveTo(xx, yy - s); ctx.lineTo(xx - s * 0.9, yy + s * 0.4); ctx.lineTo(xx + s * 0.9, yy + s * 0.4); ctx.fill();
        ctx.fillStyle = 'rgba(245,245,245,0.85)';
        ctx.beginPath(); ctx.moveTo(xx, yy - s); ctx.lineTo(xx - s * 0.3, yy - s * 0.55); ctx.lineTo(xx + s * 0.3, yy - s * 0.55); ctx.fill();
      } else if ((t === W.TER.STEPPE || t === W.TER.PLAINS) && rnd() < 0.04) {
        ctx.fillStyle = 'rgba(60,90,40,0.5)';
        ctx.beginPath(); ctx.arc(xx, yy, 2.5, 0, 7); ctx.fill();
      } else if (t === W.TER.DESERT && rnd() < 0.05) {
        ctx.strokeStyle = 'rgba(170,140,90,0.6)'; ctx.lineWidth = 1.5;
        ctx.beginPath(); ctx.arc(xx, yy + 6, 7, Math.PI * 1.15, Math.PI * 1.85); ctx.stroke();
      }
    }
  }
  // 道路
  ctx.strokeStyle = 'rgba(120,90,55,0.45)'; ctx.lineWidth = 3; ctx.setLineDash([8, 6]);
  const main = G.settlements;
  main.forEach(a => {
    const near = main.filter(b => b !== a).map(b => [b, U.dist(a.x, a.y, b.x, b.y)]).sort((p, q) => p[1] - q[1]).slice(0, 2);
    near.forEach(([b, d]) => { if (d < 320 && a.id < b.id) { ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.quadraticCurveTo((a.x + b.x) / 2 + 15, (a.y + b.y) / 2 - 15, b.x, b.y); ctx.stroke(); } });
  });
  ctx.restore();
  MV.img = cv;
};

MV.init = function (canvas) {
  MV.cv = canvas;
  MV.ctx = canvas.getContext('2d');
  canvas.addEventListener('pointerdown', MV.onDown);
  canvas.addEventListener('pointermove', MV.onMove);
  canvas.addEventListener('pointerup', MV.onUp);
  canvas.addEventListener('pointercancel', MV.onUp);
  canvas.addEventListener('wheel', e => { e.preventDefault(); MV.zoomAt(e.clientX, e.clientY, e.deltaY < 0 ? 1.15 : 1 / 1.15); }, { passive: false });
};
MV.resetCam = function () {
  const pp = W.player();
  MV.cam.x = pp.x; MV.cam.y = pp.y;
  MV.cam.z = Math.max(0.6, Math.min(1.6, MV.ch / 650));
  MV.follow = true;
};
MV.minZ = () => Math.max(MV.cw / D.MAP_W, MV.ch / D.MAP_H) * 0.9;
MV.zoomAt = function (sx, sy, f) {
  const c = MV.cam;
  const wx = c.x + (sx - MV.cw / 2) / c.z, wy = c.y + (sy - MV.ch / 2) / c.z;
  c.z = U.clamp(c.z * f, MV.minZ(), 4);
  c.x = wx - (sx - MV.cw / 2) / c.z; c.y = wy - (sy - MV.ch / 2) / c.z;
  MV.lastPan = performance.now(); MV.follow = false;
};
MV.toWorld = (sx, sy) => ({ x: MV.cam.x + (sx - MV.cw / 2) / MV.cam.z, y: MV.cam.y + (sy - MV.ch / 2) / MV.cam.z });
MV.toScreen = (x, y) => ({ x: (x - MV.cam.x) * MV.cam.z + MV.cw / 2, y: (y - MV.cam.y) * MV.cam.z + MV.ch / 2 });

MV.onDown = function (e) {
  MV.cv.setPointerCapture(e.pointerId);
  MV.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
  if (MV.pointers.size === 1) MV.tapStart = { x: e.clientX, y: e.clientY, t: performance.now(), moved: false };
  else if (MV.pointers.size === 2) {
    const [a, b] = [...MV.pointers.values()];
    MV.pinch = { d: Math.hypot(a.x - b.x, a.y - b.y) };
    if (MV.tapStart) MV.tapStart.moved = true;
  }
};
MV.onMove = function (e) {
  const p = MV.pointers.get(e.pointerId);
  if (!p) return;
  const dx = e.clientX - p.x, dy = e.clientY - p.y;
  p.x = e.clientX; p.y = e.clientY;
  if (MV.pointers.size === 2 && MV.pinch) {
    const [a, b] = [...MV.pointers.values()];
    const d = Math.hypot(a.x - b.x, a.y - b.y);
    MV.zoomAt((a.x + b.x) / 2, (a.y + b.y) / 2, d / MV.pinch.d);
    MV.pinch.d = d;
    return;
  }
  if (MV.tapStart && Math.hypot(e.clientX - MV.tapStart.x, e.clientY - MV.tapStart.y) > 10) MV.tapStart.moved = true;
  if (MV.tapStart && MV.tapStart.moved) {
    MV.cam.x -= dx / MV.cam.z; MV.cam.y -= dy / MV.cam.z;
    MV.follow = false; MV.lastPan = performance.now();
  }
};
MV.onUp = function (e) {
  MV.pointers.delete(e.pointerId);
  if (MV.pointers.size < 2) MV.pinch = null;
  if (MV.tapStart && !MV.tapStart.moved && MV.pointers.size === 0) MV.onTap(e.clientX, e.clientY);
  if (MV.pointers.size === 0) MV.tapStart = null;
};

MV.pickAt = function (sx, sy) {
  const R = 26;
  let best = null, bd = R;
  for (const p of G.parties) {
    if (p.kind === 'player' || p.inSettlement) continue;
    const s = MV.toScreen(p.x, p.y);
    const d = Math.hypot(s.x - sx, s.y - sy);
    if (d < bd) { bd = d; best = { party: p }; }
  }
  for (const bt of G.battles) {
    const s = MV.toScreen(bt.x, bt.y);
    const d = Math.hypot(s.x - sx, s.y - sy);
    if (d < bd) { bd = d; best = { battle: bt }; }
  }
  for (const st of G.settlements) {
    const s = MV.toScreen(st.x, st.y);
    const d = Math.hypot(s.x - sx, s.y - sy) - (st.type === 'town' ? 10 : 4);
    if (d < bd) { bd = d; best = { settlement: st }; }
  }
  return best;
};

MV.onTap = function (sx, sy) {
  if (Game.blocked()) return;
  const hit = MV.pickAt(sx, sy);
  MV.follow = true;
  if (hit && hit.party) {
    W.playerMoveTo(hit.party.x, hit.party.y, { chase: hit.party.id });
    MV.marker = { party: hit.party.id, t: performance.now() };
    UI.toast(`前往：${hit.party.name}（${W.partySize(hit.party)}人）`);
  } else if (hit && hit.battle) {
    W.playerMoveTo(hit.battle.x, hit.battle.y, { battle: hit.battle.id, chase: hit.battle.A[0] });
    MV.marker = { x: hit.battle.x, y: hit.battle.y, t: performance.now() };
  } else if (hit && hit.settlement) {
    const pp = W.player();
    if (U.dist(pp.x, pp.y, hit.settlement.x, hit.settlement.y) < 10) { Game.openSettlement(hit.settlement.id); return; }
    W.playerMoveTo(hit.settlement.x, hit.settlement.y, { settlement: hit.settlement.id });
    MV.marker = { x: hit.settlement.x, y: hit.settlement.y, t: performance.now() };
    UI.toast(`前往：${hit.settlement.name}`);
  } else {
    const w = MV.toWorld(sx, sy);
    if (!W.isLand(w.x, w.y)) { UI.toast('无法前往水域'); return; }
    W.playerMoveTo(w.x, w.y);
    MV.marker = { x: w.x, y: w.y, t: performance.now() };
  }
};

// ---------------- 绘制 ----------------
MV.draw = function () {
  const cv = MV.cv, ctx = MV.ctx, c = MV.cam;
  const pp = W.player();
  if (MV.follow && pp && performance.now() - MV.lastPan > 600) {
    c.x += (pp.x - c.x) * 0.12; c.y += (pp.y - c.y) * 0.12;
  }
  const hw = MV.cw / 2 / c.z, hh = MV.ch / 2 / c.z;
  c.x = U.clamp(c.x, Math.min(hw, D.MAP_W / 2), Math.max(D.MAP_W - hw, D.MAP_W / 2));
  c.y = U.clamp(c.y, Math.min(hh, D.MAP_H / 2), Math.max(D.MAP_H - hh, D.MAP_H / 2));
  ctx.fillStyle = '#25496b';
  ctx.fillRect(0, 0, MV.cw, MV.ch);
  ctx.save();
  ctx.translate(MV.cw / 2, MV.ch / 2);
  ctx.scale(c.z, c.z);
  ctx.translate(-c.x, -c.y);
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(MV.img, 0, 0, D.MAP_W, D.MAP_H);
  const k = 1 / c.z; // 屏幕像素换算
  const now = performance.now();
  // 势力边界染色（据点周围的光晕）
  for (const s of G.settlements) {
    if (s.type === 'village') continue;
    ctx.fillStyle = W.facColor(s.fac) + '22';
    ctx.beginPath(); ctx.arc(s.x, s.y, s.type === 'town' ? 70 : 50, 0, 7); ctx.fill();
  }
  // 据点
  for (const s of G.settlements) MV.drawSettlement(ctx, s, k);
  // 目标标记
  if (MV.marker) {
    let mx = MV.marker.x, my = MV.marker.y;
    if (MV.marker.party) { const p = W.P(MV.marker.party); if (p) { mx = p.x; my = p.y; } }
    const a = 1 - (now - MV.marker.t) / 1500;
    if (a > 0 && mx != null) {
      ctx.strokeStyle = `rgba(255,230,120,${a})`; ctx.lineWidth = 2.5 * k;
      ctx.beginPath(); ctx.arc(mx, my, (8 + (1 - a) * 10) * k, 0, 7); ctx.stroke();
    }
  }
  // 玩家路径
  if (pp && pp.moving) {
    ctx.strokeStyle = 'rgba(255,240,180,0.7)'; ctx.lineWidth = 2 * k; ctx.setLineDash([6 * k, 6 * k]);
    ctx.beginPath(); ctx.moveTo(pp.x, pp.y); ctx.lineTo(pp.tx, pp.ty); ctx.stroke(); ctx.setLineDash([]);
  }
  // 战斗
  for (const bt of G.battles) {
    const pulse = 1 + Math.sin(now / 150) * 0.15;
    MV.drawSwords(ctx, bt.x, bt.y, 11 * k * pulse);
  }
  // 部队
  for (const p of G.parties) {
    if (p.inSettlement && p.kind !== 'player') continue;
    MV.drawParty(ctx, p, k);
  }
  ctx.restore();
};

MV.drawSettlement = function (ctx, s, k) {
  const col = W.facColor(s.fac);
  const sc = Math.max(1, 0.9 * k * 1.1);
  ctx.save();
  ctx.translate(s.x, s.y);
  ctx.scale(sc, sc);
  ctx.lineJoin = 'round';
  if (s.type === 'town') {
    ctx.fillStyle = '#8b7d6b'; ctx.strokeStyle = '#2b2118'; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.rect(-14, -6, 28, 14); ctx.fill(); ctx.stroke();
    [-14, -4, 6].forEach(x => { ctx.fillStyle = '#9b8c78'; ctx.fillRect(x, -13, 8, 21); ctx.strokeRect(x, -13, 8, 21); ctx.fillStyle = col; ctx.beginPath(); ctx.moveTo(x - 1, -13); ctx.lineTo(x + 4, -20); ctx.lineTo(x + 9, -13); ctx.fill(); ctx.stroke(); });
    ctx.fillStyle = '#3a2a1a'; ctx.fillRect(-3, 1, 6, 7);
  } else if (s.type === 'castle') {
    ctx.fillStyle = '#9a9488'; ctx.strokeStyle = '#2b2118'; ctx.lineWidth = 1.5;
    ctx.beginPath(); ctx.rect(-9, -5, 18, 11); ctx.fill(); ctx.stroke();
    ctx.beginPath(); ctx.rect(-4, -15, 8, 12); ctx.fill(); ctx.stroke();
    ctx.fillStyle = col; ctx.beginPath(); ctx.moveTo(0, -15); ctx.lineTo(0, -24); ctx.lineTo(8, -21); ctx.lineTo(0, -18); ctx.fill();
    ctx.strokeStyle = '#2b2118'; ctx.beginPath(); ctx.moveTo(0, -15); ctx.lineTo(0, -24); ctx.stroke();
  } else {
    ctx.strokeStyle = '#2b2118'; ctx.lineWidth = 1.2;
    [[-7, 0], [4, -3], [0, 5]].forEach(([x, y]) => {
      ctx.fillStyle = s.looted ? '#4a3a30' : '#c9a66b'; ctx.fillRect(x - 3.5, y - 2, 7, 5); ctx.strokeRect(x - 3.5, y - 2, 7, 5);
      ctx.fillStyle = s.looted ? '#222' : col; ctx.beginPath(); ctx.moveTo(x - 4.5, y - 2); ctx.lineTo(x, y - 6); ctx.lineTo(x + 4.5, y - 2); ctx.fill(); ctx.stroke();
    });
  }
  if (s.siege) { ctx.fillStyle = `rgba(255,${120 + Math.sin(performance.now() / 120) * 60},0,0.9)`; ctx.beginPath(); ctx.arc(12, -14, 4, 0, 7); ctx.fill(); }
  ctx.restore();
  // 名称
  if (s.type !== 'village' || MV.cam.z > 0.7) {
    const fs = (s.type === 'town' ? 13 : s.type === 'castle' ? 11.5 : 10.5) * k;
    ctx.font = `${s.type === 'town' ? 'bold ' : ''}${fs}px sans-serif`;
    ctx.textAlign = 'center';
    ctx.lineWidth = 3 * k; ctx.strokeStyle = 'rgba(0,0,0,0.75)'; ctx.fillStyle = s.type === 'village' ? '#f3ead7' : '#fff6dc';
    const y = s.y + (s.type === 'town' ? 14 : 11) * sc + fs;
    ctx.strokeText(s.name, s.x, y); ctx.fillText(s.name, s.x, y);
    ctx.fillStyle = W.facColor(s.fac); ctx.fillRect(s.x - 10 * k, y + 3 * k, 20 * k, 2.5 * k);
  }
};

MV.drawSwords = function (ctx, x, y, s) {
  ctx.save(); ctx.translate(x, y);
  ctx.strokeStyle = '#fff'; ctx.lineWidth = s * 0.22; ctx.lineCap = 'round';
  ctx.beginPath(); ctx.moveTo(-s, -s); ctx.lineTo(s, s); ctx.moveTo(s, -s); ctx.lineTo(-s, s); ctx.stroke();
  ctx.strokeStyle = '#c33'; ctx.lineWidth = s * 0.12;
  ctx.beginPath(); ctx.moveTo(-s, -s); ctx.lineTo(s, s); ctx.moveTo(s, -s); ctx.lineTo(-s, s); ctx.stroke();
  ctx.restore();
};

MV.drawParty = function (ctx, p, k) {
  const isPl = p.kind === 'player';
  const col = isPl ? '#ffd54a' : p.kind === 'bandit' ? '#4a1d1d' : p.kind === 'caravan' ? '#9c7a4a' : W.facColor(p.fac);
  const r = (isPl ? 7.5 : p.kind === 'lord' ? 6.5 : 5.5) * k;
  // 敌意提示
  const pl = W.player();
  const hostile = !isPl && pl && W.partiesHostile(p, pl);
  ctx.save();
  ctx.translate(p.x, p.y);
  if (hostile) { ctx.strokeStyle = 'rgba(255,60,60,0.85)'; ctx.lineWidth = 2 * k; ctx.beginPath(); ctx.arc(0, 0, r + 3 * k, 0, 7); ctx.stroke(); }
  ctx.fillStyle = 'rgba(0,0,0,0.35)'; ctx.beginPath(); ctx.ellipse(0, r * 0.7, r, r * 0.45, 0, 0, 7); ctx.fill();
  ctx.fillStyle = col; ctx.strokeStyle = '#111'; ctx.lineWidth = 1.5 * k;
  ctx.beginPath(); ctx.arc(0, 0, r, 0, 7); ctx.fill(); ctx.stroke();
  if (p.kind === 'lord' || isPl) {
    // 旗帜
    ctx.strokeStyle = '#222'; ctx.lineWidth = 1.5 * k;
    ctx.beginPath(); ctx.moveTo(0, -r); ctx.lineTo(0, -r - 12 * k); ctx.stroke();
    ctx.fillStyle = isPl ? (G.player.liege ? W.facColor(G.player.liege) : '#fff') : W.facColor(p.fac);
    ctx.fillRect(0, -r - 12 * k, 9 * k, 6 * k);
    if (isPl) { ctx.fillStyle = '#c33'; ctx.fillRect(2 * k, -r - 11 * k, 5 * k, 4 * k); }
  }
  if (p.kind === 'caravan') { ctx.fillStyle = '#e8d3a8'; ctx.fillRect(-r * 0.5, -r * 0.4, r, r * 0.8); }
  if (p.kind === 'bandit') { ctx.fillStyle = '#e44'; ctx.beginPath(); ctx.arc(0, 0, r * 0.35, 0, 7); ctx.fill(); }
  ctx.restore();
  if (MV.cam.z > 0.55 || isPl || p.kind === 'lord') {
    const fs = 10.5 * k;
    ctx.font = `${fs}px sans-serif`; ctx.textAlign = 'center';
    const label = isPl ? `你 (${W.partySize(p)})` : `${p.name} (${W.partySize(p)})`;
    ctx.lineWidth = 3 * k; ctx.strokeStyle = 'rgba(0,0,0,0.8)';
    ctx.fillStyle = isPl ? '#ffe88a' : hostile ? '#ffb4a8' : '#e9f2ff';
    ctx.strokeText(label, p.x, p.y + r + fs + 1 * k); ctx.fillText(label, p.x, p.y + r + fs + 1 * k);
  }
};
