'use strict';
// ============ 战斗触控界面：虚拟摇杆 / 攻击 / 格挡 / 命令 ============
const BUI = { root: null, joy: null };

BUI.build = function () {
  const root = U.el('div', 'bui hidden');
  root.innerHTML = `
    <div class="joy-zone" id="joyZone"><div class="joy-base" id="joyBase"><div class="joy-knob" id="joyKnob"></div></div></div>
    <div class="bbtns">
      <button class="bbtn big" id="bAtk">攻击</button>
      <button class="bbtn" id="bBlk">格挡</button>
      <button class="bbtn small" id="bSw">切换<br>武器</button>
    </div>
    <div class="orders" id="bOrders">
      <div class="osel">
        <button data-sel="all" class="on">全军</button><button data-sel="inf">步兵</button><button data-sel="arc">远程</button><button data-sel="cav">骑兵</button>
      </div>
      <div class="ocmd">
        <button data-ord="hold">坚守</button><button data-ord="follow">跟随</button><button data-ord="charge" class="hot">冲锋</button>
      </div>
    </div>
    <div class="btop">
      <button class="tbtn" id="bMenu">☰</button>
      <button class="tbtn" id="bZoom">🔍</button>
    </div>
    <div class="bdown hidden" id="bDown">
      <div>你被击倒了，部队仍在战斗</div>
      <button id="bFast">加速观战</button>
      <button id="bEnd">结束战斗（撤退）</button>
    </div>
    <div class="bmenu hidden" id="bMenuPanel">
      <h3>战斗菜单</h3>
      <div class="bmhelp">左侧摇杆移动/骑马转向；<b>攻击</b>：近战点击挥砍（自动锁定前方敌人），远程按住瞄准、松开射击，<b>按住攻击键再向任意方向拖动</b>即可朝该方向瞄准（骑射可以向侧面、后方射击）；骑乘骑枪高速时按住攻击＝<b>骑枪冲刺</b>；<b>格挡</b>按住防御前方攻击。顶部按钮给部队下令。</div>
      <button id="bmResume">继续战斗</button>
      <button id="bmRetreat">撤退（损失部分士兵）</button>
    </div>`;
  document.getElementById('app').appendChild(root);
  BUI.root = root;
  const $ = id => root.querySelector('#' + id);
  // 摇杆
  const zone = $('joyZone'), base = $('joyBase'), knob = $('joyKnob');
  let jid = null, ox = 0, oy = 0;
  const R = 55;
  zone.addEventListener('pointerdown', e => {
    if (jid !== null) return;
    jid = e.pointerId; zone.setPointerCapture(e.pointerId);
    const r = zone.getBoundingClientRect();
    ox = e.clientX; oy = e.clientY;
    base.style.left = (ox - r.left) + 'px'; base.style.top = (oy - r.top) + 'px';
    base.classList.add('on');
    knob.style.transform = 'translate(-50%,-50%)';
  });
  zone.addEventListener('pointermove', e => {
    if (e.pointerId !== jid) return;
    let dx = e.clientX - ox, dy = e.clientY - oy;
    const d = Math.hypot(dx, dy);
    if (d > R) { dx = dx / d * R; dy = dy / d * R; }
    knob.style.transform = `translate(calc(-50% + ${dx}px), calc(-50% + ${dy}px))`;
    B.input.mx = dx / R; B.input.my = dy / R;
  });
  const jend = e => {
    if (e.pointerId !== jid) return;
    jid = null; B.input.mx = 0; B.input.my = 0;
    base.classList.remove('on');
    knob.style.transform = 'translate(-50%,-50%)';
  };
  zone.addEventListener('pointerup', jend); zone.addEventListener('pointercancel', jend);
  // 按钮
  const hold = (el, on, off) => {
    el.addEventListener('pointerdown', e => { e.preventDefault(); el.setPointerCapture(e.pointerId); el.classList.add('down'); on(); });
    const up = e => { el.classList.remove('down'); off && off(); };
    el.addEventListener('pointerup', up); el.addEventListener('pointercancel', up);
  };
  // 攻击键：按下攻击；按住后拖动手指可朝拖动方向瞄准（骑射可射向任意方向）
  const atk = $('bAtk');
  let aid = null, sx = 0, sy = 0;
  atk.addEventListener('pointerdown', e => {
    e.preventDefault(); atk.setPointerCapture(e.pointerId); atk.classList.add('down');
    aid = e.pointerId; sx = e.clientX; sy = e.clientY;
    B.input.attack = true; B.input.attackPressed = true; B.input.aimActive = false;
  });
  atk.addEventListener('pointermove', e => {
    if (e.pointerId !== aid) return;
    const dx = e.clientX - sx, dy = e.clientY - sy, d = Math.hypot(dx, dy);
    if (d > 14) { B.input.aimActive = true; B.input.ax = dx / d; B.input.ay = dy / d; }
  });
  const atkUp = e => {
    if (e.pointerId !== aid) return;
    aid = null; atk.classList.remove('down');
    B.input.attack = false;
    setTimeout(() => { if (aid === null) B.input.aimActive = false; }, 60);
  };
  atk.addEventListener('pointerup', atkUp); atk.addEventListener('pointercancel', atkUp);
  hold($('bBlk'), () => { B.input.block = true; }, () => { B.input.block = false; });
  hold($('bSw'), () => { B.input.switchPressed = true; });
  root.querySelectorAll('[data-sel]').forEach(b => b.addEventListener('pointerdown', e => {
    e.preventDefault();
    B.sel = b.dataset.sel;
    root.querySelectorAll('[data-sel]').forEach(x => x.classList.toggle('on', x === b));
  }));
  root.querySelectorAll('[data-ord]').forEach(b => b.addEventListener('pointerdown', e => { e.preventDefault(); B.giveOrder(b.dataset.ord); }));
  $('bMenu').onclick = () => { B.paused = true; $('bMenuPanel').classList.remove('hidden'); };
  $('bmResume').onclick = () => { B.paused = false; $('bMenuPanel').classList.add('hidden'); };
  $('bmRetreat').onclick = () => { B.paused = false; $('bMenuPanel').classList.add('hidden'); B.retreat(); };
  $('bZoom').onclick = () => { B.zoom = B.zoom > 0.9 ? 0.65 : 1; };
  $('bFast').onclick = () => { B.timeScale = B.timeScale > 1 ? 1 : 4; $('bFast').textContent = B.timeScale > 1 ? '正常速度' : '加速观战'; };
  $('bEnd').onclick = () => { B.retreat(); };
  // 键盘（桌面调试）
  const keys = {};
  window.addEventListener('keydown', e => {
    if (!B.active) return;
    keys[e.code] = true;
    if (e.code === 'KeyJ' && !e.repeat) { B.input.attack = true; B.input.attackPressed = true; }
    if (e.code === 'KeyK') B.input.block = true;
    if (e.code === 'KeyQ' && !e.repeat) B.input.switchPressed = true;
    if (e.code === 'Digit1') B.giveOrder('hold');
    if (e.code === 'Digit2') B.giveOrder('follow');
    if (e.code === 'Digit3') B.giveOrder('charge');
    BUI.keyMove(keys);
  });
  window.addEventListener('keyup', e => {
    keys[e.code] = false;
    if (e.code === 'KeyJ') B.input.attack = false;
    if (e.code === 'KeyK') B.input.block = false;
    if (B.active) BUI.keyMove(keys);
  });
};
BUI.keyMove = function (k) {
  const x = (k.KeyD ? 1 : 0) - (k.KeyA ? 1 : 0), y = (k.KeyS ? 1 : 0) - (k.KeyW ? 1 : 0);
  if (x || y) { const d = Math.hypot(x, y); B.input.mx = x / d; B.input.my = y / d; }
  else if (k.KeyD === false || k.KeyA === false || k.KeyS === false || k.KeyW === false) { B.input.mx = 0; B.input.my = 0; }
};
BUI.show = function () {
  if (!BUI.root) BUI.build();
  document.getElementById('toasts').innerHTML = '';
  BUI.root.classList.remove('hidden');
  BUI.root.querySelector('#bDown').classList.add('hidden');
  BUI.root.querySelector('#bMenuPanel').classList.add('hidden');
  BUI.root.querySelector('#bOrders').classList.toggle('hidden', B.mode === 'arena');
  BUI.root.querySelector('#bFast').textContent = '加速观战';
  BUI.root.querySelector('#bEnd').textContent = B.mode === 'arena' ? '离开竞技场' : '结束战斗（撤退）';
  BUI.root.querySelector('#bmRetreat').classList.toggle('hidden', false);
  B.input.mx = 0; B.input.my = 0; B.input.attack = false; B.input.block = false;
};
BUI.setAtkLabel = function (txt, hot) {
  if (BUI.atkTxt === txt && BUI.atkHot === hot) return;
  BUI.atkTxt = txt; BUI.atkHot = hot;
  const b = BUI.root.querySelector('#bAtk');
  b.textContent = txt;
  b.classList.toggle('hot', !!hot);
};
BUI.showDown = function () { BUI.root.querySelector('#bDown').classList.remove('hidden'); };
BUI.hide = function () { if (BUI.root) BUI.root.classList.add('hidden'); };
