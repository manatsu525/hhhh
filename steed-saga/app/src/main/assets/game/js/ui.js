'use strict';
// ============ 界面：通用窗口 / 菜单 / 面板 ============
const UI = { stack: [] };

UI.layer = () => document.getElementById('ui');
UI.isOpen = () => UI.stack.length > 0;

// 打开一个窗口；content 可以是 HTML 字符串或节点
UI.open = function (title, content, opts) {
  opts = opts || {};
  const bg = U.el('div', 'modal-bg');
  const panel = U.el('div', 'panel' + (opts.wide ? ' wide' : '') + (opts.cls ? ' ' + opts.cls : ''));
  const head = U.el('div', 'phead');
  head.innerHTML = `<span class="ptitle">${title}</span>`;
  if (!opts.noClose) {
    const x = U.el('button', 'pclose', '✕');
    x.onclick = () => close();
    head.appendChild(x);
  }
  const body = U.el('div', 'pbody');
  if (typeof content === 'string') body.innerHTML = content; else if (content) body.appendChild(content);
  panel.appendChild(head); panel.appendChild(body);
  bg.appendChild(panel);
  UI.layer().appendChild(bg);
  const h = { bg, body, panel, close, title };
  UI.stack.push(h);
  function close(silent) {
    const i = UI.stack.indexOf(h);
    if (i >= 0) UI.stack.splice(i, 1);
    bg.remove();
    if (opts.onClose && silent !== true) opts.onClose();
  }
  h.setContent = c => { body.innerHTML = ''; if (typeof c === 'string') body.innerHTML = c; else body.appendChild(c); };
  return h;
};
UI.closeAll = function () { while (UI.stack.length) UI.stack[UI.stack.length - 1].close(true); };
UI.closeTop = function () { if (UI.stack.length) { const t = UI.stack[UI.stack.length - 1]; if (!t.panel.querySelector('.pclose')) return false; t.close(); return true; } return false; };

// 骑砍式文字菜单
UI.menu = function (o) {
  const wrap = U.el('div', 'menu');
  if (o.art) wrap.appendChild(U.el('div', 'menu-art', o.art));
  if (o.text) wrap.appendChild(U.el('div', 'menu-text', o.text));
  const list = U.el('div', 'menu-opts');
  let h;
  (o.options || []).forEach(op => {
    if (!op) return;
    const b = U.el('button', 'mopt' + (op.cls ? ' ' + op.cls : ''), `<span>${op.label}</span>${op.note ? `<small>${op.note}</small>` : ''}`);
    if (op.disabled) b.disabled = true;
    b.onclick = () => {
      if (op.keep) { op.fn && op.fn(h); return; }
      h.close();
      op.fn && op.fn();
    };
    list.appendChild(b);
  });
  wrap.appendChild(list);
  h = UI.open(o.title, wrap, { noClose: o.noClose !== false, onClose: o.onClose, wide: o.wide, cls: 'menup' });
  return h;
};
UI.alert = function (title, text, then) {
  return UI.menu({ title, text, options: [{ label: '确定', fn: then }] });
};
UI.confirm = function (title, text, yes, no) {
  return UI.menu({ title, text, options: [{ label: '确定', fn: yes }, { label: '取消', fn: no }] });
};
UI.toast = function (msg, ms) {
  const t = U.el('div', 'toast', msg);
  const box = document.getElementById('toasts');
  while (box.children.length >= 3) box.firstChild.remove();
  box.appendChild(t);
  setTimeout(() => t.classList.add('out'), ms || 2200);
  setTimeout(() => t.remove(), (ms || 2200) + 500);
};

// ---------------- 顶部状态栏 ----------------
UI.hudCache = '';
UI.updateHud = function () {
  if (!G) return;
  const pl = G.player, pp = W.player();
  const foodServ = D.GOODS.filter(g => g.food).reduce((a, g) => a + (pl.goods[g.id] || 0), 0) * D.SERVINGS_PER_FOOD - pl.foodAcc;
  const days = Math.max(0, Math.floor(foodServ / Math.max(1, W.partySize(pp))));
  const status = { free: '自由人', merc: `${W.facName(pl.liege)}雇佣兵`, vassal: `${W.facName(pl.liege)}封臣`, king: `${pl.kingdomName}之王` }[pl.status];
  const html = `<span>📅 ${W.dateStr()}</span><span>💰 ${U.fmt(pl.gold)}</span><span>⚔ ${W.partySize(pp)}/${W.partyLimit() + 1}</span>` +
    `<span class="${days < 2 ? 'warn' : ''}">🍞 ${days}天</span><span class="${pl.morale < 30 ? 'warn' : ''}">😊 ${pl.morale}</span>` +
    `<span>❤ ${Math.round(pl.hpFrac * 100)}%</span><span class="hide-s">🏳 ${status}</span><span class="hide-s">⭐ ${pl.renown}</span>`;
  if (html !== UI.hudCache) { document.getElementById('hudInfo').innerHTML = html; UI.hudCache = html; }
  const wb = document.getElementById('btnWait');
  if (wb) wb.classList.toggle('on', !!W.playerWaiting);
  const sb = document.getElementById('btnSpeed');
  if (sb) sb.textContent = '⏩×' + (G.speed || 1);
};

// ---------------- 队伍面板 ----------------
UI.partyPanel = function () {
  const pp = W.player();
  const h = UI.open('队伍', '', { wide: true });
  const render = () => {
    const pl = G.player;
    let html = `<div class="sumline">人数 ${W.partySize(pp)}/${W.partyLimit() + 1} · 战力 ${Math.round(W.partyStrength(pp))} · 每周军饷 ${W.weeklyWages()} 金 · 士气 ${pl.morale} · 速度 ${W.partySpeed(pp).toFixed(1)}</div>`;
    html += '<table class="tbl"><tr><th>兵种</th><th>数量</th><th>经验</th><th>升级</th><th></th></tr>';
    html += `<tr><td><b>${U.esc(pl.name)}</b> <small>（你）Lv${pl.level}</small></td><td>1</td><td>-</td><td>-</td><td></td></tr>`;
    pp.troops.forEach((st, i) => {
      const t = D.TROOPS[st.id];
      const need = D.troopUpgradeXp(t);
      const can = t.up.length ? Math.min(st.n - st.w, Math.floor(st.xp / need)) : 0;
      const prog = t.up.length ? `${Math.min(100, Math.round((st.xp % need) / need * 100))}%` : '满级';
      let ups = '';
      if (can > 0) t.up.forEach(u => (ups += `<button class="sb up" data-i="${i}" data-u="${u}">→${D.TROOPS[u].name}<small>${D.troopUpgradeCost(t)}金</small></button>`));
      html += `<tr><td>${t.name} <small class="k-${t.kind}">${D.KIND_NAMES[t.kind]} T${t.tier}</small></td><td>${st.n}${st.w ? ` <small class="warn">(${st.w}伤)</small>` : ''}</td>` +
        `<td>${prog}${can ? ` <small class="good">可升${can}</small>` : ''}</td><td>${ups}</td><td><button class="sb dis" data-i="${i}">遣散</button></td></tr>`;
    });
    html += '</table>';
    html += `<h4>俘虏 (${W.count(pp.prisoners)}/${W.prisonerLimit()})</h4>`;
    if (!pp.prisoners.length) html += '<div class="dim">没有俘虏</div>';
    else {
      html += '<table class="tbl">';
      pp.prisoners.forEach((st, i) => {
        const t = D.TROOPS[st.id];
        html += `<tr><td>${t.name} <small>T${t.tier}</small></td><td>${st.n}</td><td><small>赎金 ${D.ransom(t)}</small></td>` +
          `<td><button class="sb rec" data-i="${i}">招降</button><button class="sb rel" data-i="${i}">释放</button></td></tr>`;
      });
      html += '</table>';
    }
    h.setContent(html);
    h.body.querySelectorAll('.up').forEach(b => b.onclick = () => {
      const st = pp.troops[+b.dataset.i];
      const t = D.TROOPS[st.id];
      const cost = D.troopUpgradeCost(t);
      if (G.player.gold < cost) { UI.toast('金币不足'); return; }
      const need = D.troopUpgradeXp(t);
      G.player.gold -= cost;
      st.xp -= need;
      W.removeTroops(pp.troops, st.id, 1);
      W.addTroops(pp, b.dataset.u, 1);
      render();
    });
    h.body.querySelectorAll('.dis').forEach(b => b.onclick = () => {
      const st = pp.troops[+b.dataset.i];
      W.removeTroops(pp.troops, st.id, 1);
      render();
    });
    h.body.querySelectorAll('.rec').forEach(b => b.onclick = () => {
      const st = pp.prisoners[+b.dataset.i];
      if (W.partySize(pp) >= W.partyLimit() + 1) { UI.toast('队伍已满'); return; }
      if (D.TROOPS[st.id].fac === 'bandit' && st.id !== 'deserter' && Math.random() < 0.3) {
        UI.toast('这名俘虏拒绝加入你'); W.removeTroops(pp.prisoners, st.id, 1); render(); return;
      }
      W.removeTroops(pp.prisoners, st.id, 1);
      W.addTroops(pp, st.id, 1);
      G.player.morale = Math.max(0, G.player.morale - 1);
      render();
    });
    h.body.querySelectorAll('.rel').forEach(b => b.onclick = () => {
      const st = pp.prisoners[+b.dataset.i];
      W.removeTroops(pp.prisoners, st.id, st.n);
      render();
    });
  };
  render();
};

// ---------------- 人物面板 ----------------
UI.charPanel = function () {
  const h = UI.open('人物', '', { wide: true });
  const render = () => {
    const pl = G.player;
    const bg = D.BACKGROUNDS.find(b => b.id === pl.bg);
    let html = `<div class="sumline"><b>${U.esc(pl.name)}</b> · ${bg ? bg.name : ''} · 等级 ${pl.level} · 经验 ${pl.xp}/${W.xpForLevel(pl.level)} · 声望 ${pl.renown}（${D.rankName(pl.renown)}）· 生命 ${Math.round(W.heroMaxHp() * pl.hpFrac)}/${W.heroMaxHp()} · 击杀 ${pl.kills}</div>`;
    html += `<div class="cols"><div><h4>属性 <small>可用点数 ${pl.attrPts}</small></h4>`;
    D.ATTRS.forEach(a => {
      html += `<div class="statrow"><span>${a.name}</span><b>${pl.attrs[a.id]}</b>${pl.attrPts > 0 ? `<button class="sb pa" data-a="${a.id}">+</button>` : '<span></span>'}<small>${a.desc}</small></div>`;
    });
    html += `</div><div><h4>技能 <small>可用点数 ${pl.skillPts}</small></h4>`;
    D.SKILLS.forEach(s => {
      const cap = Math.floor(pl.attrs[s.attr] / 3);
      const v = pl.skills[s.id];
      html += `<div class="statrow"><span>${s.name}</span><b>${v}</b>${pl.skillPts > 0 && v < Math.min(10, cap) ? `<button class="sb ps" data-s="${s.id}">+</button>` : '<span></span>'}<small>${s.desc}（上限 ${D.ATTRS.find(a => a.id === s.attr).name}/3=${cap}）</small></div>`;
    });
    html += '</div></div>';
    html += '<h4>与各势力的关系</h4><div class="rels">' + D.FACTIONS.map(f => `<span style="color:${f.color}">${f.name} ${pl.rel[f.id] > 0 ? '+' : ''}${pl.rel[f.id]}</span>`).join('') + '</div>';
    h.setContent(html);
    h.body.querySelectorAll('.pa').forEach(b => b.onclick = () => {
      pl.attrs[b.dataset.a]++; pl.attrPts--;
      if (b.dataset.a === 'int') pl.skillPts++;
      render();
    });
    h.body.querySelectorAll('.ps').forEach(b => b.onclick = () => { pl.skills[b.dataset.s]++; pl.skillPts--; render(); });
  };
  render();
};

// ---------------- 物品面板 ----------------
UI.canEquip = function (it) {
  if (it.slot === 'horse' && it.ride > G.player.skills.riding) return `需要骑术 ${it.ride}`;
  return null;
};
UI.equip = function (id) {
  const pl = G.player, it = D.ITEMS[id];
  const why = UI.canEquip(it);
  if (why) { UI.toast(why); return false; }
  const i = pl.inv.indexOf(id);
  if (i >= 0) pl.inv.splice(i, 1);
  if (pl.eq[it.slot]) pl.inv.push(pl.eq[it.slot]);
  pl.eq[it.slot] = id;
  if (it.slot === 'melee' && it.hands === 2 && pl.eq.shield) UI.toast('双手武器无法同时使用盾牌（战斗中盾牌不会生效）');
  return true;
};
UI.invPanel = function () {
  const h = UI.open('物品与装备', '', { wide: true });
  const render = () => {
    const pl = G.player;
    let html = '<div class="cols"><div><h4>装备</h4>';
    ['melee', 'ranged', 'shield', 'head', 'body', 'legs', 'horse'].forEach(sl => {
      const it = pl.eq[sl] && D.ITEMS[pl.eq[sl]];
      html += `<div class="eqrow"><span class="slot">${D.SLOT_NAMES[sl]}</span>${it ? `<b>${it.name}</b><small>${D.itemDesc(it)}</small><button class="sb uneq" data-s="${sl}">卸下</button>` : '<span class="dim">空</span>'}</div>`;
    });
    const armor = ['head', 'body', 'legs'].reduce((a, k) => a + (pl.eq[k] ? D.ITEMS[pl.eq[k]].armor : 0), 0);
    html += `<div class="dim">总护甲 ${armor}</div></div><div><h4>背包 (${pl.inv.length}/40)</h4>`;
    if (!pl.inv.length) html += '<div class="dim">背包是空的。战利品和购买的装备会放在这里。</div>';
    pl.inv.forEach((id, i) => {
      const it = D.ITEMS[id];
      const why = UI.canEquip(it);
      html += `<div class="eqrow"><b>${it.name}</b><small>${D.SLOT_NAMES[it.slot]} · ${D.itemDesc(it)}</small>${why ? `<small class="warn">${why}</small>` : `<button class="sb eq" data-i="${i}">装备</button>`}<button class="sb drop" data-i="${i}">丢弃</button></div>`;
    });
    html += '</div></div><h4>货物 (' + W.cargoCount() + '/' + W.cargoLimit() + ')</h4><div class="rels">';
    const gs = Object.keys(pl.goods).filter(k => pl.goods[k] > 0);
    html += gs.length ? gs.map(k => `<span>${D.GOOD[k].name}${D.GOOD[k].food ? '🍞' : ''} ×${pl.goods[k]}</span>`).join('') : '<span class="dim">无</span>';
    html += '</div>';
    h.setContent(html);
    h.body.querySelectorAll('.uneq').forEach(b => b.onclick = () => {
      if (pl.inv.length >= 40) { UI.toast('背包已满'); return; }
      pl.inv.push(pl.eq[b.dataset.s]); pl.eq[b.dataset.s] = null; render();
    });
    h.body.querySelectorAll('.eq').forEach(b => b.onclick = () => { UI.equip(pl.inv[+b.dataset.i]); render(); });
    h.body.querySelectorAll('.drop').forEach(b => b.onclick = () => { pl.inv.splice(+b.dataset.i, 1); render(); });
  };
  render();
};

// ---------------- 势力面板 ----------------
UI.factionPanel = function () {
  const h = UI.open('势力与外交', '', { wide: true });
  const render = () => {
    const pf = W.playerFac();
    const pl = G.player;
    const facs = D.FACTIONS.map(f => f.id).concat(G.settlements.some(s => s.fac === 'player') || pl.status === 'king' ? ['player'] : []);
    let html = `<div class="sumline">你的身份：${{ free: '自由人（未效忠任何国家）', merc: `${W.facName(pl.liege)}的雇佣兵（每周军饷 ${W.mercPay()} 金）`, vassal: `${W.facName(pl.liege)}的封臣`, king: `${pl.kingdomName}的国王` }[pl.status]}</div>`;
    html += '<table class="tbl"><tr><th>势力</th><th>君主</th><th>城/堡/村</th><th>领主</th><th>与你</th><th>交战中</th></tr>';
    facs.forEach(f => {
      const alive = f === 'player' || G.factions[f].alive;
      const ss = G.settlements.filter(s => s.fac === f);
      const king = f === 'player' ? pl.name : (W.L(G.factions[f].king) || {}).name || '-';
      const lords = f === 'player' ? 0 : G.lords.filter(l => l.fac === f).length;
      const wars = facs.filter(o => o !== f && G.war[f] && G.war[f][o]).map(o => `<span style="color:${W.facColor(o)}">${o === 'player' ? '你' : D.FAC[o].short}</span>`).join(' ');
      const rel = f === 'player' ? '-' : (W.hostile(pf, f) ? '<b class="warn">敌对</b>' : f === pf ? '<b class="good">己方</b>' : '和平') + ` ${pl.rel[f] > 0 ? '+' : ''}${pl.rel[f] || 0}`;
      html += `<tr class="${alive ? '' : 'dead'}"><td><span class="dot" style="background:${W.facColor(f)}"></span>${W.facName(f)}${alive ? '' : '（已灭亡）'}</td><td>${U.esc(king)}</td>` +
        `<td>${ss.filter(s => s.type === 'town').length}/${ss.filter(s => s.type === 'castle').length}/${ss.filter(s => s.type === 'village').length}</td><td>${lords}</td><td>${rel}</td><td>${wars || '-'}</td></tr>`;
    });
    html += '</table>';
    if (pl.status === 'king' || pl.status === 'free') {
      html += '<h4>外交</h4><div class="dim">你目前独立行事。可以花钱向敌对国家求和。</div><div class="btnrow">';
      D.FACTIONS.forEach(f => {
        if (!G.factions[f.id].alive || !G.war.player[f.id]) return;
        const cost = 500 + Math.round(W.partyStrength(W.player()) * 2) + G.settlements.filter(s => s.fac === 'player').length * 600;
        html += `<button class="sb peace" data-f="${f.id}" data-c="${cost}">向${f.name}求和（${cost}金）</button>`;
      });
      html += '</div>';
    }
    const fiefs = G.settlements.filter(s => s.owner === 'player');
    html += `<h4>你的领地 (${fiefs.length})</h4>` + (fiefs.length ? fiefs.map(s => `${s.name}（${{ town: '城镇', castle: '城堡', village: '村庄' }[s.type]}，周收入约${W.fiefIncome(s)}，驻军${W.count(s.garrison)}）`).join('<br>') : '<div class="dim">暂无领地</div>');
    h.setContent(html);
    h.body.querySelectorAll('.peace').forEach(b => b.onclick = () => {
      const c = +b.dataset.c;
      if (pl.gold < c) { UI.toast('金币不足'); return; }
      if (Math.random() < 0.65) { pl.gold -= c; W.setWar('player', b.dataset.f, false); W.changeRel(b.dataset.f, 10); UI.toast('对方接受了和约'); }
      else { pl.gold -= Math.round(c * 0.1); UI.toast('对方拒绝了求和（使者花费了一些金币）'); }
      render();
    });
  };
  render();
};
W.mercPay = () => 40 + W.count(W.player().troops) * 4;

// ---------------- 日志 ----------------
UI.logPanel = function () {
  const html = G.log.map(l => `<div class="logrow"><small>${W.dateStr(l.t)}</small> ${U.esc(l.msg)}</div>`).join('') || '<div class="dim">暂无记录</div>';
  UI.open('编年史', html, { wide: true });
};
