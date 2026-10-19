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
    `<span class="${days < 2 && !G.cheats.noFood ? 'warn' : ''}">🍞 ${G.cheats.noFood ? '∞' : days + '天'}</span><span class="${pl.morale < 30 ? 'warn' : ''}">😊 ${pl.morale}</span>` +
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
    W.companions().forEach(c => (html += `<tr><td><b>${c.name}</b> <small>同伴 Lv${c.level}</small></td><td>1${c.hpFrac < 0.5 ? ' <small class="warn">(伤)</small>' : ''}</td><td>-</td><td>-</td><td></td></tr>`));
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

// ---------------- 同伴面板 ----------------
UI.heroPanel = function () {
  const h = UI.open('同伴', '', { wide: true });
  let cur = 0;
  const render = () => {
    const list = W.companions();
    const pl = G.player;
    if (!list.length) { h.setContent('<div class="dim">你还没有同伴。在城镇的酒馆里可以遇到愿意追随你的人（每周会换地方）。<br>同伴会和你一起出战、升级，他们的技能可以作为整支队伍的技能（例如外科、寻路、战术、教练、交易）。同伴之间相处融洽，不会争吵。</div>'); return; }
    cur = Math.min(cur, list.length - 1);
    const c = list[cur];
    let html = '<div class="btnrow tabs">' + list.map((x, i) => `<button class="sb tab ${i === cur ? 'on' : ''}" data-i="${i}">${x.name} Lv${x.level}</button>`).join('') + '</div>';
    html += `<div class="sumline">${W.heroDef(c).title} · 等级 ${c.level} · 经验 ${c.xp}/${W.xpForLevel(c.level)} · 生命 ${Math.round(W.heroMaxHpOf(c) * c.hpFrac)}/${W.heroMaxHpOf(c)} · 每周薪水 ${D.heroWage(c)} 金</div>`;
    html += `<div class="cols"><div><h4>属性 <small>点数 ${c.attrPts}</small></h4>`;
    D.ATTRS.forEach(a => (html += `<div class="statrow"><span>${a.name}</span><b>${c.attrs[a.id]}</b>${c.attrPts > 0 ? `<button class="sb ha" data-a="${a.id}">+</button>` : '<span></span>'}<small></small></div>`));
    html += `<h4>技能 <small>点数 ${c.skillPts}</small></h4>`;
    D.SKILLS.forEach(s => {
      const v = c.skills[s.id] || 0, cap = Math.min(10, Math.floor(c.attrs[s.attr] / 3));
      if (!v && !c.skillPts) return;
      html += `<div class="statrow"><span>${s.name}</span><b>${v}</b>${c.skillPts > 0 && v < cap ? `<button class="sb hs" data-s="${s.id}">+</button>` : '<span></span>'}<small>${s.desc}</small></div>`;
    });
    html += '</div><div><h4>装备 <small>（从你的背包给同伴换装）</small></h4>';
    ['melee', 'ranged', 'shield', 'head', 'body', 'legs', 'horse'].forEach(sl => {
      const it = c.eq[sl] && D.ITEMS[c.eq[sl]];
      const cands = pl.inv.map((id, i) => [id, i]).filter(([id]) => D.ITEMS[id].slot === sl);
      html += `<div class="eqrow"><span class="slot">${D.SLOT_NAMES[sl]}</span>${it ? `<b>${it.name}</b>` : '<span class="dim">空</span>'}` +
        (it ? `<button class="sb hun" data-s="${sl}">卸下</button>` : '') +
        cands.map(([id, i]) => `<button class="sb heq" data-i="${i}">换:${D.ITEMS[id].name}</button>`).join('') + '</div>';
    });
    html += `</div></div><div class="btnrow"><button class="sb hdis">让 ${c.name} 离队</button></div>`;
    h.setContent(html);
    const q = (s, f) => h.body.querySelectorAll(s).forEach(b => (b.onclick = () => { f(b); render(); }));
    q('.tab', b => (cur = +b.dataset.i));
    q('.ha', b => { c.attrs[b.dataset.a]++; c.attrPts--; if (b.dataset.a === 'int') c.skillPts++; });
    q('.hs', b => { c.skills[b.dataset.s]++; c.skillPts--; });
    q('.hun', b => { if (pl.inv.length >= 40) { UI.toast('背包已满'); return; } pl.inv.push(c.eq[b.dataset.s]); c.eq[b.dataset.s] = null; });
    q('.heq', b => {
      const i = +b.dataset.i, id = pl.inv[i], it = D.ITEMS[id];
      pl.inv.splice(i, 1);
      if (c.eq[it.slot]) pl.inv.push(c.eq[it.slot]);
      c.eq[it.slot] = id;
    });
    q('.hdis', () => UI.confirm('离队', `确定让 ${c.name} 离开队伍吗？（之后可以在酒馆再次招募）`, () => { W.dismissHero(c); render(); }));
  };
  render();
};

// ---------------- 控制台（作弊） ----------------
UI.findTroop = q => {
  const all = Object.values(D.TROOPS);
  return all.find(t => t.id === q || t.name === q) || all.find(t => t.name.includes(q) || t.id.includes(q));
};
UI.findItem = q => {
  const all = Object.values(D.ITEMS).filter(i => i.cult !== 'arena');
  return all.find(i => i.id === q || i.name === q) || all.find(i => i.name.includes(q) || i.id.includes(q));
};
UI.findHero = q => (G.heroes || []).find(h => h.id === q || h.name === q || h.name.includes(q));
UI.cheat = function (line) {
  const pl = G.player, pp = W.player(), ch = G.cheats;
  const [cmd, ...rest] = line.trim().split(/\s+/);
  const arg = rest.join(' ');
  const num = (d) => { const n = parseInt(rest[rest.length - 1], 10); return isNaN(n) ? d : n; };
  const onoff = (k, name) => { ch[k] = rest[0] ? /^(on|1|开)$/.test(rest[0]) : !ch[k]; return `${name}：${ch[k] ? '开启' : '关闭'}`; };
  switch ((cmd || '').toLowerCase()) {
    case 'gold': case '金币': pl.gold += num(1000); return `金币 +${num(1000)}，现在 ${pl.gold}`;
    case 'level': case '升级': { const n = num(1); for (let i = 0; i < n; i++) W.giveXp(W.xpForLevel(pl.level) - pl.xp); return `升级到 ${pl.level} 级`; }
    case 'attr': case '属性': pl.attrPts += num(5); return `属性点 +${num(5)}`;
    case 'skill': case '技能': pl.skillPts += num(5); return `技能点 +${num(5)}`;
    case 'renown': case '声望': pl.renown += num(100); return `声望 +${num(100)}`;
    case 'troop': case '招兵': {
      const n = /\d+$/.test(arg) ? num(10) : 10;
      const name = arg.replace(/\s*\d+$/, '');
      const t = UI.findTroop(name);
      if (!t) return `找不到兵种：${name}`;
      W.addTroops(pp, t.id, n); return `招募 ${n} 名 ${t.name}`;
    }
    case 'item': case '物品': {
      const it = UI.findItem(arg);
      if (!it) return `找不到物品：${arg}`;
      pl.inv.push(it.id); return `获得 ${it.name}（已放入背包）`;
    }
    case 'equip': case '装备': {
      const it = UI.findItem(arg);
      if (!it) return `找不到物品：${arg}`;
      if (pl.eq[it.slot]) pl.inv.push(pl.eq[it.slot]);
      pl.eq[it.slot] = it.id; return `已装备 ${it.name}`;
    }
    case 'food': case '粮食': return onoff('noFood', '粮食消耗为0');
    case 'god': case '无敌': return onoff('god', '无敌');
    case 'ammo': case '弹药': return onoff('infAmmo', '远程无限弹药');
    case 'npc': case '同伴': {
      const list = arg === 'all' || arg === '全部' ? G.heroes.filter(h => !h.hired) : [UI.findHero(arg)].filter(Boolean);
      if (!list.length) return `找不到同伴：${arg}（可用：${G.heroes.map(h => h.name).join('、')}）`;
      list.forEach(h => W.hireHero(h)); return `招募同伴：${list.map(h => h.name).join('、')}`;
    }
    case 'heal': case '治疗':
      pl.hpFrac = 1; W.companions().forEach(h => (h.hpFrac = 1)); pp.troops.forEach(st => (st.w = 0)); return '全员伤势痊愈';
    case 'help': case '帮助': case '':
      return 'gold 数量 · level 级数 · attr 点数 · skill 点数 · renown 数量 · troop 兵种名 数量 · item 物品名 · equip 物品名 · food [on/off] · god [on/off] · ammo [on/off] · npc 名字/all · heal';
  }
  return `未知命令：${cmd}（输入 help 查看）`;
};
UI.consolePanel = function () {
  const ch = G.cheats;
  const troops = Object.values(D.TROOPS).filter(t => t.fac !== 'neutral' || t.id.startsWith('merc'));
  const items = Object.values(D.ITEMS).filter(i => i.cult !== 'arena');
  const opt = (v, t) => `<option value="${v}">${t}</option>`;
  const html = `<div class="console">
    <div class="btnrow"><input id="cIn" placeholder="输入命令，如 gold 5000 / troop 雄狮骑士 20 / help" style="flex:1;min-width:200px"><button class="sb" id="cRun">执行</button></div>
    <div class="clog" id="cLog"></div>
    <h4>快捷</h4>
    <div class="btnrow">
      <button class="sb q" data-c="gold 1000">+1000金</button><button class="sb q" data-c="gold 10000">+10000金</button>
      <button class="sb q" data-c="level 1">升1级</button><button class="sb q" data-c="level 5">升5级</button>
      <button class="sb q" data-c="attr 5">+5属性点</button><button class="sb q" data-c="skill 5">+5技能点</button>
      <button class="sb q" data-c="renown 100">+100声望</button><button class="sb q" data-c="heal">全员治疗</button>
    </div>
    <div class="btnrow">
      <button class="sb tg" data-c="food" data-k="noFood">粮食消耗0：${ch.noFood ? '开' : '关'}</button>
      <button class="sb tg" data-c="god" data-k="god">无敌：${ch.god ? '开' : '关'}</button>
      <button class="sb tg" data-c="ammo" data-k="infAmmo">无限弹药：${ch.infAmmo ? '开' : '关'}</button>
    </div>
    <h4>招兵</h4>
    <div class="btnrow"><select id="cTroop">${troops.map(t => opt(t.id, `${t.fac === 'bandit' ? '匪' : t.fac === 'neutral' ? '佣' : D.FAC[t.fac].short} T${t.tier} ${t.name}`)).join('')}</select>
      <button class="sb tr" data-n="1">+1</button><button class="sb tr" data-n="10">+10</button><button class="sb tr" data-n="50">+50</button></div>
    <h4>物品与装备</h4>
    <div class="btnrow"><select id="cItem">${['melee', 'ranged', 'shield', 'head', 'body', 'legs', 'horse'].map(sl => `<optgroup label="${D.SLOT_NAMES[sl]}">${items.filter(i => i.slot === sl).map(i => opt(i.id, `${i.name}（${i.price}金）`)).join('')}</optgroup>`).join('')}</select>
      <button class="sb it" data-c="item">放入背包</button><button class="sb it" data-c="equip">直接装备</button></div>
    <h4>招募同伴（NPC）</h4>
    <div class="btnrow"><select id="cHero">${G.heroes.map(h => opt(h.id, `${h.name}（${W.heroDef(h).title} Lv${h.level}）${h.hired ? '✓' : ''}`)).join('')}</select>
      <button class="sb" id="cHire">招募</button><button class="sb q" data-c="npc all">全部招募</button></div>
  </div>`;
  const h = UI.open('控制台', html, { wide: true });
  const $ = s => h.body.querySelector(s);
  const out = msg => { const d = U.el('div', '', U.esc(msg)); $('#cLog').prepend(d); };
  const run = c => out(UI.cheat(c));
  $('#cRun').onclick = () => { run($('#cIn').value); $('#cIn').value = ''; };
  $('#cIn').addEventListener('keydown', e => { if (e.key === 'Enter') $('#cRun').onclick(); });
  h.body.querySelectorAll('.q').forEach(b => (b.onclick = () => run(b.dataset.c)));
  h.body.querySelectorAll('.tg').forEach(b => (b.onclick = () => { run(b.dataset.c); b.textContent = b.textContent.replace(/：.*/, '：' + (G.cheats[b.dataset.k] ? '开' : '关')); }));
  h.body.querySelectorAll('.tr').forEach(b => (b.onclick = () => { const t = D.TROOPS[$('#cTroop').value]; W.addTroops(W.player(), t.id, +b.dataset.n); out(`招募 ${b.dataset.n} 名 ${t.name}`); }));
  h.body.querySelectorAll('.it').forEach(b => (b.onclick = () => run(`${b.dataset.c} ${$('#cItem').value}`)));
  $('#cHire').onclick = () => run(`npc ${$('#cHero').value}`);
  out('输入 help 查看全部命令');
};
