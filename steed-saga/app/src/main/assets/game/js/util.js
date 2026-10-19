'use strict';
// ============ 通用工具 ============
const U = {};

U.mulberry = function (seed) {
  let a = seed >>> 0;
  return function () {
    a = (a + 0x6D2B79F5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
};
U.rand = Math.random;
U.rr = (a, b) => a + Math.random() * (b - a);
U.ri = (a, b) => Math.floor(a + Math.random() * (b - a + 1));
U.pick = arr => arr[Math.floor(Math.random() * arr.length)];
U.chance = p => Math.random() < p;
U.clamp = (v, a, b) => (v < a ? a : v > b ? b : v);
U.lerp = (a, b, t) => a + (b - a) * t;
U.dist = (ax, ay, bx, by) => Math.hypot(ax - bx, ay - by);
U.angDiff = (a, b) => { let d = b - a; while (d > Math.PI) d -= Math.PI * 2; while (d < -Math.PI) d += Math.PI * 2; return d; };
U.rotTo = (a, b, maxStep) => { const d = U.angDiff(a, b); return Math.abs(d) <= maxStep ? b : a + Math.sign(d) * maxStep; };
U.shuffle = arr => { for (let i = arr.length - 1; i > 0; i--) { const j = Math.floor(Math.random() * (i + 1)); [arr[i], arr[j]] = [arr[j], arr[i]]; } return arr; };
U.fmt = n => Math.round(n).toLocaleString('zh-CN');

// 值噪声
U.makeNoise = function (seed) {
  const rnd = U.mulberry(seed);
  const P = new Uint8Array(512);
  const perm = []; for (let i = 0; i < 256; i++) perm.push(i);
  for (let i = 255; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [perm[i], perm[j]] = [perm[j], perm[i]]; }
  for (let i = 0; i < 512; i++) P[i] = perm[i & 255];
  const V = new Float32Array(256); for (let i = 0; i < 256; i++) V[i] = rnd();
  const sm = t => t * t * (3 - 2 * t);
  function n2(x, y) {
    const xi = Math.floor(x), yi = Math.floor(y);
    const xf = x - xi, yf = y - yi;
    const X = xi & 255, Y = yi & 255;
    const a = V[P[P[X] + Y]], b = V[P[P[X + 1] + Y]], c = V[P[P[X] + Y + 1]], d = V[P[P[X + 1] + Y + 1]];
    const u = sm(xf), v = sm(yf);
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v;
  }
  return function fbm(x, y, oct) {
    let s = 0, amp = 0.5, f = 1, tot = 0;
    for (let i = 0; i < (oct || 4); i++) { s += amp * n2(x * f, y * f); tot += amp; amp *= 0.5; f *= 2.03; }
    return s / tot;
  };
};

// 存档：仅保存在应用私有存储（卸载即删除）
U.store = {
  get(key) {
    try { if (window.AndroidStore) return window.AndroidStore.load(key) || null; } catch (e) {}
    try { return localStorage.getItem(key); } catch (e) { return null; }
  },
  set(key, val) {
    try { if (window.AndroidStore) { window.AndroidStore.save(key, val); return true; } } catch (e) {}
    try { localStorage.setItem(key, val); return true; } catch (e) { return false; }
  },
  del(key) {
    try { if (window.AndroidStore) { window.AndroidStore.remove(key); return; } } catch (e) {}
    try { localStorage.removeItem(key); } catch (e) {}
  },
};

U.el = function (tag, cls, html) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (html != null) e.innerHTML = html;
  return e;
};
U.esc = s => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
