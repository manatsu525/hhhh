'use strict';
// ============ 静态游戏数据：势力 / 物品 / 兵种 / 商品 / 地名 ============
const D = {};

D.MAP_W = 3000;
D.MAP_H = 2000;

D.FACTIONS = [
  { id: 'leon', name: '雄狮王国', short: '雄狮', color: '#d64541', color2: '#f3d36b', biome: 'plains', cx: 1400, cy: 980,
    desc: '中央平原上的骑士王国，以重甲骑士的冲锋闻名。' },
  { id: 'frost', name: '霜狼部族', short: '霜狼', color: '#3f7fd8', color2: '#dfe8f5', biome: 'snow', cx: 650, cy: 420,
    desc: '北方冰原上的战士部族，步兵凶悍，擅长投掷飞斧。' },
  { id: 'birch', name: '白桦公国', short: '白桦', color: '#8e5bd1', color2: '#e8dcf8', biome: 'forest', cx: 1950, cy: 380,
    desc: '东北密林中的公国，弓手与贵族骑兵兼备。' },
  { id: 'khan', name: '苍狼汗国', short: '汗国', color: '#e8862d', color2: '#3b2a17', biome: 'steppe', cx: 2480, cy: 1000,
    desc: '东方草原的游牧汗国，骑射手来去如风。' },
  { id: 'sultan', name: '金沙苏丹国', short: '苏丹', color: '#d8b93a', color2: '#5a2d0c', biome: 'desert', cx: 1800, cy: 1620,
    desc: '南方沙漠的富庶国度，马穆鲁克骑兵装备精良。' },
  { id: 'high', name: '山岭共和国', short: '山岭', color: '#3fae5b', color2: '#f2f2f2', biome: 'hills', cx: 620, cy: 1380,
    desc: '西南山地的商业共和国，长枪兵与重弩手组成坚阵。' },
];
D.FAC = {};
D.FACTIONS.forEach(f => (D.FAC[f.id] = f));
D.FAC.bandit = { id: 'bandit', name: '匪徒', short: '匪', color: '#555', color2: '#222' };
D.FAC.player = { id: 'player', name: '你的王国', short: '你', color: '#ffffff', color2: '#c33' };
D.FAC.neutral = { id: 'neutral', name: '中立', short: '民', color: '#bbb', color2: '#666' };

D.SETTLEMENT_NAMES = {
  leon: { town: ['雷恩城', '金穗城'], castle: ['白鹰堡', '红石堡', '狮鬃堡'], village: ['麦田村', '柳溪村', '石桥村', '风车村', '橡木村', '葡萄园村'] },
  frost: { town: ['寒铁港', '冰牙城'], castle: ['霜狼要塞', '雪峰堡', '鲸骨堡'], village: ['鲸湾村', '松针村', '白岩村', '鹿角村', '冰川村', '渔火村'] },
  birch: { town: ['白桦城', '琥珀城'], castle: ['黑森堡', '银河堡', '熊巢堡'], village: ['蜂蜜村', '熊掌村', '苔原村', '桦林村', '貂皮村', '蘑菇村'] },
  khan: { town: ['金帐城', '苍狼城'], castle: ['鹰嘴堡', '风蚀堡', '烽燧堡'], village: ['牧马村', '白毡村', '草海村', '狼泉村', '鹰猎村', '奶酒村'] },
  sultan: { town: ['沙丘城', '绿洲城'], castle: ['月牙堡', '烈日堡', '蝎尾堡'], village: ['椰枣村', '泉水村', '盐湖村', '驼铃村', '香料村', '棉田村'] },
  high: { town: ['鹰岩城', '青峰城'], castle: ['峭壁堡', '石门堡', '云顶堡'], village: ['牧羊村', '葡萄村', '溪谷村', '栗树村', '铁矿村', '奶酪村'] },
};

D.LORD_NAMES = {
  leon: { king: '哈罗德国王', title: '伯爵', names: ['艾德蒙', '亚瑟', '戈弗雷', '雷蒙德', '西奥多', '兰斯洛'] },
  frost: { king: '拉格纳王', title: '雅尔', names: ['比约恩', '埃里克', '西格德', '乌尔夫', '哈康', '托尔芬'] },
  birch: { king: '雅罗斯拉夫大公', title: '波雅尔', names: ['鲍里斯', '伊戈尔', '斯维亚托', '弗拉基米尔', '德米特里', '奥列格'] },
  khan: { king: '巴图大汗', title: '那颜', names: ['帖木尔', '合撒儿', '速不台', '哲别', '拖雷', '阿术'] },
  sultan: { king: '萨拉丁苏丹', title: '埃米尔', names: ['哈桑', '优素福', '卡西姆', '法里德', '穆拉德', '纳赛尔'] },
  high: { king: '洛伦佐执政官', title: '城主', names: ['马可', '卢卡', '乔瓦尼', '费德里科', '安东尼奥', '彼得罗'] },
};

// ---------------- 物品 ----------------
D.ITEMS = {};
function _add(it) { D.ITEMS[it.id] = it; return it; }
// 近战: dmg 伤害, dt 伤害类型, reach 攻击距离, spd 攻速, hands 单/双手, couch 可骑枪冲刺
function M(id, name, price, dmg, dt, reach, spd, hands, cult, extra) {
  return _add(Object.assign({ id, name, slot: 'melee', price, dmg, dt, reach, spd, hands, cult }, extra || {}));
}
// 远程: kind bow/xbow/throw, range 射程, reload 装填时间, ammo 弹药, mount 可骑乘使用
function R(id, name, price, kind, dmg, dt, range, reload, ammo, mount, cult) {
  return _add({ id, name, slot: 'ranged', price, kind, dmg, dt, range, reload, ammo, mount, cult });
}
function S(id, name, price, hp, cult) { return _add({ id, name, slot: 'shield', price, hp, cult }); }
function A(id, name, slot, price, armor, cult) { return _add({ id, name, slot, price, armor, cult }); }
// 马: spd 最高速度, man 转向, hp, armor, chg 冲撞, ride 需要骑术
function H(id, name, price, spd, man, hp, armor, chg, ride, cult) {
  return _add({ id, name, slot: 'horse', price, spd, man, hp, armor, chg, ride, cult });
}

M('knife', '小刀', 15, 13, 'cut', 24, 1.35, 1, 'any');
M('club', '木棍', 10, 14, 'blunt', 30, 1.15, 1, 'any');
M('hatchet', '手斧', 40, 18, 'cut', 28, 1.1, 1, 'any');
M('shortsword', '短剑', 90, 21, 'cut', 32, 1.2, 1, 'any');
M('arming', '骑士剑', 300, 27, 'cut', 37, 1.1, 1, 'leon');
M('falchion', '弯刃刀', 220, 27, 'cut', 33, 1.1, 1, 'high');
M('sabre', '马刀', 280, 27, 'cut', 38, 1.15, 1, 'khan');
M('scimitar', '弯月刀', 320, 28, 'cut', 37, 1.15, 1, 'sultan');
M('frost_sword', '霜钢剑', 330, 28, 'cut', 36, 1.05, 1, 'frost');
M('war_axe', '战斧', 260, 30, 'cut', 32, 1.0, 1, 'birch');
M('mace', '钉头锤', 250, 25, 'blunt', 31, 1.05, 1, 'any');
M('morningstar', '流星锤', 420, 31, 'blunt', 34, 0.95, 1, 'birch');
M('master_sword', '精钢长剑', 1100, 34, 'cut', 41, 1.12, 1, 'rare');
M('greatsword', '双手大剑', 850, 41, 'cut', 48, 0.85, 2, 'leon');
M('great_axe', '霜狼巨斧', 680, 45, 'cut', 42, 0.8, 2, 'frost');
M('bardiche', '长柄斧', 720, 45, 'cut', 52, 0.78, 2, 'birch');
M('maul', '大锤', 520, 38, 'blunt', 40, 0.75, 2, 'any');
M('pitchfork', '草叉', 20, 18, 'pierce', 56, 0.95, 2, 'any');
M('spear', '长矛', 120, 24, 'pierce', 58, 1.0, 1, 'any');
M('war_spear', '战矛', 280, 28, 'pierce', 62, 1.0, 1, 'any');
M('pike', '长枪', 320, 32, 'pierce', 80, 0.85, 2, 'high');
M('glaive', '关刀', 650, 41, 'cut', 64, 0.85, 2, 'high');
M('lance', '骑枪', 280, 26, 'pierce', 70, 0.9, 1, 'any', { couch: true });
M('heavy_lance', '重骑枪', 640, 33, 'pierce', 76, 0.85, 1, 'leon', { couch: true });
M('wood_sword', '练习木剑', 0, 18, 'blunt', 34, 1.15, 1, 'arena', { noShop: true });
M('wood_staff', '练习长棍', 0, 20, 'blunt', 55, 1.0, 2, 'arena', { noShop: true });

R('stones', '投石', 5, 'throw', 10, 'blunt', 220, 0.8, 12, true, 'any');
R('hunting_bow', '猎弓', 60, 'bow', 16, 'pierce', 420, 1.2, 30, true, 'any');
R('short_bow', '短弓', 140, 'bow', 19, 'pierce', 430, 1.1, 30, true, 'any');
R('nomad_bow', '草原弓', 320, 'bow', 22, 'pierce', 460, 1.0, 30, true, 'khan');
R('long_bow', '长弓', 480, 'bow', 28, 'pierce', 580, 1.4, 30, false, 'birch');
R('khan_bow', '汗弓', 780, 'bow', 30, 'pierce', 540, 1.05, 30, true, 'khan');
R('light_xbow', '轻弩', 180, 'xbow', 30, 'pierce', 480, 2.2, 24, true, 'any');
R('xbow', '战弩', 420, 'xbow', 38, 'pierce', 530, 2.6, 24, true, 'leon');
R('heavy_xbow', '重弩', 820, 'xbow', 52, 'pierce', 580, 3.2, 20, false, 'high');
R('javelin', '标枪', 150, 'throw', 30, 'pierce', 260, 1.0, 6, true, 'any');
R('throw_axe', '飞斧', 220, 'throw', 36, 'cut', 220, 1.0, 5, true, 'frost');
R('arena_bow', '练习弓', 0, 'bow', 12, 'blunt', 380, 1.1, 20, true, 'arena');

S('wood_shield', '木盾', 40, 100, 'any');
S('round_shield', '圆盾', 120, 180, 'frost');
S('leather_shield', '皮盾', 160, 170, 'khan');
S('board_shield', '塔盾', 220, 330, 'high');
S('kite_shield', '鸢盾', 260, 260, 'leon');
S('heater', '骑士盾', 340, 280, 'leon');
S('steel_buckler', '钢圆盾', 520, 360, 'sultan');
S('huscarl_shield', '霜狼重盾', 480, 400, 'frost');
S('arena_shield', '练习盾', 0, 120, 'arena');

A('felt_hat', '毡帽', 'head', 10, 2, 'any');
A('leather_cap', '皮帽', 'head', 40, 8, 'any');
A('fur_helm', '毛皮盔', 'head', 120, 16, 'birch');
A('nasal', '鼻护盔', 'head', 160, 20, 'any');
A('kettle', '宽檐盔', 'head', 240, 24, 'high');
A('steppe_helm', '草原尖盔', 'head', 260, 25, 'khan');
A('spangen', '霜狼角盔', 'head', 300, 26, 'frost');
A('turban_helm', '头巾盔', 'head', 280, 26, 'sultan');
A('bascinet', '尖顶盔', 'head', 520, 34, 'leon');
A('winged', '鹰翼盔', 'head', 820, 40, 'birch');
A('great_helm', '骑士大盔', 'head', 950, 45, 'leon');

A('tunic', '布衣', 'body', 20, 4, 'any');
A('robe', '长袍', 'body', 60, 8, 'sultan');
A('gambeson', '棉甲', 'body', 180, 14, 'any');
A('leather_jerkin', '皮甲', 'body', 300, 18, 'any');
A('lamellar_vest', '札甲背心', 'body', 620, 26, 'khan');
A('mail_shirt', '锁子甲衫', 'body', 950, 30, 'any');
A('hauberk', '链甲', 'body', 1450, 38, 'any');
A('brigandine', '布面甲', 'body', 1650, 40, 'high');
A('scale', '鳞甲', 'body', 1850, 42, 'birch');
A('mamluk', '马穆鲁克甲', 'body', 2300, 46, 'sultan');
A('heavy_lamellar', '重札甲', 'body', 2200, 45, 'khan');
A('coat_plates', '板片甲', 'body', 2900, 50, 'leon');
A('plate', '全身板甲', 'body', 4800, 58, 'rare');

A('wraps', '裹腿', 'legs', 10, 2, 'any');
A('leather_boots', '皮靴', 'legs', 80, 8, 'any');
A('mail_chausses', '锁子护腿', 'legs', 420, 16, 'any');
A('plate_greaves', '板甲护腿', 'legs', 950, 24, 'leon');

H('sumpter', '驮马', 180, 170, 2.0, 90, 6, 10, 0, 'any');
H('steppe_horse', '草原马', 300, 205, 3.2, 100, 8, 10, 1, 'khan');
H('palfrey', '骏马', 450, 222, 2.6, 105, 10, 12, 1, 'any');
H('desert_horse', '沙漠马', 620, 235, 2.9, 110, 12, 14, 2, 'sultan');
H('courser', '快马', 950, 255, 2.8, 100, 10, 12, 2, 'any');
H('hunter', '猎马', 1150, 228, 2.5, 140, 18, 20, 2, 'any');
H('warhorse', '战马', 1900, 212, 2.2, 165, 30, 26, 3, 'any');
H('charger', '重甲战马', 3300, 202, 2.0, 190, 45, 32, 4, 'leon');

D.SLOT_NAMES = { melee: '近战武器', ranged: '远程武器', shield: '盾牌', head: '头盔', body: '身甲', legs: '腿甲', horse: '坐骑' };
D.DT_NAMES = { cut: '砍', pierce: '刺', blunt: '钝' };

D.itemDesc = function (it) {
  if (!it) return '';
  switch (it.slot) {
    case 'melee': return `${it.hands === 2 ? '双手' : '单手'} 伤害${it.dmg}${D.DT_NAMES[it.dt]} 长度${it.reach} 速度${Math.round(it.spd * 100)}${it.couch ? ' 可冲刺' : ''}`;
    case 'ranged': return `${{ bow: '弓', xbow: '弩', throw: '投掷' }[it.kind]} 伤害${it.dmg}${D.DT_NAMES[it.dt]} 射程${it.range} 弹药${it.ammo}${it.mount ? '' : ' 不可马上用'}`;
    case 'shield': return `盾牌 耐久${it.hp}`;
    case 'head': case 'body': case 'legs': return `护甲 ${it.armor}`;
    case 'horse': return `速度${it.spd} 灵活${it.man} 生命${it.hp} 护甲${it.armor} 需骑术${it.ride}`;
  }
  return '';
};

// ---------------- 兵种 ----------------
// kind: inf 步兵, arc 远程, cav 骑兵, ha 骑射
D.TROOPS = {};
function T(id, name, fac, tier, kind, eq, up) {
  D.TROOPS[id] = { id, name, fac, tier, kind, eq, up: up || [] };
}
// 雄狮王国
T('leon_recruit', '雄狮新兵', 'leon', 1, 'inf', { m: 'pitchfork', b: 'tunic', l: 'wraps' }, ['leon_militia']);
T('leon_militia', '雄狮民兵', 'leon', 2, 'inf', { m: 'spear', s: 'wood_shield', h: 'leather_cap', b: 'gambeson', l: 'wraps' }, ['leon_footman', 'leon_xbow']);
T('leon_footman', '雄狮步卒', 'leon', 3, 'inf', { m: 'arming', s: 'kite_shield', h: 'nasal', b: 'leather_jerkin', l: 'leather_boots' }, ['leon_infantry', 'leon_maa']);
T('leon_xbow', '雄狮弩手', 'leon', 3, 'arc', { m: 'shortsword', r: 'light_xbow', h: 'leather_cap', b: 'gambeson', l: 'leather_boots' }, ['leon_sharp']);
T('leon_infantry', '雄狮重步兵', 'leon', 4, 'inf', { m: 'arming', s: 'kite_shield', h: 'bascinet', b: 'mail_shirt', l: 'mail_chausses' }, ['leon_sergeant']);
T('leon_maa', '雄狮扈从骑兵', 'leon', 4, 'cav', { m: 'lance', s: 'heater', h: 'nasal', b: 'mail_shirt', l: 'mail_chausses', horse: 'hunter' }, ['leon_knight']);
T('leon_sharp', '雄狮神弩手', 'leon', 4, 'arc', { m: 'arming', r: 'xbow', h: 'nasal', b: 'leather_jerkin', l: 'leather_boots' }, ['leon_arbalest']);
T('leon_sergeant', '雄狮禁卫军士', 'leon', 5, 'inf', { m: 'greatsword', s: 'heater', h: 'great_helm', b: 'coat_plates', l: 'plate_greaves' });
T('leon_knight', '雄狮骑士', 'leon', 5, 'cav', { m: 'heavy_lance', s: 'heater', h: 'great_helm', b: 'coat_plates', l: 'plate_greaves', horse: 'charger' });
T('leon_arbalest', '雄狮王家弩手', 'leon', 5, 'arc', { m: 'arming', r: 'xbow', s: 'kite_shield', h: 'bascinet', b: 'hauberk', l: 'mail_chausses' });
// 霜狼部族
T('frost_recruit', '霜狼新兵', 'frost', 1, 'inf', { m: 'hatchet', b: 'tunic', l: 'wraps' }, ['frost_warrior']);
T('frost_warrior', '霜狼战士', 'frost', 2, 'inf', { m: 'hatchet', r: 'javelin', s: 'round_shield', h: 'leather_cap', b: 'leather_jerkin', l: 'wraps' }, ['frost_veteran', 'frost_hunter']);
T('frost_veteran', '霜狼老兵', 'frost', 3, 'inf', { m: 'frost_sword', r: 'throw_axe', s: 'round_shield', h: 'nasal', b: 'leather_jerkin', l: 'leather_boots' }, ['frost_raider', 'frost_rider']);
T('frost_hunter', '霜狼猎手', 'frost', 3, 'arc', { m: 'hatchet', r: 'short_bow', h: 'leather_cap', b: 'leather_jerkin', l: 'leather_boots' }, ['frost_archer']);
T('frost_raider', '霜狼掠袭者', 'frost', 4, 'inf', { m: 'great_axe', r: 'throw_axe', s: 'huscarl_shield', h: 'spangen', b: 'mail_shirt', l: 'leather_boots' }, ['frost_huscarl']);
T('frost_rider', '霜狼骑手', 'frost', 4, 'cav', { m: 'war_spear', s: 'round_shield', h: 'spangen', b: 'mail_shirt', l: 'leather_boots', horse: 'palfrey' }, ['frost_jarlguard']);
T('frost_archer', '霜狼弓手', 'frost', 4, 'arc', { m: 'frost_sword', r: 'long_bow', h: 'nasal', b: 'mail_shirt', l: 'leather_boots' }, ['frost_bowmaster']);
T('frost_huscarl', '霜狼亲卫', 'frost', 5, 'inf', { m: 'frost_sword', r: 'throw_axe', s: 'huscarl_shield', h: 'spangen', b: 'hauberk', l: 'mail_chausses' });
T('frost_jarlguard', '雅尔近卫骑兵', 'frost', 5, 'cav', { m: 'great_axe', s: 'huscarl_shield', h: 'spangen', b: 'hauberk', l: 'mail_chausses', horse: 'hunter' });
T('frost_bowmaster', '霜狼弓术大师', 'frost', 5, 'arc', { m: 'great_axe', r: 'long_bow', h: 'spangen', b: 'hauberk', l: 'mail_chausses' });
// 白桦公国
T('birch_recruit', '白桦新兵', 'birch', 1, 'inf', { m: 'club', b: 'tunic', l: 'wraps' }, ['birch_footman']);
T('birch_footman', '白桦步兵', 'birch', 2, 'inf', { m: 'war_axe', s: 'wood_shield', h: 'fur_helm', b: 'gambeson', l: 'wraps' }, ['birch_guard', 'birch_archer']);
T('birch_guard', '白桦卫兵', 'birch', 3, 'inf', { m: 'war_axe', s: 'round_shield', h: 'fur_helm', b: 'leather_jerkin', l: 'leather_boots' }, ['birch_axeman', 'birch_horseman']);
T('birch_archer', '白桦弓手', 'birch', 3, 'arc', { m: 'hatchet', r: 'short_bow', h: 'fur_helm', b: 'gambeson', l: 'leather_boots' }, ['birch_veteran_archer']);
T('birch_axeman', '白桦斧兵', 'birch', 4, 'inf', { m: 'bardiche', h: 'bascinet', b: 'scale', l: 'mail_chausses' }, ['birch_druzhina']);
T('birch_horseman', '白桦骑兵', 'birch', 4, 'cav', { m: 'lance', s: 'round_shield', h: 'nasal', b: 'mail_shirt', l: 'leather_boots', horse: 'hunter' }, ['birch_boyar']);
T('birch_veteran_archer', '白桦老练弓手', 'birch', 4, 'arc', { m: 'war_axe', r: 'long_bow', h: 'nasal', b: 'leather_jerkin', l: 'leather_boots' }, ['birch_sniper']);
T('birch_druzhina', '大公亲兵', 'birch', 5, 'inf', { m: 'morningstar', s: 'round_shield', h: 'winged', b: 'scale', l: 'plate_greaves' });
T('birch_boyar', '白桦波雅尔骑士', 'birch', 5, 'cav', { m: 'heavy_lance', s: 'round_shield', h: 'winged', b: 'scale', l: 'mail_chausses', horse: 'warhorse' });
T('birch_sniper', '白桦神射手', 'birch', 5, 'arc', { m: 'war_axe', r: 'long_bow', h: 'bascinet', b: 'mail_shirt', l: 'mail_chausses' });
// 苍狼汗国
T('khan_recruit', '汗国牧民', 'khan', 1, 'inf', { m: 'knife', r: 'hunting_bow', b: 'tunic', l: 'wraps' }, ['khan_tribesman']);
T('khan_tribesman', '汗国部落骑手', 'khan', 2, 'ha', { m: 'sabre', r: 'short_bow', h: 'felt_hat', b: 'gambeson', l: 'leather_boots', horse: 'steppe_horse' }, ['khan_lancer', 'khan_ha']);
T('khan_lancer', '汗国枪骑兵', 'khan', 3, 'cav', { m: 'lance', s: 'leather_shield', h: 'steppe_helm', b: 'leather_jerkin', l: 'leather_boots', horse: 'steppe_horse' }, ['khan_heavy', 'khan_foot']);
T('khan_ha', '汗国骑射手', 'khan', 3, 'ha', { m: 'sabre', r: 'nomad_bow', h: 'steppe_helm', b: 'leather_jerkin', l: 'leather_boots', horse: 'steppe_horse' }, ['khan_veteran_ha']);
T('khan_heavy', '汗国重骑兵', 'khan', 4, 'cav', { m: 'lance', s: 'leather_shield', h: 'steppe_helm', b: 'lamellar_vest', l: 'mail_chausses', horse: 'hunter' }, ['khan_noyan']);
T('khan_foot', '汗国下马武士', 'khan', 4, 'inf', { m: 'glaive', h: 'steppe_helm', b: 'lamellar_vest', l: 'leather_boots' }, ['khan_keshig']);
T('khan_veteran_ha', '汗国老练骑射手', 'khan', 4, 'ha', { m: 'sabre', r: 'nomad_bow', s: 'leather_shield', h: 'steppe_helm', b: 'lamellar_vest', l: 'leather_boots', horse: 'courser' }, ['khan_khanguard']);
T('khan_noyan', '那颜贵族骑兵', 'khan', 5, 'cav', { m: 'heavy_lance', s: 'leather_shield', h: 'steppe_helm', b: 'heavy_lamellar', l: 'mail_chausses', horse: 'warhorse' });
T('khan_keshig', '怯薛步卫', 'khan', 5, 'inf', { m: 'glaive', r: 'nomad_bow', h: 'steppe_helm', b: 'heavy_lamellar', l: 'mail_chausses' });
T('khan_khanguard', '大汗金帐骑射', 'khan', 5, 'ha', { m: 'sabre', r: 'khan_bow', s: 'leather_shield', h: 'steppe_helm', b: 'heavy_lamellar', l: 'mail_chausses', horse: 'courser' });
// 金沙苏丹国
T('sultan_recruit', '苏丹新兵', 'sultan', 1, 'inf', { m: 'club', b: 'robe', l: 'wraps' }, ['sultan_spearman']);
T('sultan_spearman', '苏丹矛兵', 'sultan', 2, 'inf', { m: 'spear', s: 'leather_shield', h: 'turban_helm', b: 'robe', l: 'wraps' }, ['sultan_warrior', 'sultan_skirm']);
T('sultan_warrior', '苏丹战士', 'sultan', 3, 'inf', { m: 'scimitar', r: 'javelin', s: 'leather_shield', h: 'turban_helm', b: 'leather_jerkin', l: 'leather_boots' }, ['sultan_guard', 'sultan_horseman']);
T('sultan_skirm', '苏丹游击兵', 'sultan', 3, 'arc', { m: 'shortsword', r: 'short_bow', h: 'turban_helm', b: 'robe', l: 'leather_boots' }, ['sultan_archer']);
T('sultan_guard', '苏丹禁卫', 'sultan', 4, 'inf', { m: 'scimitar', r: 'javelin', s: 'steel_buckler', h: 'turban_helm', b: 'mail_shirt', l: 'mail_chausses' }, ['sultan_janissary']);
T('sultan_horseman', '苏丹骑兵', 'sultan', 4, 'cav', { m: 'scimitar', r: 'javelin', s: 'leather_shield', h: 'turban_helm', b: 'mail_shirt', l: 'leather_boots', horse: 'desert_horse' }, ['sultan_mamluk']);
T('sultan_archer', '苏丹弓手', 'sultan', 4, 'arc', { m: 'scimitar', r: 'nomad_bow', h: 'turban_helm', b: 'leather_jerkin', l: 'leather_boots' }, ['sultan_master_archer']);
T('sultan_janissary', '苏丹近卫军', 'sultan', 5, 'inf', { m: 'glaive', s: 'steel_buckler', h: 'turban_helm', b: 'mamluk', l: 'plate_greaves' });
T('sultan_mamluk', '马穆鲁克', 'sultan', 5, 'cav', { m: 'lance', s: 'steel_buckler', h: 'turban_helm', b: 'mamluk', l: 'mail_chausses', horse: 'desert_horse' });
T('sultan_master_archer', '苏丹射手长', 'sultan', 5, 'arc', { m: 'scimitar', r: 'khan_bow', h: 'turban_helm', b: 'mail_shirt', l: 'mail_chausses' });
// 山岭共和国
T('high_recruit', '山岭新兵', 'high', 1, 'inf', { m: 'pitchfork', b: 'tunic', l: 'wraps' }, ['high_militia']);
T('high_militia', '山岭民兵', 'high', 2, 'inf', { m: 'spear', s: 'wood_shield', h: 'leather_cap', b: 'gambeson', l: 'wraps' }, ['high_pikeman', 'high_xbow']);
T('high_pikeman', '山岭长枪兵', 'high', 3, 'inf', { m: 'pike', h: 'kettle', b: 'leather_jerkin', l: 'leather_boots' }, ['high_sergeant', 'high_rider']);
T('high_xbow', '山岭弩手', 'high', 3, 'arc', { m: 'falchion', r: 'light_xbow', s: 'board_shield', h: 'kettle', b: 'gambeson', l: 'leather_boots' }, ['high_heavy_xbow']);
T('high_sergeant', '山岭长枪军士', 'high', 4, 'inf', { m: 'pike', s: 'board_shield', h: 'kettle', b: 'brigandine', l: 'mail_chausses' }, ['high_halberdier']);
T('high_rider', '山岭轻骑兵', 'high', 4, 'cav', { m: 'war_spear', r: 'light_xbow', s: 'wood_shield', h: 'kettle', b: 'leather_jerkin', l: 'leather_boots', horse: 'palfrey' }, ['high_condottiero']);
T('high_heavy_xbow', '山岭重弩手', 'high', 4, 'arc', { m: 'falchion', r: 'heavy_xbow', s: 'board_shield', h: 'kettle', b: 'mail_shirt', l: 'mail_chausses' }, ['high_arbalest']);
T('high_halberdier', '山岭关刀卫士', 'high', 5, 'inf', { m: 'glaive', s: 'board_shield', h: 'kettle', b: 'brigandine', l: 'plate_greaves' });
T('high_condottiero', '佣兵骑士', 'high', 5, 'cav', { m: 'lance', s: 'kite_shield', h: 'bascinet', b: 'brigandine', l: 'mail_chausses', horse: 'warhorse' });
T('high_arbalest', '山岭神弩卫队', 'high', 5, 'arc', { m: 'falchion', r: 'heavy_xbow', s: 'board_shield', h: 'bascinet', b: 'brigandine', l: 'mail_chausses' });
// 匪徒
T('looter', '劫掠者', 'bandit', 1, 'inf', { m: 'club', r: 'stones', b: 'tunic', l: 'wraps' });
T('bandit', '山贼', 'bandit', 2, 'inf', { m: 'hatchet', r: 'hunting_bow', h: 'leather_cap', b: 'leather_jerkin', l: 'wraps' });
T('forest_bandit', '林间盗匪', 'bandit', 2, 'arc', { m: 'hatchet', r: 'short_bow', h: 'felt_hat', b: 'gambeson', l: 'leather_boots' });
T('mountain_bandit', '山地匪帮', 'bandit', 2, 'inf', { m: 'spear', r: 'javelin', s: 'wood_shield', h: 'leather_cap', b: 'gambeson', l: 'wraps' });
T('steppe_raider', '草原马匪', 'bandit', 3, 'ha', { m: 'sabre', r: 'short_bow', h: 'felt_hat', b: 'leather_jerkin', l: 'leather_boots', horse: 'steppe_horse' });
T('sea_raider', '海寇', 'bandit', 3, 'inf', { m: 'war_axe', r: 'throw_axe', s: 'round_shield', h: 'nasal', b: 'mail_shirt', l: 'leather_boots' });
T('desert_raider', '沙漠盗匪', 'bandit', 3, 'cav', { m: 'scimitar', r: 'javelin', s: 'leather_shield', h: 'turban_helm', b: 'robe', l: 'leather_boots', horse: 'desert_horse' });
T('deserter', '逃兵', 'bandit', 3, 'inf', { m: 'arming', r: 'light_xbow', s: 'wood_shield', h: 'nasal', b: 'leather_jerkin', l: 'leather_boots' });
// 中立
T('peasant', '农夫', 'neutral', 1, 'inf', { m: 'pitchfork', b: 'tunic', l: 'wraps' });
T('caravan_guard', '商队护卫', 'neutral', 3, 'cav', { m: 'spear', s: 'wood_shield', h: 'nasal', b: 'leather_jerkin', l: 'leather_boots', horse: 'palfrey' });
T('caravan_master', '商队领队', 'neutral', 4, 'cav', { m: 'arming', s: 'kite_shield', h: 'nasal', b: 'mail_shirt', l: 'leather_boots', horse: 'hunter' });
T('merc_inf', '雇佣步兵', 'neutral', 3, 'inf', { m: 'mace', s: 'kite_shield', h: 'nasal', b: 'mail_shirt', l: 'leather_boots' }, ['merc_veteran']);
T('merc_xbow', '雇佣弩手', 'neutral', 3, 'arc', { m: 'shortsword', r: 'xbow', h: 'kettle', b: 'leather_jerkin', l: 'leather_boots' }, ['merc_marksman']);
T('merc_cav', '雇佣骑兵', 'neutral', 4, 'cav', { m: 'lance', s: 'heater', h: 'bascinet', b: 'mail_shirt', l: 'mail_chausses', horse: 'hunter' });
T('merc_veteran', '雇佣老兵', 'neutral', 4, 'inf', { m: 'maul', h: 'bascinet', b: 'hauberk', l: 'mail_chausses' });
T('merc_marksman', '雇佣神射手', 'neutral', 4, 'arc', { m: 'arming', r: 'heavy_xbow', s: 'board_shield', h: 'kettle', b: 'mail_shirt', l: 'mail_chausses' });

D.KIND_NAMES = { inf: '步兵', arc: '远程', cav: '骑兵', ha: '骑射' };

D.troopStats = function (t) {
  const tier = t.tier;
  return {
    hp: 42 + tier * 9,
    skill: tier, // 1..5，影响命中/格挡/攻速
    level: tier * 6 - 3,
  };
};
D.troopWage = t => [0, 2, 4, 7, 12, 20][t.tier] + (t.eq.horse ? 4 : 0);
D.troopRecruitCost = t => [0, 10, 30, 80, 180, 350][t.tier] + (t.eq.horse ? 60 : 0);
D.troopUpgradeXp = t => [0, 40, 110, 260, 600, 99999][t.tier];
D.troopUpgradeCost = t => [0, 10, 30, 70, 150, 0][t.tier];
D.ransom = t => Math.round(25 * Math.pow(t.tier, 1.6));
D.troopStrength = t => {
  const st = D.troopStats(t);
  let s = st.hp * (1 + t.tier * 0.35);
  if (t.eq.horse) s *= 1.35;
  const arm = ['h', 'b', 'l'].reduce((a, k) => a + (t.eq[k] ? D.ITEMS[t.eq[k]].armor : 0), 0);
  s *= 1 + arm / 80;
  return s / 50;
};

// ---------------- 商品 ----------------
D.GOODS = [
  { id: 'grain', name: '谷物', price: 18, food: true },
  { id: 'bacon', name: '熏肉', price: 45, food: true },
  { id: 'fish', name: '鱼干', price: 32, food: true },
  { id: 'cheese', name: '奶酪', price: 38, food: true },
  { id: 'dates', name: '椰枣', price: 36, food: true },
  { id: 'beer', name: '麦酒', price: 40 },
  { id: 'wine', name: '葡萄酒', price: 120 },
  { id: 'salt', name: '盐', price: 140 },
  { id: 'iron', name: '铁锭', price: 160 },
  { id: 'tools', name: '工具', price: 210 },
  { id: 'furs', name: '毛皮', price: 260 },
  { id: 'linen', name: '亚麻布', price: 130 },
  { id: 'wool', name: '羊毛', price: 110 },
  { id: 'leather', name: '皮革', price: 150 },
  { id: 'pottery', name: '陶器', price: 100 },
  { id: 'spice', name: '香料', price: 420 },
  { id: 'silk', name: '丝绸', price: 520 },
  { id: 'kumis', name: '马奶酒', price: 90 },
];
D.GOOD = {};
D.GOODS.forEach(g => (D.GOOD[g.id] = g));
// 各地区特产（便宜）与紧缺（昂贵）
D.PRODUCE = {
  leon: { cheap: ['grain', 'wine', 'tools', 'linen'], dear: ['furs', 'spice', 'kumis'] },
  frost: { cheap: ['fish', 'furs', 'iron', 'beer'], dear: ['wine', 'spice', 'dates', 'silk'] },
  birch: { cheap: ['furs', 'bacon', 'beer', 'leather'], dear: ['salt', 'silk', 'dates'] },
  khan: { cheap: ['kumis', 'leather', 'wool', 'cheese'], dear: ['iron', 'tools', 'wine', 'grain'] },
  sultan: { cheap: ['spice', 'silk', 'dates', 'salt'], dear: ['furs', 'iron', 'fish', 'beer'] },
  high: { cheap: ['iron', 'cheese', 'pottery', 'wool'], dear: ['silk', 'kumis', 'fish'] },
};
D.SERVINGS_PER_FOOD = 30;

// ---------------- 角色 ----------------
D.ATTRS = [
  { id: 'str', name: '力量', desc: '每点+1生命，提高近战伤害' },
  { id: 'agi', name: '敏捷', desc: '提高攻击与移动速度' },
  { id: 'int', name: '智力', desc: '每点+1技能点' },
  { id: 'cha', name: '魅力', desc: '每点+1队伍上限' },
];
D.SKILLS = [
  { id: 'ironflesh', name: '铁骨', attr: 'str', desc: '每级+4生命' },
  { id: 'powerstrike', name: '强击', attr: 'str', desc: '每级+8%近战伤害' },
  { id: 'powerdraw', name: '强射', attr: 'str', desc: '每级+10%远程伤害' },
  { id: 'shield', name: '盾防', attr: 'agi', desc: '每级盾牌承伤-8%' },
  { id: 'athletics', name: '跑动', attr: 'agi', desc: '每级+4%步行速度' },
  { id: 'riding', name: '骑术', attr: 'agi', desc: '驾驭更好的马，提升马速与转向' },
  { id: 'horsearchery', name: '骑射', attr: 'agi', desc: '减少马上射击的散布' },
  { id: 'looting', name: '掠夺', attr: 'agi', desc: '战后获得更多战利品' },
  { id: 'trainer', name: '教练', attr: 'int', desc: '每日给部队经验' },
  { id: 'tactics', name: '战术', attr: 'int', desc: '自动战斗更有优势' },
  { id: 'pathfinding', name: '寻路', attr: 'int', desc: '每级+3%大地图速度' },
  { id: 'surgery', name: '外科', attr: 'int', desc: '部下阵亡时更可能只是受伤' },
  { id: 'firstaid', name: '急救', attr: 'int', desc: '伤员恢复更快' },
  { id: 'leadership', name: '统御', attr: 'cha', desc: '每级+5队伍上限，提升士气，降低军饷' },
  { id: 'prisoner', name: '俘虏管理', attr: 'cha', desc: '每级+5俘虏上限' },
  { id: 'trade', name: '交易', attr: 'cha', desc: '买卖价格更优惠' },
];

D.BACKGROUNDS = [
  { id: 'noble', name: '没落贵族', desc: '你出身贵族家庭，自小习武骑马。', gold: 900, attrs: { str: 6, agi: 6, int: 5, cha: 8 },
    skills: { riding: 2, leadership: 2, powerstrike: 1, trade: 1 }, renown: 30,
    eq: { melee: 'arming', shield: 'heater', head: 'nasal', body: 'gambeson', legs: 'leather_boots', horse: 'palfrey' } },
  { id: 'merchant', name: '商人之子', desc: '你在商队中长大，精于算计。', gold: 2200, attrs: { str: 5, agi: 5, int: 7, cha: 7 },
    skills: { trade: 3, leadership: 1, pathfinding: 1, riding: 1 }, renown: 5,
    eq: { melee: 'shortsword', ranged: 'light_xbow', head: 'leather_cap', body: 'leather_jerkin', legs: 'leather_boots', horse: 'sumpter' } },
  { id: 'hunter', name: '草原猎手', desc: '你在马背上长大，弓不离手。', gold: 600, attrs: { str: 6, agi: 8, int: 5, cha: 5 },
    skills: { horsearchery: 3, riding: 3, powerdraw: 2, pathfinding: 1 }, renown: 5,
    eq: { melee: 'sabre', ranged: 'nomad_bow', head: 'felt_hat', body: 'leather_jerkin', legs: 'leather_boots', horse: 'steppe_horse' } },
  { id: 'merc', name: '老练佣兵', desc: '你在各国战场上出卖刀剑多年。', gold: 700, attrs: { str: 8, agi: 6, int: 5, cha: 5 },
    skills: { ironflesh: 2, powerstrike: 2, shield: 2, athletics: 1, tactics: 1 }, renown: 15,
    eq: { melee: 'falchion', ranged: 'javelin', shield: 'round_shield', head: 'nasal', body: 'mail_shirt', legs: 'leather_boots' } },
  { id: 'peasant', name: '农家子弟', desc: '你一无所有，只有一腔热血。', gold: 300, attrs: { str: 7, agi: 7, int: 5, cha: 6 },
    skills: { athletics: 2, ironflesh: 2, firstaid: 1, trainer: 1 }, renown: 0,
    eq: { melee: 'pitchfork', ranged: 'hunting_bow', body: 'tunic', legs: 'wraps' } },
];

D.RANKS = [
  { renown: 0, name: '无名之辈' }, { renown: 50, name: '小有名气' }, { renown: 150, name: '声名鹊起' },
  { renown: 300, name: '威名远扬' }, { renown: 600, name: '一代名将' }, { renown: 1000, name: '传奇英雄' },
];
D.rankName = r => { let n = D.RANKS[0].name; for (const k of D.RANKS) if (r >= k.renown) n = k.name; return n; };

// ---------------- 同伴（NPC） ----------------
// 同伴之间不会争吵：没有任何同伴互斥/抱怨的机制
D.HEROES = [
  { id: 'bors', name: '博尔斯', title: '退伍老兵', desc: '雄狮王国的退伍军士，盾墙战术的行家。', level: 6, cost: 400,
    attrs: { str: 13, agi: 8, int: 6, cha: 6 }, skills: { ironflesh: 3, powerstrike: 3, shield: 3, athletics: 2, trainer: 3 },
    eq: { melee: 'arming', shield: 'kite_shield', head: 'nasal', body: 'mail_shirt', legs: 'leather_boots' } },
  { id: 'lina', name: '莉娜', title: '游方医者', desc: '走遍六国的女医师，能把垂死的伤兵救回来。', level: 3, cost: 300,
    attrs: { str: 6, agi: 8, int: 13, cha: 7 }, skills: { surgery: 4, firstaid: 4, athletics: 2 },
    eq: { melee: 'knife', ranged: 'hunting_bow', body: 'robe', legs: 'leather_boots' } },
  { id: 'temur', name: '铁木', title: '草原射手', desc: '汗国流亡的神射手，马背上百步穿杨。', level: 7, cost: 600,
    attrs: { str: 10, agi: 14, int: 6, cha: 5 }, skills: { horsearchery: 4, powerdraw: 3, riding: 4, pathfinding: 2 },
    eq: { melee: 'sabre', ranged: 'nomad_bow', head: 'steppe_helm', body: 'leather_jerkin', legs: 'leather_boots', horse: 'steppe_horse' } },
  { id: 'ragna', name: '拉格娜', title: '持盾少女', desc: '霜狼部族的女战士，挥舞战斧无所畏惧。', level: 5, cost: 450,
    attrs: { str: 12, agi: 10, int: 5, cha: 6 }, skills: { ironflesh: 2, powerstrike: 3, shield: 2, athletics: 3 },
    eq: { melee: 'war_axe', ranged: 'throw_axe', shield: 'round_shield', head: 'spangen', body: 'leather_jerkin', legs: 'leather_boots' } },
  { id: 'marco', name: '马可', title: '落魄商人', desc: '山岭共和国的商人，生意失败后想重振家业。', level: 2, cost: 250,
    attrs: { str: 6, agi: 6, int: 11, cha: 12 }, skills: { trade: 4, leadership: 2, looting: 2 },
    eq: { melee: 'falchion', ranged: 'light_xbow', head: 'kettle', body: 'gambeson', legs: 'leather_boots' } },
  { id: 'yusuf', name: '优素福', title: '沙漠向导', desc: '熟悉每一条商路与小径的向导。', level: 4, cost: 350,
    attrs: { str: 8, agi: 10, int: 11, cha: 6 }, skills: { pathfinding: 4, tactics: 2, riding: 2 },
    eq: { melee: 'scimitar', ranged: 'javelin', shield: 'leather_shield', head: 'turban_helm', body: 'robe', legs: 'leather_boots', horse: 'desert_horse' } },
  { id: 'edmund', name: '埃德蒙', title: '无地骑士', desc: '失去封地的骑士，渴望重新赢得荣誉。', level: 9, cost: 900,
    attrs: { str: 14, agi: 10, int: 6, cha: 9 }, skills: { riding: 4, powerstrike: 4, ironflesh: 3, leadership: 2, shield: 2 },
    eq: { melee: 'lance', shield: 'heater', head: 'bascinet', body: 'hauberk', legs: 'mail_chausses', horse: 'hunter' } },
  { id: 'vasha', name: '瓦莎', title: '林中猎人', desc: '白桦森林里长大的猎人，长弓从不虚发。', level: 5, cost: 400,
    attrs: { str: 11, agi: 12, int: 7, cha: 5 }, skills: { powerdraw: 4, athletics: 3, looting: 2, pathfinding: 2 },
    eq: { melee: 'hatchet', ranged: 'long_bow', head: 'fur_helm', body: 'leather_jerkin', legs: 'leather_boots' } },
  { id: 'orin', name: '奥林', title: '流亡学者', desc: '读过兵书的学者，擅长谋划战局与操练新兵。', level: 3, cost: 300,
    attrs: { str: 6, agi: 7, int: 14, cha: 8 }, skills: { tactics: 4, trainer: 3, surgery: 1, prisoner: 2 },
    eq: { melee: 'shortsword', ranged: 'light_xbow', body: 'gambeson', legs: 'leather_boots' } },
  { id: 'gunnar', name: '贡纳尔', title: '狂战士', desc: '只知道向前冲的巨汉，双手巨斧所向披靡。', level: 8, cost: 700,
    attrs: { str: 16, agi: 9, int: 4, cha: 5 }, skills: { ironflesh: 4, powerstrike: 5, athletics: 3 },
    eq: { melee: 'great_axe', head: 'spangen', body: 'mail_shirt', legs: 'mail_chausses' } },
  { id: 'selin', name: '瑟琳', title: '轻骑斥候', desc: '苏丹国的轻骑兵，来去如风，擅长掠夺。', level: 5, cost: 450,
    attrs: { str: 9, agi: 13, int: 7, cha: 6 }, skills: { riding: 4, looting: 3, horsearchery: 2, pathfinding: 2 },
    eq: { melee: 'scimitar', ranged: 'short_bow', shield: 'leather_shield', head: 'turban_helm', body: 'leather_jerkin', legs: 'leather_boots', horse: 'courser' } },
  { id: 'aldric', name: '奥德里克', title: '雇佣兵队长', desc: '久经沙场的佣兵头子，带兵很有一套。', level: 10, cost: 1000,
    attrs: { str: 13, agi: 10, int: 9, cha: 12 }, skills: { leadership: 4, tactics: 3, trainer: 3, powerstrike: 3, ironflesh: 2 },
    eq: { melee: 'master_sword', shield: 'heater', head: 'bascinet', body: 'brigandine', legs: 'mail_chausses', horse: 'warhorse' } },
];
D.HERO = {};
D.HEROES.forEach(h => (D.HERO[h.id] = h));
D.heroWage = h => 10 + h.level * 3;
