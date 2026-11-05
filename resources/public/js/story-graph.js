// Mermaid flowchart -> layered, animated Two.js story graph. Renders only while something moves.
import Two from './two.module.js';

// the page's own tokens, read when the scene is built, so the map follows the
// palette and the reader's light or dark
let INK, BG, ACCENT, ACCENT_INK, MUTED, KIND, FONT;
function readPalette(el) {
  const css = getComputedStyle(el), v = n => css.getPropertyValue(n).trim();
  FONT = v('--sans'); INK = v('--ink'); BG = v('--paper'); ACCENT = v('--accent'); ACCENT_INK = v('--accent-ink'); MUTED = v('--muted');
  KIND = {
    news:    { fill: v('--node-news'), text: INK, weight: 400 },
    trend:   { fill: v('--node-pressure'), stroke: INK, lw: 2, text: INK, weight: 600 },
    implied: { fill: BG, stroke: INK, lw: 1.5, dash: [4,3], text: INK, weight: 600, italic: true },
    force:   { fill: INK, text: BG, weight: 800 }
  };
}
const FS = 13, LH = 16, PAD = 10, NW = 164;
export const KIND_LABEL = { news: 'News', trend: 'Pressure', implied: 'Not yet shown', force: 'Force' };

function wrap(label, maxChars) {
  const lines = []; let cur = '';
  for (const w of label.split(' ')) {
    if ((cur + ' ' + w).trim().length > maxChars && cur) { lines.push(cur); cur = w; } else cur = (cur + ' ' + w).trim();
  }
  if (cur) lines.push(cur);
  return lines;
}

function layout(g, maxCols) {
  const colW = 196, rowH = 140, subOff = 86;
  const N = new Map(g.nodes.map(n => [n.id, n]));
  const mid = n => n.kind === 'trend' || n.kind === 'implied';
  const rank = new Map(), visiting = new Set();
  const r = id => {
    if (rank.has(id)) return rank.get(id);
    if (visiting.has(id)) return 1;
    visiting.add(id); let v = 1;
    for (const e of g.edges) if (e.to === id && N.get(e.from) && mid(N.get(e.from))) v = Math.max(v, r(e.from) + 1);
    visiting.delete(id); rank.set(id, v); return v;
  };
  g.nodes.forEach(n => mid(n) && r(n.id));
  const top = Math.max(1, ...rank.values()) + 1;
  g.nodes.forEach(n => { if (!mid(n)) rank.set(n.id, n.kind === 'force' ? top : 0); });
  g.nodes.forEach(n => { n.lines = wrap(n.label, Math.floor((NW - PAD * 2) / (FS * 0.54))); n.w = NW; n.h = n.lines.length * LH + PAD * 2; });
  const rows = [];
  for (let k = 0; k <= top; k++) { const row = g.nodes.filter(n => rank.get(n.id) === k); if (row.length) rows.push(row); }
  const nb = new Map(g.nodes.map(n => [n.id, []]));
  g.edges.forEach(e => { if (N.has(e.from) && N.has(e.to)) { nb.get(e.from).push(N.get(e.to)); nb.get(e.to).push(N.get(e.from)); } });
  const place = row => {
    const p = Math.ceil(row.length / maxCols), sp = colW / p;
    row.forEach((n, i) => { n.x = (i - (row.length - 1) / 2) * sp; n.sub = i % p; });
  };
  rows.forEach(place);
  for (let it = 0; it < 8; it++) {
    const order = it % 2 ? [...rows].reverse() : rows;
    order.forEach(row => {
      row.forEach(n => { const ns = nb.get(n.id); n.bx = ns.length ? ns.reduce((s, m) => s + m.x, 0) / ns.length : n.x; });
      row.sort((a, b) => a.bx - b.bx); place(row);
    });
  }
  let cursor = 0;
  rows.forEach(row => {
    const p = Math.ceil(row.length / maxCols);
    row.forEach(n => { n.y = -cursor - n.sub * subOff; });
    cursor += rowH + (p - 1) * subOff;
  });
}

export function createStoryGraph(container, graph, opts = {}) {
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  // canvas, not svg: mermaid renders elsewhere on this page and clears
  // stray <svg> nodes out of the document while it works
  const two = new Two({ type: Two.Types.canvas, width: container.clientWidth, height: container.clientHeight, autostart: false }).appendTo(container);
  const dom = two.renderer.domElement;
  Object.assign(dom.style, { display: 'block', position: 'absolute', inset: '0', pointerEvents: 'none' });
  const N = new Map(graph.nodes.map(n => [n.id, n]));
  const edges = graph.edges.filter(e => N.has(e.from) && N.has(e.to));
  const cam = { x: 0, y: 0, s: 0.5, tx: 0, ty: 0, ts: 0.5 };
  let scene, mode, last = 0, raf = 0, current = { nodes: null, focus: [], labels: false, interactive: false }, interactive = false;
  const narrow = () => two.width < 860;
  const safe = () => narrow()
    ? { x: 12, y: 60, w: two.width - 24, h: two.height * 0.5 - 44 }
    : { x: 480, y: 48, w: two.width - 520, h: two.height - 96 };

  function build() {
    if (scene) two.remove(scene);
    readPalette(container);
    mode = narrow() ? 'narrow' : 'wide';
    layout(graph, narrow() ? 3 : 5);
    scene = two.makeGroup();
    const eL = new Two.Group(), nL = new Two.Group(), lL = new Two.Group();
    scene.add(eL); scene.add(nL); scene.add(lL);
    const outs = new Map(), ins = new Map();
    edges.forEach(e => { (outs.get(e.from) || outs.set(e.from, []).get(e.from)).push(e); (ins.get(e.to) || ins.set(e.to, []).get(e.to)).push(e); });
    const slot = (list, e, w, key) => { list.sort((a, b) => N.get(a[key]).x - N.get(b[key]).x); const i = list.indexOf(e), k = list.length; return k === 1 ? 0 : (-0.36 + 0.72 * i / (k - 1)) * w; };

    graph.nodes.forEach(n => {
      const st = KIND[n.kind] || KIND.trend, grp = new Two.Group();
      grp.translation.set(n.x, n.y);
      const rect = new Two.Rectangle(0, 0, n.w, n.h);
      rect.fill = st.fill;
      if (st.stroke) { rect.stroke = st.stroke; rect.linewidth = st.lw; if (st.dash) rect.dashes = st.dash; } else rect.noStroke();
      grp.add(rect);
      const texts = n.lines.map((ln, i) => {
        const t = new Two.Text(ln, -n.w / 2 + PAD, -n.h / 2 + PAD + LH / 2 + i * LH,
          { family: FONT, size: FS, weight: st.weight, alignment: 'left', baseline: 'middle', fill: st.text, style: st.italic ? 'italic' : 'normal' });
        grp.add(t); return t;
      });
      nL.add(grp);
      n.v = { grp, rect, texts, st, op: 0, opT: 1, focus: false };
    });

    edges.forEach(e => {
      const a = N.get(e.from), b = N.get(e.to);
      let P0, P3, c1, c2;
      if (a.y > b.y + 1) {
        P0 = { x: a.x + slot(outs.get(a.id), e, a.w, 'to'), y: a.y - a.h / 2 };
        P3 = { x: b.x + slot(ins.get(b.id), e, b.w, 'from'), y: b.y + b.h / 2 };
        const dy = Math.max(30, (P0.y - P3.y) * 0.5);
        c1 = { x: 0, y: -dy }; c2 = { x: 0, y: dy };
      } else {
        P0 = { x: a.x + a.w / 2, y: a.y }; P3 = { x: b.x + b.w / 2, y: b.y };
        c1 = { x: 90, y: 0 }; c2 = { x: 90, y: 0 };
      }
      const path = new Two.Path([
        new Two.Anchor(P0.x, P0.y, 0, 0, c1.x, c1.y, Two.Commands.move),
        new Two.Anchor(P3.x, P3.y, c2.x, c2.y, 0, 0, Two.Commands.curve)
      ], false, false, true);
      path.noFill(); path.stroke = INK; path.cap = 'butt';
      path.linewidth = e.weight === 'strong' ? 2.6 : 1.4;
      if (e.weight === 'possible') path.dashes = [2,4];
      eL.add(path);
      const ang = Math.atan2(-c2.y, -c2.x);
      let marker, parts;
      if (e.effect === 'weakens') {
        const l1 = new Two.Line(-5, -5, 5, 5), l2 = new Two.Line(-5, 5, 5, -5);
        parts = [l1, l2]; parts.forEach(l => { l.stroke = INK; l.linewidth = 2.2; });
        marker = new Two.Group(); marker.add(l1); marker.add(l2);
        marker.translation.set(P3.x - Math.cos(ang) * 8, P3.y - Math.sin(ang) * 8);
      } else {
        marker = new Two.Path([new Two.Anchor(0, 0), new Two.Anchor(-9, -4.5), new Two.Anchor(-9, 4.5)], true, false);
        marker.noStroke(); marker.fill = INK; parts = [marker];
        marker.translation.set(P3.x, P3.y); marker.rotation = ang;
      }
      eL.add(marker);
      let label = null;
      if (e.label) {
        const C1 = { x: P0.x + c1.x, y: P0.y + c1.y }, C2 = { x: P3.x + c2.x, y: P3.y + c2.y };
        const mx = .125 * P0.x + .375 * C1.x + .375 * C2.x + .125 * P3.x, my = .125 * P0.y + .375 * C1.y + .375 * C2.y + .125 * P3.y;
        label = new Two.Group();
        const bgR = new Two.Rectangle(0, 0, e.label.length * 5.7 + 12, 17); bgR.fill = BG; bgR.noStroke();
        const t = new Two.Text(e.label, 0, 0.5, { family: FONT, size: 11, weight: 400, fill: MUTED, alignment: 'center', baseline: 'middle' });
        label.add(bgR); label.add(t); label.translation.set(mx, my); label.opacity = 0; lL.add(label);
      }
      e.v = { path, marker, parts, label, op: 0, opT: 0, end: 0, on: false, lop: 0, lopT: 0 };
    });
    show(current, true);
  }

  function setFocus(n, f) {
    const v = n.v; if (v.focus === f) return; v.focus = f;
    v.rect.fill = f ? ACCENT : v.st.fill;
    if (v.st.stroke) v.rect.stroke = f ? ACCENT : v.st.stroke;
    v.texts.forEach(t => (t.fill = f ? ACCENT_INK : v.st.text));
  }

  function fit(ids) {
    const ns = ids.map(id => N.get(id)).filter(Boolean);
    if (!ns.length) return;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    ns.forEach(n => { x0 = Math.min(x0, n.x - n.w / 2); x1 = Math.max(x1, n.x + n.w / 2); y0 = Math.min(y0, n.y - n.h / 2); y1 = Math.max(y1, n.y + n.h / 2); });
    const sa = safe(), pad = 28;
    const s = Math.min(sa.w / (x1 - x0 + pad * 2), sa.h / (y1 - y0 + pad * 2), 1.35);
    cam.ts = s; cam.tx = sa.x + sa.w / 2 - (x0 + x1) / 2 * s; cam.ty = sa.y + sa.h / 2 - (y0 + y1) / 2 * s;
    if (reduce || cam.first !== false) { cam.x = cam.tx; cam.y = cam.ty; cam.s = cam.ts; cam.first = false; }
  }

  function show(spec, instantCam) {
    current = spec;
    const ids = spec.nodes || graph.nodes.map(n => n.id);
    const act = new Set(ids), foc = new Set(spec.focus || []);
    graph.nodes.forEach(n => { n.v.opT = act.has(n.id) ? 1 : 0.13; setFocus(n, foc.has(n.id)); });
    edges.forEach(e => {
      const v = e.v, on = act.has(e.from) && act.has(e.to);
      if (on && !v.on && !reduce) v.end = 0;
      v.on = on; v.opT = on ? 1 : 0.07; v.lopT = on && spec.labels ? 1 : 0;
      const c = on && (foc.has(e.from) || foc.has(e.to)) ? ACCENT : INK;
      v.path.stroke = c; v.parts.forEach(p => (e.effect === 'weakens' ? (p.stroke = c) : (p.fill = c)));
    });
    if (instantCam) cam.first = true;
    fit(ids);
    interactive = !!spec.interactive;
    dom.style.pointerEvents = interactive ? 'auto' : 'none';
    kick();
  }

  function kick() { if (!raf) { last = performance.now(); raf = requestAnimationFrame(tick); } }
  function tick(t) {
    const dt = Math.min(64, t - last) / 1000; last = t;
    const k = reduce ? 1 : 1 - Math.exp(-dt * 6);
    let moving = false;
    const ap = (o, key, tk) => { const d = o[tk] - o[key]; if (Math.abs(d) > 0.001) { o[key] += d * k; moving = true; } else o[key] = o[tk]; };
    ap(cam, 'x', 'tx'); ap(cam, 'y', 'ty'); ap(cam, 's', 'ts');
    graph.nodes.forEach(n => { ap(n.v, 'op', 'opT'); n.v.grp.opacity = n.v.op; });
    edges.forEach(e => {
      const v = e.v; ap(v, 'op', 'opT'); ap(v, 'lop', 'lopT');
      if (v.end < 1) { v.end = reduce ? 1 : Math.min(1, v.end + dt * 1.4); moving = true; }
      v.path.opacity = v.op; v.path.ending = v.end;
      v.marker.opacity = v.op * Math.max(0, Math.min(1, (v.end - 0.9) * 10));
      if (v.label) v.label.opacity = v.lop * Math.max(0, Math.min(1, (v.end - 0.6) * 2.5));
    });
    scene.translation.set(cam.x, cam.y); scene.scale = cam.s;
    two.update();
    raf = moving ? requestAnimationFrame(tick) : 0;
  }

  function hit(ev) {
    const b = dom.getBoundingClientRect();
    const wx = (ev.clientX - b.left - cam.x) / cam.s, wy = (ev.clientY - b.top - cam.y) / cam.s;
    return graph.nodes.find(n => Math.abs(wx - n.x) < n.w / 2 && Math.abs(wy - n.y) < n.h / 2);
  }
  function select(id) {
    const n = N.get(id);
    if (!n) { show({ nodes: null, focus: [], labels: false, interactive: true }); opts.onSelect?.(null); return; }
    const inE = edges.filter(e => e.to === id), outE = edges.filter(e => e.from === id);
    const how = e => (e.effect === 'weakens' ? 'Weakens' : 'Strengthens') + (e.weight === 'possible' ? ' · possible' : e.weight === 'strong' ? ' · shown in reports' : '');
    opts.onSelect?.({ id, label: n.label, kind: n.kind, kindLabel: KIND_LABEL[n.kind], step: n.step,
      ins: inE.map(e => ({ label: N.get(e.from).label, how: how(e), why: e.label })),
      outs: outE.map(e => ({ label: N.get(e.to).label, how: how(e), why: e.label })) });
    show({ nodes: [id, ...inE.map(e => e.from), ...outE.map(e => e.to)], focus: [id], labels: true, interactive: true });
  }
  dom.addEventListener('click', ev => { if (interactive) { const n = hit(ev); select(n ? n.id : null); } });
  dom.addEventListener('pointermove', ev => { if (interactive) dom.style.cursor = hit(ev) ? 'pointer' : 'default'; });

  const ro = new ResizeObserver(() => {
    const w = container.clientWidth, h = container.clientHeight;
    if (!w || !h) return;
    two.renderer.setSize(w, h); two.width = w; two.height = h;
    if ((narrow() ? 'narrow' : 'wide') !== mode) build(); else { fit(current.nodes || graph.nodes.map(n => n.id)); kick(); }
  });
  const scheme = matchMedia('(prefers-color-scheme: dark)');
  scheme.addEventListener('change', build);
  build();
  ro.observe(container);
  // the first frames can land before the page's font does
  document.fonts?.ready.then(() => two.update());
  return { show: s => show(s), select, destroy() { ro.disconnect(); scheme.removeEventListener('change', build); cancelAnimationFrame(raf); dom.remove(); } };
}
