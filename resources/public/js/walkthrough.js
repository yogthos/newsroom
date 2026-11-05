// The day's map, revealed a layer at a time as the step cards pass the
// middle of the screen. The graph itself is story-graph.js; this file only
// decides which pieces a step has brought on by the time it is reached.
import { createStoryGraph } from './story-graph.js';

const root = document.querySelector('.walkthrough');

// The page is live-patched, and the stage is marked data-ignore-morph so a
// patch cannot wipe the drawing out from under it. The price of that is a
// stale map: a new briefing arrives as a fresh [data-map] payload that never
// touches the stage, so the drawing is rebuilt whenever the payload changes.
const mount = text => {
  const canvas = root.querySelector('[data-canvas]');
  const stepsBox = root.querySelector('[data-steps]');
  if (!canvas || !stepsBox) return () => {};

  let graph = null;
  try {
    graph = JSON.parse(text);
  } catch (e) {
    console.error('walkthrough: the day\u2019s map could not be read', e);
  }
  if (!graph || !graph.nodes) return () => {};

  // A step owns the pieces tagged with its own number; everything a
  // reader has passed stays on the stage, so the map accumulates.
  const stepOf = n => Number(n.step) || 1;
  const nodesAt = i => graph.nodes.filter(n => stepOf(n) <= i).map(n => n.id);
  const freshAt = i => graph.nodes.filter(n => stepOf(n) === i).map(n => n.id);

  // The detail opens on the card the piece belongs to, the one whose
  // reports it rests on. Every card carries a slot so that the card keeps
  // its own shape as the reader moves on.
  const showSel = sel => {
    cards.forEach(c => {
      const s = c.querySelector('[data-sel]');
      if (s) { s.hidden = true; s.textContent = ''; }
    });
    if (!sel) return;
    const on = cardAt(stepOf(sel));
    const selBox = on && on.querySelector('[data-sel]');
    if (!selBox) return;
    selBox.hidden = false;
    const head = document.createElement('div');
    head.className = 'sel-head';
    const kick = document.createElement('span');
    kick.className = 'sel-kicker';
    kick.textContent = sel.kindLabel || '';
    const name = document.createElement('span');
    name.className = 'sel-name';
    name.textContent = sel.label || '';
    head.append(kick, name);
    selBox.append(head);
    const list = (title, rows) => {
      if (!rows.length) return;
      const box = document.createElement('div');
      box.className = 'sel-box';
      const h = document.createElement('span');
      h.className = 'sel-title';
      h.textContent = title;
      box.append(h);
      rows.forEach(r => {
        const row = document.createElement('div');
        row.className = 'sel-row';
        const l = document.createElement('span');
        l.className = 'sel-label';
        l.textContent = r.label;
        const how = document.createElement('span');
        how.className = 'sel-how';
        how.textContent = [r.how, r.why].filter(Boolean).join(' ');
        row.append(l, how);
        box.append(row);
      });
      selBox.append(box);
    };
    list('Pushed by', sel.ins || []);
    list('Pushes', sel.outs || []);
    // the reports the piece rests on, offered with the piece; a step can
    // lean on a great many, so show the ones it opens with and point at
    // the rest rather than burying the card
    const src = (graph.sources || {})[String(stepOf(sel))] || [];
    if (src.length) {
      const box = document.createElement('div');
      box.className = 'sel-box sel-srcs';
      const h = document.createElement('span');
      h.className = 'sel-title';
      h.textContent = 'Sources';
      box.append(h);
      src.slice(0, 6).forEach(s => {
        const a = document.createElement('a');
        a.className = 'sel-src';
        a.href = s.url;
        a.target = '_blank';
        a.rel = 'noopener';
        a.textContent = s.title || s.url;
        box.append(a);
      });
      if (src.length > 6) {
        const more = document.createElement('a');
        more.className = 'sel-more';
        more.href = '#source-' + (src[6].n || '');
        more.textContent = 'and ' + (src.length - 6) + ' more reports behind this step';
        box.append(more);
      }
      selBox.append(box);
    }
    // a narrow card scrolls inside itself, so the detail it just filled can
    // be below the card's own clip
    if (on.scrollHeight > on.clientHeight) on.scrollTop = selBox.offsetTop - on.offsetTop;
  };

  // Scrolling to a card passes the others on the way, and moving on closes
  // the detail, so the stage ignores the scroll until it has stopped.
  let holding = false, holdTimer = 0;
  const hold = () => {
    holding = true;
    const done = () => {
      clearTimeout(holdTimer);
      removeEventListener('scrollend', done);
      holding = false;
      again();
    };
    addEventListener('scrollend', done);
    holdTimer = setTimeout(done, 1200);
  };

  // A piece asked about on the map brings up its own card, with its detail.
  const focusPiece = sel => {
    if (!sel) { showSel(null); if (step) apply(step); return; }
    const on = cardAt(stepOf(sel));
    if (!on) return;
    step = stepOf(sel);
    cards.forEach(c => c.classList.toggle('is-on', c === on));
    showSel(sel);
    const box = on.querySelector('[data-sel]').getBoundingClientRect(), top = on.getBoundingClientRect().top;
    if (top >= 0 && box.bottom <= innerHeight) return;
    hold();
    on.closest('.walk-step').scrollIntoView({
      block: 'start',
      behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
    });
  };

  const view = createStoryGraph(canvas, graph, { onSelect: focusPiece });

  // The cover holds an empty stage: the map starts when the first card
  // arrives, so its pieces land one step at a time instead of all at once.
  const cards = [...root.querySelectorAll('[data-step]')];
  const cardAt = i => cards.find(c => Number(c.dataset.step) === i);
  let step = 0;
  const apply = i => {
    showSel(null);
    cards.forEach(c => c.classList.toggle('is-on', Number(c.dataset.step) === i));
    if (!i) return view.show({ nodes: [], focus: [], labels: false, interactive: false });
    view.show({ nodes: nodesAt(i), focus: freshAt(i), labels: true, interactive: true });
  };

  // Whichever card has crossed the middle of the screen owns the stage.
  // Reading the scroll position rather than watching for a card to enter
  // a band keeps the last card reachable: it is short, and sits above the
  // fold long before its middle would ever reach one.
  let waiting = false;
  const pick = () => {
    waiting = false;
    if (holding) return;
    const mid = window.innerHeight * 0.5;
    let next = 0;
    for (const c of cards) {
      const n = Number(c.dataset.step) || 0;
      if (c.getBoundingClientRect().top <= mid) next = Math.max(next, n);
    }
    if (next !== step) { step = next; apply(next); }
  };
  const again = () => { if (!waiting) { waiting = true; requestAnimationFrame(pick); } };

  addEventListener('scroll', again, { passive: true });
  addEventListener('resize', again);
  apply(0);
  pick();

  return () => {
    showSel(null);
    cards.forEach(c => c.classList.remove('is-on'));
    removeEventListener('scroll', again);
    removeEventListener('resize', again);
    clearTimeout(holdTimer);
    view.destroy();
  };
};

if (root) {
  const payload = () => {
    const raw = root.querySelector('[data-map]');
    return raw ? raw.textContent : '';
  };

  let mounted = null; // { text, undo }
  // A narrow page has no stage, its cards being headed by their own pieces
  // instead, so there is nothing to draw until it is wide enough to show one.
  const showing = () => {
    const canvas = root.querySelector('[data-canvas]');
    return !!canvas && canvas.clientWidth > 0;
  };
  const sync = () => {
    const text = showing() ? payload() : null;
    if (mounted && mounted.text === text) return;
    if (mounted) mounted.undo();
    mounted = { text, undo: text === null ? () => {} : mount(text) };
  };
  sync();
  new ResizeObserver(sync).observe(root);
  new MutationObserver(sync).observe(root, { childList: true, subtree: true, characterData: true });
}
