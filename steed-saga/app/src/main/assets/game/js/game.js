'use strict';
// ============ 游戏流程：据点 / 遭遇 / 交易 / 战斗结算 / 攻城 ============
const Game = { screen: 'title', waitState: null };

Game.blocked = () => UI.isOpen() || B.active || Game.screen !== 'map';
const TYPE_NAME = { town: '城镇', castle: '城堡', village: '村庄' };
const ownerName = s => (s.owner === 'player' ? '你' : s.owner && W.L(s.owner) ? W.L(s.owner).name : '无');

Game.handleEvent = function (ev) {
  W.pendingEvent = null;
  if (ev.type === 'settlement') Game.openSettlement(ev.id);
  else if (ev.type === 'encounter') { const p = W.P(ev.partyId); if (p) Game.encounter(p, ev.attacked); }
  else if (ev.type === 'joinBattle') Game.joinBattleMenu(ev.battleId);
};

// ---------------- 等待 ----------------
Game.wait = function (hours, opts) {
  opts = opts || {};
  const pp = W.player();
  pp.tx = pp.x; pp.ty = pp.y; pp.chase = null; pp.targetSettlement = null;
  if (opts.sid) pp.inSettlement = opts.sid;
  W.playerWaiting = true;
  Game.waitState = { until: G.time + hours, opts };
  const h = UI.menu({ title: opts.title || '等待中', text: `<div id="waitTxt">时间流逝……</div>`, options: [{ label: '停止', fn: () => Game.stopWait(true) }] });
  Game.waitState.h = h;
};
Game.stopWait = function (cancelled) {
  const ws = Game.waitState;
  if (!ws) return;
  Game.waitState = null;
  W.playerWaiting = false;
  const pp = W.player();
  if (UI.stack.includes(ws.h)) ws.h.close();
  if (!cancelled && ws.opts.done) ws.opts.done();
  else if (ws.opts.sid && !W.pendingEvent) Game.openSettlement(ws.opts.sid);
  if (!ws.opts.sid) pp.inSettlement = null;
};
Game.tickWait = function () {
  const ws = Game.waitState;
  if (!ws) return;
  const el = document.getElementById('waitTxt');
  if (el) el.textContent = `${W.dateStr()} · 剩余 ${Math.max(0, Math.ceil(ws.until - G.time))} 小时`;
  if (G.time >= ws.until) Game.stopWait(false);
};

// ---------------- 据点 ----------------
Game.openSettlement = function (id) {
  const s = W.S(id);
  if (!s) return;
  const pp = W.player();
  pp.tx = pp.x; pp.ty = pp.y;
  const pf = W.playerFac();
  const hostile = W.hostile(pf, s.fac);
  const isOwner = s.owner === 'player';
  let text = `<b>${s.name}</b>（${TYPE_NAME[s.type]}）<br>归属：<span style="color:${W.facColor(s.fac)}">${W.facName(s.fac)}</span> · 领主：${ownerName(s)}<br>`;
  if (s.type !== 'village') text += `繁荣度 ${s.prosperity} · 驻军 ${W.count(s.garrison)} 人`;
  else text += `繁荣度 ${s.prosperity}${s.looted ? ' · <span class="warn">已被洗劫</span>' : ` · 志愿兵 ${s.recruits} 人`}`;
  if (s.siege) text += `<br><span class="warn">🔥 正遭到 ${W.facName(s.siege.fac)} 围攻！</span>`;
  const lordsHere = G.parties.filter(p => p.inSettlement === s.id && p.kind === 'lord');
  if (lordsHere.length) text += `<br>城中领主：${lordsHere.map(p => p.name).join('、')}`;
  const opts = [];
  const art = { town: '🏰', castle: '🏯', village: '🏡' }[s.type];
  if (hostile) {
    text += '<br><br>这里的守军对你怀有敌意。';
    if (s.type === 'village') {
      if (!s.looted) opts.push({ label: '🔥 掠夺村庄', note: '与村民作战，获取货物与金币', fn: () => Game.raidVillage(s) });
    } else {
      const allied = s.siege && s.siege.fac === pf && s.siege.by === 'ai';
      if (allied) opts.push({ label: '⚔ 加入盟军攻城', note: '与围城的友军一同攻城', fn: () => Game.assault(s, true) });
      else opts.push({ label: '🪜 围攻', fn: () => Game.siegeMenu(s) });
    }
  } else {
    if (s.siege && W.hostile(pf, s.siege.fac)) opts.push({ label: '🛡 协助守城', note: '与驻军一同击退围城者', fn: () => Game.defendSiege(s) });
    if (s.type === 'town') {
      opts.push({ label: '👑 城镇大厅', note: '拜见领主', fn: () => Game.hall(s) });
      opts.push({ label: '🛒 市场', note: '买卖货物与食物', fn: () => Game.market(s) });
      opts.push({ label: '🗡 武器装备商', fn: () => Game.shop(s, 'items') });
      opts.push({ label: '🐎 马商', fn: () => Game.shop(s, 'horses') });
      opts.push({ label: '🍺 酒馆', note: '雇佣兵 / 赎金 / 任务 / 消息', fn: () => Game.tavern(s) });
      opts.push({ label: '🏟 竞技场', note: '参加角斗赢取奖金', fn: () => Game.arenaMenu(s) });
    } else if (s.type === 'castle') {
      opts.push({ label: '👑 城堡大厅', note: '拜见领主', fn: () => Game.hall(s) });
    } else {
      if (!s.looted) opts.push({ label: '🧑‍🌾 招募志愿兵', note: `${s.recruits} 人愿意参军`, fn: () => Game.recruitVillage(s) });
      opts.push({ label: '🧺 村庄集市', note: '购买食物与特产', fn: () => Game.market(s) });
    }
    if (isOwner && s.type !== 'village') opts.push({ label: '🛡 管理驻军', fn: () => Game.garrison(s) });
    opts.push({ label: '🛏 在此休息', note: '恢复伤员（直到伤员痊愈或24小时）', fn: () => Game.wait(24, { sid: s.id, title: `在${s.name}休息` }) });
  }
  opts.push({ label: '🚪 离开', fn: () => { if (s.siege && s.siege.by === 'player') s.siege = null; } });
  UI.menu({ title: s.name, art, text, options: opts });
};

// ---------------- 大厅与领主对话 ----------------
Game.hall = function (s) {
  const lords = G.parties.filter(p => p.inSettlement === s.id && p.kind === 'lord').map(p => W.L(p.lordId)).filter(Boolean);
  const opts = lords.map(l => ({ label: `与 ${l.name} 交谈`, note: l.isKing ? '国王' : '', fn: () => Game.talkLord(l, () => Game.hall(s)) }));
  let text = lords.length ? '大厅里的领主们：' : '大厅里空荡荡的，领主们都外出了。<br><small>提示：领主外出时，可以在大地图上找到他的部队并交谈。</small>';
  if (s.owner === 'player') text += '<br>这是你的领地，仆人们向你行礼。';
  opts.push({ label: '返回', fn: () => Game.openSettlement(s.id) });
  UI.menu({ title: `${s.name}·大厅`, text, options: opts });
};
Game.talkLord = function (l, back) {
  const pl = G.player;
  const fac = l.fac;
  const rel = pl.rel[fac] || 0;
  const hostile = W.hostile(W.playerFac(), fac);
  let text = `<b>${l.name}</b>（${W.facName(fac)}${l.isKing ? '·国王' : ''}，声望 ${l.renown}）<br><br>`;
  text += hostile ? '「哼，你竟敢出现在我面前。」' : rel > 20 ? '「我的朋友，很高兴见到你！」' : `「${pl.renown > 100 ? '久仰大名，' : ''}有什么事吗，旅人？」`;
  const opts = [];
  if (!hostile && l.isKing) {
    if (pl.status === 'free' && !G.settlements.some(s => s.owner === 'player')) {
      opts.push({ label: '「我愿以雇佣兵身份为您效力。」', note: `需要声望≥20，每周军饷约 ${W.mercPay()} 金`, disabled: pl.renown < 20, fn: () => Game.joinMerc(l) });
      opts.push({ label: '「我愿宣誓效忠，成为您的封臣。」', note: '需要声望≥150 或关系≥20', disabled: pl.renown < 150 && rel < 20, fn: () => Game.joinVassal(l) });
    } else if (pl.status === 'merc' && pl.liege === fac) {
      opts.push({ label: '「我愿宣誓效忠，成为您的封臣。」', note: '需要声望≥120 或关系≥15', disabled: pl.renown < 120 && rel < 15, fn: () => Game.joinVassal(l) });
      opts.push({ label: '「我想终止雇佣合同。」', fn: () => Game.leaveLiege() });
    } else if (pl.status === 'vassal' && pl.liege === fac) {
      opts.push({ label: '「陛下，请赐予我更多封地。」', note: '关系≥30 且有空闲城堡', fn: () => Game.askFief(l) });
      opts.push({ label: '「我要解除对您的效忠！」', note: '将失去所有封地', fn: () => UI.confirm('解除效忠', '你确定要背弃誓言吗？你将失去所有封地，与该国关系大幅下降。', () => Game.leaveLiege()) });
    }
  }
  opts.push({ label: '「最近局势如何？」', fn: () => {
    const f = G.factions[fac];
    const wars = Object.keys(G.war[fac]).filter(o => G.war[fac][o]).map(W.facName);
    const camp = f && f.campaign ? `我们的大军正在进攻 ${W.S(f.campaign.target).name}。` : '目前没有大规模的战役。';
    UI.alert(l.name, `「${W.facName(fac)}目前与${wars.length ? wars.join('、') : '任何国家都没有'}交战。${camp}」`, () => Game.talkLord(l, back));
  } });
  opts.push({ label: '告辞', fn: back });
  UI.menu({ title: '交谈', text, options: opts });
};
Game.joinMerc = function (l) {
  const pl = G.player;
  pl.status = 'merc'; pl.liege = l.fac;
  D.FACTIONS.forEach(f => { if (G.war.player[f.id] && !G.war[l.fac][f.id]) W.setWar('player', f.id, false, true); });
  W.changeRel(l.fac, 5);
  W.log(`你成为了 ${W.facName(l.fac)} 的雇佣兵。`);
  UI.alert(l.name, `「很好。从今天起你为${W.facName(l.fac)}而战，每周你将收到军饷。去打击我们的敌人吧！」`);
};
Game.joinVassal = function (l) {
  const pl = G.player;
  pl.status = 'vassal'; pl.liege = l.fac;
  D.FACTIONS.forEach(f => { if (G.war.player[f.id] && !G.war[l.fac][f.id]) W.setWar('player', f.id, false, true); });
  W.changeRel(l.fac, 10);
  W.addRenown(20);
  // 赏赐一个村庄
  const vs = G.settlements.filter(s => s.type === 'village' && s.fac === l.fac);
  let gift = '';
  if (vs.length) { const v = U.pick(vs); v.owner = 'player'; gift = `我将${v.name}赐予你作为封地。`; }
  W.log(`你向 ${l.name} 宣誓效忠，成为 ${W.facName(l.fac)} 的封臣。`);
  UI.alert(l.name, `「跪下吧，${U.esc(pl.name)}。从今以后你就是${W.facName(l.fac)}的贵族了。${gift}」`);
};
Game.askFief = function (l) {
  const pl = G.player;
  const free = G.settlements.filter(s => s.fac === l.fac && s.type === 'castle' && !W.L(s.owner));
  if (pl.rel[l.fac] < 30 || !free.length) { UI.alert(l.name, '「现在没有可以封赏的城堡。继续为王国立功吧。」'); return; }
  const s = free[0]; s.owner = 'player';
  G.settlements.filter(v => v.bound === s.id).forEach(v => (v.owner = 'player'));
  W.changeRel(l.fac, -10);
  UI.alert(l.name, `「你的功绩有目共睹。${s.name}从此归你所有。」`);
};
Game.leaveLiege = function () {
  const pl = G.player, f = pl.liege;
  if (!f) return;
  if (pl.status === 'vassal') {
    G.settlements.filter(s => s.owner === 'player').forEach(s => {
      const lords = G.lords.filter(l => l.fac === f);
      s.owner = lords.length ? U.pick(lords).id : null;
    });
    W.changeRel(f, -25);
  } else W.changeRel(f, -5);
  pl.status = 'free'; pl.liege = null;
  W.log(`你离开了 ${W.facName(f)} 的阵营。`);
  UI.toast('你重新成为自由人');
};

// ---------------- 交易 ----------------
Game.market = function (s) {
  const h = UI.open(`${s.name}·${s.type === 'village' ? '集市' : '市场'}`, '', { wide: true, onClose: () => Game.openSettlement(s.id) });
  const goods = s.type === 'village' ? D.GOODS.filter(g => g.food || D.PRODUCE[s.cult].cheap.includes(g.id)) : D.GOODS;
  const render = () => {
    const pl = G.player;
    let html = `<div class="sumline">金币 ${U.fmt(pl.gold)} · 货物 ${W.cargoCount()}/${W.cargoLimit()} · 🍞=食物（每份供30人·天）<small>（交易技能 ${W.pskill('trade')}）</small></div>`;
    html += '<table class="tbl"><tr><th>商品</th><th>买价</th><th>卖价</th><th>持有</th><th></th></tr>';
    goods.forEach(g => {
      const bp = W.buyPrice(s, g.id), sp = W.sellPrice(s, g.id);
      const tag = s.prices[g.id] < 0.8 ? '<small class="good">便宜</small>' : s.prices[g.id] > 1.3 ? '<small class="warn">紧缺</small>' : '';
      html += `<tr><td>${g.name}${g.food ? '🍞' : ''} ${tag}</td><td>${bp}</td><td>${sp}</td><td>${pl.goods[g.id] || 0}</td>` +
        `<td><button class="sb buy" data-g="${g.id}">买</button><button class="sb buy5" data-g="${g.id}">买5</button><button class="sb sell" data-g="${g.id}" ${pl.goods[g.id] ? '' : 'disabled'}>卖</button><button class="sb sellall" data-g="${g.id}" ${pl.goods[g.id] ? '' : 'disabled'}>全卖</button></td></tr>`;
    });
    html += '</table>';
    h.setContent(html);
    const buy = (gid, n) => {
      for (let i = 0; i < n; i++) {
        const p = W.buyPrice(s, gid);
        if (pl.gold < p) { UI.toast('金币不足'); break; }
        if (W.cargoCount() >= W.cargoLimit()) { UI.toast('货物已满'); break; }
        pl.gold -= p; pl.goods[gid] = (pl.goods[gid] || 0) + 1; s.prices[gid] *= 1.025; s.wealth += p * 0.1;
      }
      render();
    };
    const sell = (gid, n) => {
      for (let i = 0; i < n && pl.goods[gid] > 0; i++) {
        pl.gold += W.sellPrice(s, gid); pl.goods[gid]--; s.prices[gid] *= 0.975;
      }
      if (!pl.goods[gid]) delete pl.goods[gid];
      render();
    };
    h.body.querySelectorAll('.buy').forEach(b => b.onclick = () => buy(b.dataset.g, 1));
    h.body.querySelectorAll('.buy5').forEach(b => b.onclick = () => buy(b.dataset.g, 5));
    h.body.querySelectorAll('.sell').forEach(b => b.onclick = () => sell(b.dataset.g, 1));
    h.body.querySelectorAll('.sellall').forEach(b => b.onclick = () => sell(b.dataset.g, 999));
  };
  render();
};
Game.shop = function (s, kind) {
  const h = UI.open(kind === 'horses' ? `${s.name}·马商` : `${s.name}·武器装备商`, '', { wide: true, onClose: () => Game.openSettlement(s.id) });
  const render = () => {
    const pl = G.player;
    const list = kind === 'horses' ? s.horses : s.shop;
    let html = `<div class="sumline">金币 ${U.fmt(pl.gold)} · 背包 ${pl.inv.length}/40 · 骑术 ${pl.skills.riding}</div><div class="cols"><div><h4>出售中</h4>`;
    if (!list.length) html += '<div class="dim">货架空空（每周补货）</div>';
    list.forEach((id, i) => {
      const it = D.ITEMS[id];
      const cur = pl.eq[it.slot] && D.ITEMS[pl.eq[it.slot]];
      const why = UI.canEquip(it);
      html += `<div class="eqrow"><b>${it.name}</b><small>${D.SLOT_NAMES[it.slot]} · ${D.itemDesc(it)}${cur ? `<br>当前：${cur.name}` : ''}</small>` +
        `<span class="price">${W.itemBuyPrice(it)}金</span><button class="sb buy" data-i="${i}">购买</button>${why ? `<small class="warn">${why}</small>` : `<button class="sb buyeq" data-i="${i}">买并装备</button>`}</div>`;
    });
    html += '</div><div><h4>你的背包（出售）</h4>';
    if (!pl.inv.length) html += '<div class="dim">背包是空的</div>';
    pl.inv.forEach((id, i) => {
      const it = D.ITEMS[id];
      html += `<div class="eqrow"><b>${it.name}</b><small>${D.SLOT_NAMES[it.slot]}</small><span class="price">${W.itemSellPrice(it)}金</span><button class="sb sell" data-i="${i}">出售</button></div>`;
    });
    html += '</div></div>';
    h.setContent(html);
    const buy = (i, equip) => {
      const id = list[i], it = D.ITEMS[id];
      const p = W.itemBuyPrice(it);
      if (pl.gold < p) { UI.toast('金币不足'); return; }
      if (pl.inv.length >= 40) { UI.toast('背包已满'); return; }
      pl.gold -= p; list.splice(i, 1); pl.inv.push(id); s.wealth += p * 0.2;
      if (equip) UI.equip(id);
      UI.toast(`购买了 ${it.name}`);
      render();
    };
    h.body.querySelectorAll('.buy').forEach(b => b.onclick = () => buy(+b.dataset.i, false));
    h.body.querySelectorAll('.buyeq').forEach(b => b.onclick = () => buy(+b.dataset.i, true));
    h.body.querySelectorAll('.sell').forEach(b => b.onclick = () => {
      const id = pl.inv[+b.dataset.i], it = D.ITEMS[id];
      pl.gold += W.itemSellPrice(it); pl.inv.splice(+b.dataset.i, 1);
      if (it.slot === 'horse') s.horses.push(id); else s.shop.push(id);
      render();
    });
  };
  render();
};

// ---------------- 酒馆 ----------------
Game.tavern = function (s) {
  const pl = G.player, pp = W.player();
  const opts = [];
  let text = '酒馆里弥漫着麦酒与烤肉的香气。';
  if (s.merc && s.merc.n > 0) {
    const t = D.TROOPS[s.merc.id];
    const cost = Math.round(D.troopRecruitCost(t) * 1.3);
    const room = W.partyLimit() + 1 - W.partySize(pp);
    const n = Math.min(s.merc.n, room, Math.floor(pl.gold / cost));
    text += `<br>一队${t.name}（${s.merc.n}人）正在寻找雇主，每人 ${cost} 金。`;
    opts.push({ label: `雇佣 ${Math.max(0, n)} 名${t.name}`, note: `共 ${n * cost} 金`, disabled: n <= 0, fn: () => {
      pl.gold -= n * cost; W.addTroops(pp, s.merc.id, n); s.merc.n -= n;
      UI.toast(`雇佣了 ${n} 名${t.name}`); Game.tavern(s);
    } });
  }
  const pris = W.count(pp.prisoners);
  if (pris) {
    const val = pp.prisoners.reduce((a, st) => a + D.ransom(D.TROOPS[st.id]) * st.n, 0);
    opts.push({ label: `把 ${pris} 名俘虏卖给赎金经纪人`, note: `+${val} 金`, fn: () => {
      pl.gold += val; pp.prisoners = []; UI.toast(`获得赎金 ${val} 金`); Game.tavern(s);
    } });
  }
  W.heroAt(s.id).forEach(h => {
    const d = W.heroDef(h);
    text += `<br>角落里坐着一位${d.title}：${h.name}。`;
    opts.push({ label: `🧑 与${d.title}${h.name}交谈`, note: `Lv${h.level} 招募 ${W.heroHireCost(h)} 金`, fn: () => Game.heroTalk(h, s) });
  });
  // 任务
  const q = pl.quest;
  if (q && W.P(q.partyId)) text += `<br><br>📜 当前任务：消灭 ${q.name}（赏金 ${q.reward}）`;
  else {
    if (q) pl.quest = null;
    let best = null, bd = 600;
    G.parties.forEach(p => { if (p.kind === 'bandit') { const d = U.dist(p.x, p.y, s.x, s.y); if (d < bd) { bd = d; best = p; } } });
    if (best) {
      const reward = 120 + W.count(best.troops) * 18;
      opts.push({ label: '📜 打听赏金任务', note: `附近有${best.name}出没`, fn: () => UI.confirm('赏金任务', `酒馆老板：「一伙${best.name}（${W.partySize(best)}人）正在附近劫掠，就在${Game.dirText(s, best)}。消灭他们，商会会付你 ${reward} 金。」`, () => {
        pl.quest = { partyId: best.id, reward, name: best.name, from: s.id };
        MV.marker = { party: best.id, t: performance.now() + 2500 };
        W.log(`接受任务：消灭 ${best.name}，赏金 ${reward}。`);
        Game.tavern(s);
      }, () => Game.tavern(s)) });
    }
  }
  opts.push({ label: '🍻 请全场喝酒', note: '100金：士气+8', disabled: pl.gold < 100, fn: () => { pl.gold -= 100; pl.morale = Math.min(100, pl.morale + 8); UI.toast('士兵们欢呼雀跃！'); Game.tavern(s); } });
  opts.push({ label: '👂 打听消息', fn: () => UI.alert('传闻', Game.rumor(), () => Game.tavern(s)) });
  opts.push({ label: '返回', fn: () => Game.openSettlement(s.id) });
  UI.menu({ title: `${s.name}·酒馆`, art: '🍺', text, options: opts });
};
Game.heroTalk = function (h, s) {
  const d = W.heroDef(h), pl = G.player, cost = W.heroHireCost(h);
  const sk = Object.keys(h.skills).filter(k => h.skills[k] > 0).map(k => `${D.SKILLS.find(x => x.id === k).name}${h.skills[k]}`).join(' ');
  const full = W.partySize(W.player()) >= W.partyLimit() + 1;
  UI.menu({ title: h.name, art: '🧑', text: `「${d.desc}如果你需要人手，我可以跟你走。」<br><br>${d.title} · 等级 ${h.level} · 生命 ${W.heroMaxHpOf(h)}<br>擅长：${sk}<br>装备：${Object.values(h.eq).filter(Boolean).map(id => D.ITEMS[id].name).join('、')}<br>每周薪水 ${D.heroWage(h)} 金`, options: [
    { label: `「欢迎加入！」`, note: full ? '队伍已满' : `${cost} 金`, disabled: pl.gold < cost || full, fn: () => { pl.gold -= cost; W.hireHero(h); UI.toast(`${h.name} 加入了你的队伍`); Game.tavern(s); } },
    { label: '「以后再说吧。」', fn: () => Game.tavern(s) },
  ] });
};
Game.dirText = function (from, p) {
  const a = Math.atan2(p.y - from.y, p.x - from.x);
  const dirs = ['东', '东南', '南', '西南', '西', '西北', '北', '东北'];
  return `城${dirs[(Math.round(a / (Math.PI / 4)) + 8) % 8]}方约 ${Math.round(U.dist(from.x, from.y, p.x, p.y) / 10)} 里处`;
};
Game.rumor = function () {
  const r = Math.random();
  const live = D.FACTIONS.filter(f => G.factions[f.id].alive);
  if (r < 0.35) {
    const f = U.pick(live);
    const c = G.factions[f.id].campaign;
    if (c) return `听说${f.name}的大军正向${W.S(c.target).name}进发。`;
  }
  if (r < 0.65) {
    const l = U.pick(G.lords.filter(l => l.partyId));
    if (l) { const p = W.P(l.partyId); const s = G.settlements.slice().sort((a, b) => U.dist(a.x, a.y, p.x, p.y) - U.dist(b.x, b.y, p.x, p.y))[0]; return `有人看到${l.name}带着约${W.partySize(p)}人在${s.name}附近活动。`; }
  }
  const f = U.pick(live);
  const ss = G.settlements.filter(s => s.fac === f.id && s.type === 'town');
  const g = U.pick(D.PRODUCE[f.id].dear);
  return `${ss.length ? ss[0].name : f.name}的商人说，那里的${D.GOOD[g].name}很紧缺，价钱很高。`;
};

// ---------------- 村庄 ----------------
Game.recruitVillage = function (s) {
  const pl = G.player, pp = W.player();
  const t = D.TROOPS[s.cult + '_recruit'];
  const cost = D.troopRecruitCost(t);
  const room = W.partyLimit() + 1 - W.partySize(pp);
  const n = Math.min(s.recruits, room, Math.floor(pl.gold / cost));
  const text = `${s.recruits} 名年轻人愿意跟随你（${t.name}，每人 ${cost} 金）。<br>队伍空位：${room}`;
  UI.menu({ title: '招募志愿兵', text, options: [
    { label: `招募 ${Math.max(0, n)} 人`, note: `共 ${n * cost} 金`, disabled: n <= 0, fn: () => { pl.gold -= n * cost; s.recruits -= n; W.addTroops(pp, t.id, n); UI.toast(`招募了 ${n} 名${t.name}`); Game.openSettlement(s.id); } },
    { label: '返回', fn: () => Game.openSettlement(s.id) },
  ] });
};
Game.raidVillage = function (s) {
  if (!W.declarePersonalWar(s.fac)) { UI.alert('无法行动', '你的君主与该国处于和平状态，你不能擅自劫掠。'); return; }
  const n = 6 + Math.floor(s.prosperity / 6);
  const militia = [{ id: 'peasant', n, w: 0, xp: 0 }];
  if (s.prosperity > 60) militia.push({ id: s.cult + '_recruit', n: 4, w: 0, xp: 0 });
  const fake = { id: 'village_' + s.id, kind: 'village', fac: s.fac, name: `${s.name}村民`, troops: militia, gold: 0, x: s.x, y: s.y, temp: true };
  Game.battleChoice({ enemies: [fake], allies: [], title: `${s.name}的村民拿起草叉保卫家园！`, onWin: () => {
    const pl = G.player;
    const gold = U.ri(150, 350) + s.prosperity * 3;
    pl.gold += gold;
    const goods = {};
    for (let i = 0; i < U.ri(4, 8); i++) { const g = U.pick(D.PRODUCE[s.cult].cheap.concat(['grain', 'grain'])); if (W.cargoCount() < W.cargoLimit()) { pl.goods[g] = (pl.goods[g] || 0) + 1; goods[g] = (goods[g] || 0) + 1; } }
    s.looted = G.time + 24 * 7; s.prosperity = Math.max(5, s.prosperity - 20); s.recruits = 0;
    W.changeRel(s.fac, -5);
    W.log(`你洗劫了 ${s.name}。`);
    return `你洗劫了村庄，获得 ${gold} 金币与货物：${Object.keys(goods).map(k => D.GOOD[k].name + '×' + goods[k]).join('、') || '无'}。`;
  } });
};

// ---------------- 驻军 ----------------
Game.garrison = function (s) {
  const pp = W.player();
  const h = UI.open(`${s.name}·驻军`, '', { wide: true, onClose: () => Game.openSettlement(s.id) });
  const render = () => {
    let html = `<div class="sumline">驻军 ${W.count(s.garrison)} 人 · 队伍 ${W.partySize(pp)}/${W.partyLimit() + 1}（驻军军饷减半）</div><div class="cols"><div><h4>你的队伍</h4>`;
    pp.troops.forEach((st, i) => (html += `<div class="eqrow"><b>${D.TROOPS[st.id].name}</b><small>×${st.n}${st.w ? `（${st.w}伤）` : ''}</small><button class="sb g1" data-i="${i}">留1</button><button class="sb ga" data-i="${i}">全留</button></div>`));
    html += '</div><div><h4>驻军</h4>';
    s.garrison.forEach((st, i) => (html += `<div class="eqrow"><b>${D.TROOPS[st.id].name}</b><small>×${st.n}</small><button class="sb t1" data-i="${i}">带1</button><button class="sb ta" data-i="${i}">全带</button></div>`));
    html += '</div></div>';
    h.setContent(html);
    const move = (from, to, i, all, toParty) => {
      const st = from[i];
      let n = all ? st.n : 1;
      if (toParty) n = Math.min(n, W.partyLimit() + 1 - W.partySize(pp));
      if (n <= 0) { UI.toast('队伍已满'); return; }
      const w = Math.min(st.w, n);
      W.removeTroops(from, st.id, n);
      W.addTroops(to, st.id, n, 0, w);
      render();
    };
    h.body.querySelectorAll('.g1').forEach(b => b.onclick = () => move(pp.troops, s.garrison, +b.dataset.i, false));
    h.body.querySelectorAll('.ga').forEach(b => b.onclick = () => move(pp.troops, s.garrison, +b.dataset.i, true));
    h.body.querySelectorAll('.t1').forEach(b => b.onclick = () => move(s.garrison, pp.troops, +b.dataset.i, false, true));
    h.body.querySelectorAll('.ta').forEach(b => b.onclick = () => move(s.garrison, pp.troops, +b.dataset.i, true, true));
  };
  render();
};

// ---------------- 遭遇 ----------------
Game.strengthWord = function (mine, theirs) {
  const r = theirs / Math.max(1, mine);
  return r < 0.5 ? '<span class="good">不堪一击</span>' : r < 0.9 ? '<span class="good">较弱</span>' : r < 1.2 ? '势均力敌' : r < 2 ? '<span class="warn">较强</span>' : '<span class="warn">极其强大</span>';
};
Game.troopSummary = function (list) {
  const by = {};
  list.forEach(st => { const k = D.TROOPS[st.id].kind; by[k] = (by[k] || 0) + st.n - st.w; });
  return Object.keys(by).map(k => `${D.KIND_NAMES[k]}${by[k]}`).join(' · ');
};
Game.encounter = function (p, attacked) {
  const pp = W.player();
  const hostile = W.partiesHostile(p, pp);
  const myS = W.partyStrength(pp), theirS = W.partyStrength(p);
  let text = `你遇到了 <b>${p.name}</b>（${W.partySize(p)} 人：${Game.troopSummary(p.troops)}）<br>`;
  if (p.kind === 'lord') text += `所属：<span style="color:${W.facColor(p.fac)}">${W.facName(p.fac)}</span><br>`;
  text += `敌方实力：${Game.strengthWord(myS, theirS)}`;
  const opts = [];
  if (hostile) {
    text = (attacked ? '⚠ 敌人向你发起了攻击！<br>' : '') + text;
    opts.push({ label: '⚔ 进攻！', note: '亲自上阵指挥战斗', fn: () => Game.engage(p) });
    if (attacked) {
      const escape = W.partySpeed(pp) > W.partySpeed(p) * 1.05 ? 0.85 : 0.4;
      opts.push({ label: '🏃 撤退', note: `成功率约 ${Math.round(escape * 100)}%${escape < 0.8 ? '，失败会被迫应战' : ''}`, fn: () => {
        if (Math.random() < escape) { Game.escapeFrom(p); UI.toast('你成功摆脱了敌人'); }
        else {
          // 留下断后部队
          const lose = Math.ceil(W.count(pp.troops) * 0.12);
          for (let i = 0; i < lose; i++) { const st = U.pick(pp.troops); if (st) W.removeTroops(pp.troops, st.id, 1); }
          if (lose) W.log(`撤退时有 ${lose} 名士兵掩护断后，没能回来。`);
          Game.escapeFrom(p);
          UI.toast(lose ? `撤退成功，但损失了 ${lose} 名士兵` : '撤退成功');
        }
      } });
    } else opts.push({ label: '离开', fn: () => Game.escapeFrom(p) });
  } else {
    if (p.kind === 'lord') {
      const l = W.L(p.lordId);
      opts.push({ label: '💬 交谈', fn: () => Game.talkLord(l, () => Game.encounter(p, false)) });
    } else if (p.kind === 'caravan') {
      opts.push({ label: '💬 交谈', fn: () => UI.alert(p.name, `「我们只是来往于城镇之间的商人。」<br>商队带着：${Object.keys(p.goods || {}).map(k => D.GOOD[k].name).join('、') || '一些杂货'}`, () => Game.encounter(p, false)) });
    }
    const verb = p.kind === 'caravan' ? '💰 打劫商队' : '⚔ 发动攻击';
    opts.push({ label: verb, note: `将与${W.facName(p.fac)}开战`, cls: 'danger', fn: () => {
      if (!W.declarePersonalWar(p.fac)) { UI.alert('无法行动', '你的君主与该国处于和平状态。若要开战，必须先离开你的阵营。'); return; }
      Game.engage(p);
    } });
    opts.push({ label: '离开', fn: () => {} });
  }
  UI.menu({ title: '遭遇', art: hostile ? '⚔' : '🚩', text, options: opts });
};
Game.escapeFrom = function (p) {
  const pp = W.player();
  G.player.captiveUntil = G.time + 2.5;
  const a = Math.atan2(pp.y - p.y, pp.x - p.x);
  let tx = pp.x + Math.cos(a) * 40, ty = pp.y + Math.sin(a) * 40;
  if (!W.isLand(tx, ty)) { tx = pp.x; ty = pp.y; }
  W.playerMoveTo(tx, ty);
  p.chase = null;
  p.ai.next = G.time + 3;
};
Game.gatherSides = function (enemy, center) {
  const pp = W.player();
  const cx = center ? center.x : pp.x, cy = center ? center.y : pp.y;
  const enemies = [enemy], allies = [];
  G.parties.forEach(q => {
    if (q === enemy || q === pp || q.battleId || q.inSettlement || q.kind === 'caravan') return;
    if (U.dist(q.x, q.y, cx, cy) > 45) return;
    if (W.partiesHostile(q, pp) && !W.partiesHostile(q, enemy)) enemies.push(q);
    else if (q.kind === 'lord' && !W.partiesHostile(q, pp) && W.partiesHostile(q, enemy)) allies.push(q);
  });
  return { enemies, allies };
};
Game.engage = function (p) {
  const { enemies, allies } = Game.gatherSides(p);
  let title = `与 ${p.name} 交战！`;
  if (enemies.length > 1) title += ` 附近的 ${enemies.slice(1).map(e => e.name).join('、')} 也加入了战斗。`;
  if (allies.length) title += ` ${allies.map(a => a.name).join('、')} 前来助战！`;
  Game.battleChoice({ enemies, allies, title });
};
Game.joinBattleMenu = function (bid) {
  const bt = G.battles.find(b => b.id === bid);
  if (!bt) return;
  const pp = W.player();
  const A = bt.A.map(W.P).filter(Boolean), Bs = bt.B.map(W.P).filter(Boolean);
  const opts = [];
  const sideOk = (mine, other) => mine.length && other.length && !mine.some(q => W.partiesHostile(q, pp)) && other.some(q => W.partiesHostile(q, pp));
  if (sideOk(A, Bs)) opts.push({ label: `援助 ${A.map(q => q.name).join('、')}`, fn: () => Game.joinAIBattle(bt, A, Bs) });
  if (sideOk(Bs, A)) opts.push({ label: `援助 ${Bs.map(q => q.name).join('、')}`, fn: () => Game.joinAIBattle(bt, Bs, A) });
  opts.push({ label: '旁观，离开', fn: () => Game.escapeFrom(A[0] || Bs[0] || pp) });
  UI.menu({ title: '战斗', art: '⚔', text: `${A.map(q => `${q.name}(${W.partySize(q)})`).join('、')} 正与 ${Bs.map(q => `${q.name}(${W.partySize(q)})`).join('、')} 激战！`, options: opts });
};
Game.joinAIBattle = function (bt, allies, enemies) {
  G.battles.splice(G.battles.indexOf(bt), 1);
  allies.concat(enemies).forEach(q => (q.battleId = null));
  Game.battleChoice({ enemies, allies: allies.filter(q => q.kind === 'lord'), allyTroopsOnly: allies.filter(q => q.kind !== 'lord'), title: '你加入了战斗！' });
};

// 选择：亲自指挥 / 自动结算
Game.battleChoice = function (ctx) {
  const pp = W.player();
  const myS = W.partyStrength(pp) + ctx.allies.reduce((a, q) => a + W.partyStrength(q), 0);
  const theirS = ctx.enemies.reduce((a, q) => a + W.partyStrength(q), 0) + (ctx.siege && !ctx.defending ? W.strength(ctx.siege.garrison) * 1.7 : 0);
  const healthy = W.healthy(pp.troops);
  UI.menu({ title: ctx.siege ? '攻城战' : '战斗', art: '⚔', text: `${ctx.title || ''}<br><br>我方：${W.healthy(pp.troops) + 1 + ctx.allies.reduce((a, q) => a + W.healthy(q.troops), 0)} 人<br>敌方：${ctx.enemies.reduce((a, q) => a + W.healthy(q.troops), 0) + (ctx.siege && !ctx.defending ? W.healthy(ctx.siege.garrison) : 0)} 人 · 实力${Game.strengthWord(myS, theirS)}`, options: [
    { label: '⚔ 亲自上阵', note: '进入实时战斗', fn: () => Game.realBattle(ctx) },
    { label: '📯 指挥部队作战', note: healthy || ctx.allies.length ? '自动结算（战术技能有加成）' : '你没有可战斗的士兵', disabled: !healthy && !ctx.allies.length, fn: () => Game.autoBattle(ctx) },
  ] });
};

// 构造战斗双方
Game.expand = function (list, owner) {
  const out = [];
  list.forEach(st => { for (let i = 0; i < st.n - st.w; i++) out.push({ troopId: st.id, owner }); });
  return out;
};
Game.ownerList = function (owner) {
  if (owner === 'player') return W.player().troops;
  if (owner.startsWith('g:')) { const s = W.S(owner.slice(2)); return s ? s.garrison : null; }
  const p = Game.tempParties && Game.tempParties[owner] || W.P(owner);
  return p ? p.troops : null;
};
Game.realBattle = function (ctx) {
  const pp = W.player();
  Game.tempParties = {};
  ctx.enemies.forEach(e => { if (e.temp) Game.tempParties[e.id] = e; });
  let team0 = Game.expand(pp.troops, 'player');
  let allyUnits = [];
  ctx.allies.forEach(a => (allyUnits = allyUnits.concat(Game.expand(a.troops, a.id))));
  (ctx.allyTroopsOnly || []).forEach(a => (allyUnits = allyUnits.concat(Game.expand(a.troops, a.id))));
  let team1 = [];
  ctx.enemies.forEach(e => (team1 = team1.concat(Game.expand(e.troops, e.id))));
  if (ctx.siege) {
    const g = Game.expand(ctx.siege.garrison, 'g:' + ctx.siege.id);
    if (ctx.defending) allyUnits = allyUnits.concat(g); else team1 = team1.concat(g);
  }
  U.shuffle(team0); U.shuffle(allyUnits); U.shuffle(team1);
  team0 = team0.concat(allyUnits);
  const ter = W.terAt(pp.x, pp.y);
  ctx.before = Game.snapshotStrength(ctx);
  Game.screen = 'battle';
  B.start({
    mode: ctx.siege ? 'siege' : 'field', biome: W.TER_BIOME[ter] || 'plains', teams: [team0, team1], heroes: W.companions(),
    siegeAttacker: ctx.siege ? (ctx.defending ? 1 : 0) : null, title: ctx.siege ? (ctx.defending ? '守住城墙！' : '攻上城墙！') : '战斗开始！',
    onEnd: res => { Game.screen = 'map'; Game.applyRealResult(ctx, res); },
  });
  if (ctx.siege && ctx.defending) B.orders[0] = { inf: 'defend', arc: 'defend', cav: 'defend' };
};
Game.snapshotStrength = ctx => ctx.enemies.reduce((a, q) => a + W.partyStrength(q), 0) + (ctx.siege && !ctx.defending ? W.strength(ctx.siege.garrison) : 0);

Game.applyRealResult = function (ctx, res) {
  const pl = G.player;
  const enemyFallen = [];
  res.fallen.forEach(f => {
    const list = Game.ownerList(f.owner);
    if (!list) return;
    const st = list.find(s => s.id === f.troopId);
    if (!st) return;
    if (f.wounded) st.w = Math.min(st.n, st.w + 1);
    else W.removeTroops(list, f.troopId, 1);
    if (f.team === 1 && f.wounded) enemyFallen.push(f.troopId);
  });
  pl.hpFrac = U.clamp(res.playerHpFrac, 0.1, 1);
  (res.heroHp || []).forEach(([id, f]) => {
    const h = G.heroes.find(x => x.id === id);
    if (!h) return;
    h.hpFrac = U.clamp(f, 0.1, 1);
    if (res.heroKills[id]) W.giveHeroXp(h, res.heroKills[id] * 25);
  });
  pl.kills += res.kills;
  W.giveXp(res.killList.reduce((a, id) => a + D.TROOPS[id].tier * 25, 0));
  if (res.winner === 0) Game.victory(ctx, enemyFallen, res.fallen.filter(f => f.team === 1).map(f => f.troopId));
  else Game.defeat(ctx, res.retreat);
};
Game.autoBattle = function (ctx) {
  const pp = W.player(), pl = G.player;
  ctx.before = Game.snapshotStrength(ctx);
  const A = [pp.troops].concat(ctx.allies.map(a => a.troops)).concat((ctx.allyTroopsOnly || []).map(a => a.troops));
  const Bl = ctx.enemies.map(e => e.troops);
  if (ctx.siege) (ctx.defending ? A : Bl).push(ctx.siege.garrison);
  const enemyBefore = {};
  Bl.forEach(l => l.forEach(st => (enemyBefore[st.id] = (enemyBefore[st.id] || 0) + st.w)));
  const tac = 1 + W.pskill('tactics') * 0.07;
  const res = W.autoResolve(A, Bl, {
    bonusA: tac * (ctx.siege && ctx.defending ? 1.5 : ctx.siege ? 0.8 : 1), bonusB: ctx.siege && !ctx.defending ? 1.5 : 1,
    heroA: W.heroStrength(), dmgMulA: 0.5, woundA: 0.35 + W.pskill('surgery') * 0.05, woundB: 0.5,
  });
  const lost = res.cas[0], killed = res.cas[1];
  const lostTxt = Object.keys(lost).map(k => `${D.TROOPS[k].name} 阵亡${lost[k].killed}/受伤${lost[k].wounded}`).join('<br>') || '无';
  if (Math.random() < 0.3 && res.winner === 0) pl.hpFrac = Math.max(0.3, pl.hpFrac - 0.2);
  const enemyWounded = [], enemyAll = [];
  Object.keys(killed).forEach(k => { for (let i = 0; i < killed[k].wounded; i++) enemyWounded.push(k); for (let i = 0; i < killed[k].killed + killed[k].wounded; i++) enemyAll.push(k); });
  ctx.autoReport = `我方伤亡：<br>${lostTxt}`;
  if (res.winner === 0) Game.victory(ctx, enemyWounded, enemyAll);
  else Game.defeat(ctx, false);
};

Game.victory = function (ctx, woundedEnemies, fallenEnemies) {
  const pl = G.player, pp = W.player();
  const str = ctx.before || 10;
  // 经验与声望
  const nTroops = Math.max(5, W.count(pp.troops));
  const xpEach = Math.min(90, Math.round(str * 7 / nTroops));
  pp.troops.forEach(st => (st.xp += xpEach * (st.n - st.w)));
  W.giveXp(str * 6 + 40);
  W.companions().forEach(h => W.giveHeroXp(h, str * 5 + 30));
  const isLord = ctx.enemies.some(e => e.kind === 'lord');
  const ren = Math.max(1, Math.round(Math.sqrt(str) * 1.4 + (isLord ? 5 : 0) + (ctx.siege ? 15 : 0)));
  W.addRenown(ren);
  pl.battlesWon++; pl.recentWin = 12;
  pl.morale = Math.min(100, pl.morale + 6);
  // 战利品
  let gold = Math.round(ctx.enemies.reduce((a, e) => a + (e.gold || 0), 0) * (0.5 + W.pskill('looting') * 0.05) + fallenEnemies.length * U.ri(2, 6));
  pl.gold += gold;
  const loot = [];
  const lootChance = 0.05 + W.pskill('looting') * 0.02;
  fallenEnemies.forEach(id => {
    if (loot.length >= 10 || Math.random() > lootChance) return;
    const eq = D.TROOPS[id].eq;
    const opts = ['m', 'r', 's', 'h', 'b', 'l', 'horse'].map(k => eq[k]).filter(x => x && D.ITEMS[x] && D.ITEMS[x].price > 15);
    if (opts.length) loot.push(U.pick(opts));
  });
  // 商队货物
  const goodsGot = {};
  ctx.enemies.forEach(e => {
    if (!e.goods) return;
    Object.keys(e.goods).forEach(g => {
      const n = Math.min(e.goods[g], W.cargoLimit() - W.cargoCount());
      if (n > 0) { pl.goods[g] = (pl.goods[g] || 0) + n; goodsGot[g] = n; }
    });
  });
  // 俘虏
  let caught = 0;
  woundedEnemies.forEach(id => {
    if (W.count(pp.prisoners) >= W.prisonerLimit()) return;
    W.addTroops(pp.prisoners, id, 1); caught++;
  });
  // 外交后果
  ctx.enemies.forEach(e => {
    if (e.kind === 'lord') { W.changeRel(e.fac, -3); const l = W.L(e.lordId); if (l) W.log(`你击败了 ${l.name} 的部队！`); }
    if (e.kind === 'caravan') { W.changeRel(e.fac, -5); }
  });
  // 任务
  let questTxt = '';
  if (pl.quest && ctx.enemies.some(e => e.id === pl.quest.partyId)) {
    pl.gold += pl.quest.reward;
    questTxt = `<br>📜 赏金任务完成！获得 ${pl.quest.reward} 金。`;
    W.addRenown(3);
    pl.quest = null;
  }
  // 移除敌军
  ctx.enemies.forEach(e => { if (!e.temp) W.removeParty(e); });
  ctx.allies.forEach(a => { a.battleId = null; if (W.healthy(a.troops) <= 0) W.removeParty(a); });
  let extra = '';
  if (ctx.onWin) extra = '<br>' + ctx.onWin();
  if (ctx.siege && !ctx.defending) extra += '<br>' + Game.captureByPlayer(ctx.siege);
  if (ctx.siege && ctx.defending) {
    ctx.siege.siege = null;
    G.parties.filter(q => q.ai.mode === 'siege' && q.ai.target === ctx.siege.id).forEach(q => W.removeParty(q));
    W.addRenown(10); W.changeRel(ctx.siege.fac, 8);
    extra += `<br>你成功守住了 ${ctx.siege.name}！`;
  }
  const text = `🏆 <b>胜利！</b><br>获得声望 ${ren} · 金币 ${gold} · 每名士兵经验 +${xpEach}` +
    (caught ? `<br>俘虏了 ${caught} 名敌人` : '') +
    (Object.keys(goodsGot).length ? `<br>缴获货物：${Object.keys(goodsGot).map(k => D.GOOD[k].name + '×' + goodsGot[k]).join('、')}` : '') +
    questTxt + extra + (ctx.autoReport ? '<br><br>' + ctx.autoReport : '');
  const after = () => {
    if (loot.length) Game.lootMenu(loot);
    if (W.checkVictory() && !G.wonShown) {
      G.wonShown = true;
      UI.alert('天下一统！', `所有城镇都已归于 ${W.facName(W.playerFac())} 的旗帜之下。你的名字将永载史册！<br><br>（你可以继续游戏）`);
    }
  };
  UI.menu({ title: '战斗结果', art: '🏆', text, options: [{ label: '继续', fn: after }] });
};
Game.lootMenu = function (loot) {
  const pl = G.player;
  const h = UI.open('战利品', '');
  const render = () => {
    let html = `<div class="sumline">背包 ${pl.inv.length}/40</div>`;
    loot.forEach((id, i) => { const it = D.ITEMS[id]; html += `<div class="eqrow"><b>${it.name}</b><small>${D.SLOT_NAMES[it.slot]} · ${D.itemDesc(it)} · 价值${it.price}</small><button class="sb take" data-i="${i}">拿取</button></div>`; });
    html += '<div class="btnrow"><button class="sb all">全部拿取</button><button class="sb done">完成</button></div>';
    h.setContent(html);
    h.body.querySelectorAll('.take').forEach(b => b.onclick = () => {
      if (pl.inv.length >= 40) { UI.toast('背包已满'); return; }
      pl.inv.push(loot[+b.dataset.i]); loot.splice(+b.dataset.i, 1);
      if (!loot.length) h.close(); else render();
    });
    h.body.querySelector('.all').onclick = () => {
      while (loot.length && pl.inv.length < 40) pl.inv.push(loot.shift());
      if (loot.length) UI.toast('背包已满'); else h.close();
      render();
    };
    h.body.querySelector('.done').onclick = () => h.close();
  };
  render();
};
Game.defeat = function (ctx, retreat) {
  const pl = G.player, pp = W.player();
  ctx.allies.forEach(a => (a.battleId = null));
  if (ctx.siege && ctx.siege.siege && ctx.siege.siege.by === 'player') ctx.siege.siege = null;
  if (retreat) {
    Game.escapeFrom(ctx.enemies[0] || pp);
    UI.alert('撤退', '你带领残部撤离了战场。' + (ctx.autoReport ? '<br>' + ctx.autoReport : ''));
    return;
  }
  // 被俘
  const lostTroops = W.count(pp.troops);
  pp.troops.forEach(st => { const keep = Math.floor(st.n * U.rr(0, 0.35)); st.n = keep; st.w = Math.min(st.w, keep); });
  pp.troops = pp.troops.filter(st => st.n > 0);
  const lostGold = Math.round(pl.gold * U.rr(0.1, 0.3));
  pl.gold -= lostGold;
  pp.prisoners = [];
  pl.hpFrac = 0.2;
  W.companions().forEach(h => (h.hpFrac = 0.2));
  pl.morale = Math.max(0, pl.morale - 20);
  const hours = U.ri(24, 60);
  W.log(`你在战斗中被击败并被俘虏了。`);
  pl.captiveUntil = G.time + hours + 3;
  pp.frozen = true;
  W.advance(hours);
  W.pendingEvent = null;
  pp.frozen = false;
  const safe = G.settlements.filter(s => !W.hostile(W.playerFac(), s.fac) && s.type !== 'village');
  const s = safe.length ? U.pick(safe) : U.pick(G.settlements);
  pp.x = s.x + 15; pp.y = s.y + 15; pp.tx = pp.x; pp.ty = pp.y;
  MV.follow = true;
  UI.alert('战败被俘', `你的部队被击溃，你本人被俘虏。<br>失去了约 ${lostTroops - W.count(pp.troops)} 名士兵和 ${lostGold} 金币。<br>${hours} 小时后，你趁看守不备逃了出来，来到了${s.name}附近。`);
};

// ---------------- 攻城 ----------------
Game.siegeMenu = function (s) {
  if (!W.declarePersonalWar(s.fac)) { UI.alert('无法行动', '你的君主与该国处于和平状态。'); return; }
  const pp = W.player();
  const def = W.defenseStrength(s);
  const allies = G.parties.filter(q => q.kind === 'lord' && !W.partiesHostile(q, pp) && W.hostile(q.fac, s.fac) && U.dist(q.x, q.y, s.x, s.y) < 60);
  const atk = W.partyStrength(pp) + allies.reduce((a, q) => a + W.partyStrength(q), 0);
  const ready = s.siege && s.siege.by === 'player' && s.siege.ladders;
  const text = `你在${s.name}城外扎下营寨。<br>守军 ${W.count(s.garrison)} 人，城防实力：${Game.strengthWord(atk, def)}${allies.length ? `<br>友军：${allies.map(q => q.name).join('、')}` : ''}<br><br>${ready ? '攻城梯已经造好，随时可以发起进攻。' : '你需要先建造攻城梯（约10小时）。'}`;
  const opts = [];
  if (!ready) opts.push({ label: '🪜 建造攻城梯', note: '等待10小时（期间可能遭到敌军援军攻击）', fn: () => {
    s.siege = { fac: W.playerFac(), start: G.time, by: 'player' };
    Game.wait(10, { title: '建造攻城梯', done: () => { if (s.siege && s.siege.by === 'player') { s.siege.ladders = true; Game.siegeMenu(s); } } });
  } });
  else {
    opts.push({ label: '⚔ 发起攻城', fn: () => Game.assault(s, false) });
  }
  opts.push({ label: '撤围离开', fn: () => { s.siege = null; } });
  UI.menu({ title: `围攻 ${s.name}`, art: '🏰', text, options: opts });
};
Game.assault = function (s, withAllies) {
  const pp = W.player();
  const allies = G.parties.filter(q => q.kind === 'lord' && !W.partiesHostile(q, pp) && W.hostile(q.fac, s.fac) && U.dist(q.x, q.y, s.x, s.y) < 60);
  const lordsIn = G.parties.filter(q => q.inSettlement === s.id && q.kind === 'lord' && W.partiesHostile(q, pp));
  Game.battleChoice({ enemies: lordsIn, allies, siege: s, title: `向 ${s.name} 的城墙发起冲锋！` });
};
Game.defendSiege = function (s) {
  const pp = W.player();
  const besiegers = G.parties.filter(q => q.ai.mode === 'siege' && q.ai.target === s.id);
  if (!besiegers.length) { s.siege = null; UI.toast('围城的敌军已经撤走了'); return; }
  const allies = G.parties.filter(q => q.inSettlement === s.id && q.kind === 'lord' && !W.partiesHostile(q, pp));
  Game.battleChoice({ enemies: besiegers, allies, siege: s, defending: true, title: `${besiegers.map(q => q.name).join('、')} 正在攻城，与守军一同坚守城墙！` });
};
Game.captureByPlayer = function (s) {
  const pl = G.player;
  let msg;
  if (pl.status === 'vassal') {
    W.captureSettlement(s, pl.liege, 'player');
    msg = `你为${W.facName(pl.liege)}攻下了${s.name}，国王将它封给了你！`;
    W.changeRel(pl.liege, 10);
  } else if (pl.status === 'merc') {
    W.captureSettlement(s, pl.liege, null);
    const reward = s.type === 'town' ? 1500 : 800;
    pl.gold += reward;
    msg = `你为${W.facName(pl.liege)}攻下了${s.name}，获得赏金 ${reward} 金。`;
    W.changeRel(pl.liege, 10);
  } else {
    W.becomeKing();
    W.captureSettlement(s, 'player', 'player');
    msg = `${s.name}成为了你的领地！你现在是 ${pl.kingdomName} 的君主。<br><small>记得在「管理驻军」中留下士兵防守。</small>`;
  }
  const f = G.factions[pl.liege];
  if (f) f.campaign = null;
  s.siege = null;
  return '🚩 ' + msg;
};

// ---------------- 竞技场 ----------------
Game.arenaMenu = function (s) {
  const pl = G.player;
  UI.menu({ title: `${s.name}·竞技场`, art: '🏟', text: '角斗士们在沙地上捉对厮杀。参加一场 8 人混战，坚持到最后的人赢得奖金。<br>（使用练习武器，不会致命）', options: [
    { label: '参加混战', note: '胜利奖金 150 金', fn: () => Game.arena(s, 0) },
    { label: '下注 100 金后参加', note: '胜利可得 150 + 400 金', disabled: pl.gold < 100, fn: () => { pl.gold -= 100; Game.arena(s, 100); } },
    { label: '返回', fn: () => Game.openSettlement(s.id) },
  ] });
};
Game.arena = function (s, bet) {
  const teams = [[]];
  for (let i = 1; i <= 7; i++) teams.push([{ name: U.pick(['角斗士', '老练角斗士', '佣兵', '新手', '冠军']) + i, tier: U.ri(2, 5) }]);
  Game.screen = 'battle';
  B.start({ mode: 'arena', teams, title: '竞技开始！战斗到最后一人！', onEnd: res => {
    Game.screen = 'map';
    const pl = G.player;
    if (res.winner === 0 && !res.retreat) {
      const prize = 150 + bet * 5;
      pl.gold += prize; W.addRenown(3); W.giveXp(200);
      UI.alert('竞技场冠军！', `你击倒了 ${res.kills} 名对手，赢得了 ${prize} 金币！观众为你欢呼。`, () => Game.openSettlement(s.id));
    } else {
      const c = res.kills * 20;
      pl.gold += c; W.giveXp(res.kills * 40);
      UI.alert('落败', `你击倒了 ${res.kills} 名对手后倒下了。${c ? `获得安慰奖 ${c} 金。` : ''}`, () => Game.openSettlement(s.id));
    }
  } });
};

// ---------------- 营地 ----------------
Game.campMenu = function () {
  const pl = G.player;
  UI.menu({ title: '营地', art: '⛺', noClose: false, text: `${W.dateStr()} · ${U.esc(pl.name)}`, options: [
    { label: '⏳ 扎营等待 6 小时', fn: () => Game.wait(6, { title: '扎营' }) },
    { label: '⌨ 控制台（作弊）', fn: () => UI.consolePanel() },
    { label: '🛏 扎营休息 24 小时', fn: () => Game.wait(24, { title: '扎营休息' }) },
    { label: '💾 保存游戏', fn: () => Game.saveMenu() },
    { label: '📂 读取存档', fn: () => Game.loadMenu(false) },
    { label: '⚙ 设置', fn: () => Game.settings() },
    { label: '❓ 游戏说明', fn: () => Game.help() },
    { label: '🏠 返回标题', fn: () => UI.confirm('返回标题', '未保存的进度将会丢失（游戏每天会自动存到「自动存档」）。', () => Main.toTitle()) },
  ] });
};
Game.saveMenu = function () {
  const opts = [1, 2, 3].map(i => {
    const m = W.saveMeta(i);
    return { label: `存档 ${i}`, note: m ? `${m.name} Lv${m.level} · ${m.date}` : '空', fn: () => { if (W.save(i)) UI.toast('已保存'); else UI.toast('保存失败'); } };
  });
  opts.push({ label: '返回', fn: () => Game.campMenu() });
  UI.menu({ title: '保存游戏', options: opts });
};
Game.loadMenu = function (fromTitle) {
  const opts = ['auto', 1, 2, 3].map(i => {
    const m = W.saveMeta(i);
    return { label: i === 'auto' ? '自动存档' : `存档 ${i}`, note: m ? `${m.name} Lv${m.level} · ${m.date}` : '空', disabled: !m, fn: () => Main.loadGame(i) };
  });
  opts.push({ label: '返回', fn: () => (fromTitle ? Main.toTitle() : Game.campMenu()) });
  UI.menu({ title: '读取存档', options: opts });
};
Game.settings = function () {
  UI.menu({ title: '设置', text: `战斗规模：同时在场 ${G.battleSize} 人（手机性能较弱时调低）<br>战斗速度：×${G.battleSpeed}（越小越慢，更容易反应）<br>大地图速度：×${G.speed}`, options: [
    { label: '战斗速度 ×0.5（很慢）', fn: () => { G.battleSpeed = 0.5; Game.settings(); } },
    { label: '战斗速度 ×0.7（默认）', fn: () => { G.battleSpeed = 0.7; Game.settings(); } },
    { label: '战斗速度 ×1.0（原速）', fn: () => { G.battleSpeed = 1; Game.settings(); } },
    { label: '战斗规模 40', fn: () => { G.battleSize = 40; Game.settings(); } },
    { label: '战斗规模 70', fn: () => { G.battleSize = 70; Game.settings(); } },
    { label: '战斗规模 100', fn: () => { G.battleSize = 100; Game.settings(); } },
    { label: '战斗规模 150', fn: () => { G.battleSize = 150; Game.settings(); } },
    { label: '返回', fn: () => Game.campMenu() },
  ] });
};
Game.help = function () {
  UI.open('游戏说明', `
  <h4>大地图</h4>点击地面移动，点击城镇/城堡/村庄前往并进入，点击部队追击或交谈。拖动平移，双指缩放。时间只在你移动或等待时流逝。
  <h4>经营队伍</h4>在村庄招募志愿兵，在酒馆雇佣佣兵。战斗获得的经验可以在「队伍」中升级士兵（不同分支：步兵/远程/骑兵）。注意食物（🍞）、士气与每周军饷。
  <h4>战斗</h4>左侧摇杆移动；「攻击」近战点击挥砍，远程按住瞄准、松开射击，按住攻击键再拖动可向任意方向瞄准（骑射可侧射、后射）；骑马手持骑枪高速冲锋时按住攻击即为「骑枪冲刺」，伤害随马速大增。「格挡」按住防御正面攻击。顶部选择兵种并下令：坚守（在你所在位置列阵）/ 跟随 / 冲锋。
  <h4>同伴（NPC）</h4>城镇酒馆里会遇到各具特长的同伴，招募后与你并肩作战、升级成长，并可在「同伴」面板中加点、换装。队伍技能（外科、急救、寻路、战术、教练、交易、掠夺）取你和同伴中最高的一项。同伴之间不会争吵。
  <h4>控制台</h4>「营地 → 控制台」可以作弊：加钱、升级加点、招兵、拿物品装备、粮食消耗0、招募同伴、无敌、远程无限弹药。
  <h4>难度</h4>你只受到 1/4 伤害，你的部队只受到 1/2 伤害。战斗速度可在「营地 → 设置」中调整（默认 ×0.7）。
  <h4>贸易</h4>每个地区都有便宜的特产和紧缺的商品，低买高卖赚取差价。
  <h4>争霸</h4>积累声望后向国王申请成为雇佣兵或封臣；或独立攻城，建立自己的王国。攻城需要先建造攻城梯。占领所有城镇即可统一大陆。
  <h4>存档</h4>存档只保存在本应用的私有空间里，卸载应用时会被系统一并删除，不会留下任何文件。`, { wide: true });
};
