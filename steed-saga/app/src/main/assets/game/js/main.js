'use strict';
// ============ 启动 / 主循环 / 标题与创建角色 ============
const Main = { last: 0, dpr: 1, lastDay: -1 };

Main.init = function () {
  Main.cv = document.getElementById('cv');
  Main.ctx = Main.cv.getContext('2d');
  MV.init(Main.cv);
  Main.resize();
  window.addEventListener('resize', Main.resize);
  document.getElementById('btnParty').onclick = () => !Game.blocked() && UI.partyPanel();
  document.getElementById('btnChar').onclick = () => !Game.blocked() && UI.charPanel();
  document.getElementById('btnHero').onclick = () => !Game.blocked() && UI.heroPanel();
  document.getElementById('btnInv').onclick = () => !Game.blocked() && UI.invPanel();
  document.getElementById('btnFac').onclick = () => !Game.blocked() && UI.factionPanel();
  document.getElementById('btnLog').onclick = () => !Game.blocked() && UI.logPanel();
  document.getElementById('btnCamp').onclick = () => !Game.blocked() && Game.campMenu();
  document.getElementById('btnWait').onclick = () => { if (!Game.blocked()) { W.playerWaiting = !W.playerWaiting; if (W.playerWaiting) { const pp = W.player(); pp.tx = pp.x; pp.ty = pp.y; pp.chase = null; } } };
  document.getElementById('btnSpeed').onclick = () => { if (G) G.speed = G.speed >= 4 ? 1 : G.speed * 2; };
  document.getElementById('btnCenter').onclick = () => { MV.follow = true; MV.lastPan = 0; };
  W.onLog = msg => { if (Game.screen === 'map' && /⚔|🚩|☠|👑|🎺|⭐/.test(msg)) UI.toast(msg, 3500); };
  window.onAndroidBack = Main.back;
  window.onAndroidPause = () => { if (G && Game.screen === 'map' && !B.active) W.save('auto'); };
  Main.toTitle();
  requestAnimationFrame(Main.loop);
};
Main.resize = function () {
  Main.dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = window.innerWidth, h = window.innerHeight;
  Main.cv.width = Math.round(w * Main.dpr); Main.cv.height = Math.round(h * Main.dpr);
  Main.cv.style.width = w + 'px'; Main.cv.style.height = h + 'px';
  MV.cw = w; MV.ch = h;
};
Main.back = function () {
  if (B.active) { if (!B.over) { B.paused = true; document.getElementById('bMenuPanel').classList.remove('hidden'); } return 'ok'; }
  if (Game.waitState) { Game.stopWait(true); return 'ok'; }
  if (UI.closeTop()) return 'ok';
  if (Game.screen === 'map') { if (!UI.isOpen()) Game.campMenu(); return 'ok'; }
  if (Game.screen === 'create') { Main.toTitle(); return 'ok'; }
  return 'exit';
};

Main.loop = function (ts) {
  const dt = Math.min(0.1, (ts - Main.last) / 1000 || 0);
  Main.last = ts;
  const ctx = Main.ctx;
  ctx.setTransform(Main.dpr, 0, 0, Main.dpr, 0, 0);
  if (Main.scr !== Game.screen) {
    Main.scr = Game.screen;
    document.getElementById('hud').classList.toggle('hidden', Game.screen !== 'map');
  }
  try {
    if (Game.screen === 'map' && G) Main.mapFrame(dt);
    else if (Game.screen === 'battle' && B.active) { B.update(dt); B.draw(ctx, MV.cw, MV.ch); }
  } catch (e) { console.error(e); Main.showError(e); }
  requestAnimationFrame(Main.loop);
};
Main.showError = function (e) {
  if (Main.errShown) return;
  Main.errShown = true;
  UI.toast('出现错误：' + (e && e.message), 5000);
  setTimeout(() => (Main.errShown = false), 5000);
};
Main.mapFrame = function (dt) {
  const pp = W.player();
  if (W.pendingEvent) {
    const ev = W.pendingEvent;
    if (Game.waitState) Game.stopWait(true);
    if (!UI.isOpen()) Game.handleEvent(ev);
  } else {
    const moving = W.playerWaiting || Game.waitState || U.dist(pp.x, pp.y, pp.tx, pp.ty) > 0.5;
    if (moving && (!UI.isOpen() || Game.waitState)) W.advance(dt * W.HOURS_PER_SEC * (Game.waitState ? 8 : G.speed || 1));
    Game.tickWait();
  }
  const day = W.day();
  if (day !== Main.lastDay) { if (Main.lastDay >= 0) W.save('auto'); Main.lastDay = day; }
  MV.draw();
  // 昼夜
  const h = G.time % 24;
  const dark = h >= 20 ? (h - 20) / 4 : h < 5 ? 1 : h < 7 ? (7 - h) / 2 : 0;
  if (dark > 0) { Main.ctx.fillStyle = `rgba(10,15,45,${dark * 0.38})`; Main.ctx.fillRect(0, 0, MV.cw, MV.ch); }
  UI.updateHud();
};

// ---------------- 标题 ----------------
Main.show = function (id) {
  ['title', 'create', 'hud', 'loading'].forEach(x => document.getElementById(x).classList.toggle('hidden', x !== id && !(id === 'map' && x === 'hud')));
};
Main.toTitle = function () {
  UI.closeAll();
  B.active = false; BUI.hide();
  Game.screen = 'title';
  Main.show('title');
  const has = ['auto', 1, 2, 3].some(i => W.saveMeta(i));
  document.getElementById('tContinue').disabled = !W.saveMeta('auto');
  document.getElementById('tLoad').disabled = !has;
};
Main.loadGame = function (slot) {
  Main.show('loading');
  setTimeout(() => {
    UI.closeAll();
    if (!W.load(slot)) { UI.toast('读取失败'); Main.toTitle(); return; }
    MV.buildImage();
    Main.enterMap();
    UI.toast('读取成功');
  }, 30);
};
Main.enterMap = function () {
  Game.screen = 'map';
  Main.show('map');
  Main.lastDay = W.day();
  W.playerWaiting = false; Game.waitState = null;
  MV.resetCam();
};

// ---------------- 创建角色 ----------------
Main.create = function () {
  Game.screen = 'create';
  Main.show('create');
  const el = document.getElementById('create');
  let bg = D.BACKGROUNDS[0].id, fac = 'leon';
  const render = () => {
    el.innerHTML = `<div class="cbox"><h2>创建角色</h2>
      <label>姓名 <input id="cName" maxlength="10" value="${U.esc(document.getElementById('cName') ? document.getElementById('cName').value : '艾伦')}"></label>
      <h4>出身</h4><div class="cards">${D.BACKGROUNDS.map(b => `<div class="card ${b.id === bg ? 'on' : ''}" data-bg="${b.id}"><b>${b.name}</b><small>${b.desc}</small><small>金币 ${b.gold} · ${Object.keys(b.skills).map(k => D.SKILLS.find(s => s.id === k).name + b.skills[k]).join(' ')}</small></div>`).join('')}</div>
      <h4>出发地</h4><div class="cards">${D.FACTIONS.map(f => `<div class="card ${f.id === fac ? 'on' : ''}" data-fac="${f.id}" style="border-left:5px solid ${f.color}"><b>${f.name}</b><small>${f.desc}</small></div>`).join('')}</div>
      <div class="btnrow"><button class="big" id="cStart">踏上征途</button><button id="cBack">返回</button></div></div>`;
    el.querySelectorAll('[data-bg]').forEach(c => (c.onclick = () => { bg = c.dataset.bg; render(); }));
    el.querySelectorAll('[data-fac]').forEach(c => (c.onclick = () => { fac = c.dataset.fac; render(); }));
    el.querySelector('#cBack').onclick = () => Main.toTitle();
    el.querySelector('#cStart').onclick = () => {
      const name = (el.querySelector('#cName').value || '无名骑士').trim().slice(0, 10) || '无名骑士';
      Main.show('loading');
      setTimeout(() => {
        W.newGame({ name, bg, startFac: fac });
        MV.buildImage();
        Main.enterMap();
        UI.alert('征途开始', `大陆上六国争霸，战火连年。<br><br><b>${U.esc(name)}</b>，你带着几名随从来到了这片土地。<br><br>🗺 点击地图移动，点击城镇进入，点击部队追击。<br>🍞 记得在市场购买食物。<br>⚔ 先去剿灭附近的劫掠者，积累经验和声望吧！<br><br>（详细玩法见「营地 → 游戏说明」）`);
      }, 40);
    };
  };
  render();
};

window.addEventListener('load', () => {
  document.getElementById('tNew').onclick = () => Main.create();
  document.getElementById('tContinue').onclick = () => Main.loadGame('auto');
  document.getElementById('tLoad').onclick = () => Game.loadMenu(true);
  document.getElementById('tHelp').onclick = () => Game.help();
  Main.init();
});
