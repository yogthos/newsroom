// The reader's topics. A + beside any paragraph of a briefing, a digest or
// a storyline adds it to the pane on the right, and from there the analyst
// writes a report on the topics, or talks them over, its answer streamed
// from /ask. The topics and the conversation are kept in the browser, so
// they follow the reader from one day's page to the next.
const KEY = 'newsroom.topics.v1';
const PICKABLE = '#article .prose p, #article .prose li, #article .standfirst, #article ol.facts li p';

const load = () => {
  try {
    const s = JSON.parse(localStorage.getItem(KEY));
    if (s && Array.isArray(s.topics) && Array.isArray(s.messages)) return s;
  } catch (_) { /* storage blocked or garbled */ }
  return {topics: [], messages: [], open: false, wide: false};
};
const state = load();
// an answer the page was left in the middle of is no answer
state.messages.forEach(m => {
  if (m.role === 'assistant' && !m.html && !m.error) Object.assign(m, {error: true, status: null, content: m.content || 'Interrupted.'});
});
const save = () => {
  try { localStorage.setItem(KEY, JSON.stringify(state)); } catch (_) { /* private mode */ }
};

const pane = document.getElementById('topics');
const toggle = document.querySelector('[data-topics=toggle]');
if (pane && toggle) {
  const list = pane.querySelector('.topics-list');
  const chat = pane.querySelector('.topics-chat');
  const focus = pane.querySelector('[data-topics-focus]');
  const form = pane.querySelector('[data-topics=ask]');
  const question = form.querySelector('textarea');
  const hint = pane.querySelector('.topics-hint');

  const clean = el => el.innerText.replace(/\s+/g, ' ').trim();
  const pageLabel = () => {
    const d = document.querySelector('#article .dateline');
    return d ? d.innerText.trim() : document.title.split(' · ')[0];
  };
  const picked = text => state.topics.some(t => t.page === location.pathname && t.text === text);

  // --- picking --------------------------------------------------------------------
  const add = document.createElement('button');
  add.type = 'button';
  add.className = 'topic-add';
  add.hidden = true;
  document.body.appendChild(add);
  let target = null;
  const place = el => {
    target = el;
    const r = el.getBoundingClientRect();
    const on = picked(clean(el));
    add.textContent = on ? '✓' : '+';
    add.title = on ? 'Remove from your topics' : 'Add to your topics';
    add.classList.toggle('on', on);
    add.style.top = `${r.top + window.scrollY}px`;
    add.style.left = `${Math.max(4, r.left + window.scrollX - 34)}px`;
    add.hidden = false;
  };
  document.addEventListener('mouseover', e => {
    if (e.target === add) return;
    const el = e.target.closest && e.target.closest(PICKABLE);
    if (el && !el.closest('figure') && clean(el).length > 20) place(el);
  });
  document.addEventListener('scroll', () => { if (target && !add.hidden) place(target); }, {passive: true});
  add.addEventListener('click', () => {
    if (!target) return;
    const text = clean(target);
    const i = state.topics.findIndex(t => t.page === location.pathname && t.text === text);
    if (i >= 0) state.topics.splice(i, 1);
    else {
      state.topics.push({id: Date.now() + Math.random(), text, page: location.pathname, label: pageLabel()});
      if (!state.open) state.open = true;
    }
    save();
    render();
    place(target);
  });

  // the passages picked on this page are marked, again after a live re-render
  const mark = () => {
    document.querySelectorAll(PICKABLE).forEach(el => {
      const on = picked(clean(el));
      if (el.classList.contains('picked') !== on) el.classList.toggle('picked', on);
    });
  };
  new MutationObserver(ms => {
    if (ms.some(m => !(m.target.closest && m.target.closest('svg')))) mark();
  }).observe(document.getElementById('article'), {childList: true, subtree: true});

  // --- the pane -------------------------------------------------------------------
  const el = (tag, cls, text) => {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  };
  const download = (name, text) => {
    const a = el('a');
    a.href = URL.createObjectURL(new Blob([text], {type: 'text/markdown'}));
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  };
  let busy = null;

  const message = m => {
    const box = el('div', `topics-message ${m.role}${m.report ? ' report' : ''}${m.error ? ' error' : ''}`);
    if (m.role === 'user') box.appendChild(el('p', null, m.content));
    else if (m.html) {
      const body = el('div', 'prose');
      body.innerHTML = m.html;
      box.appendChild(body);
      const tools = el('div', 'topics-message-tools');
      const dl = el('button', 'quiet', 'Download');
      dl.type = 'button';
      dl.addEventListener('click', () => download(m.report ? 'report.md' : 'answer.md', m.markdown || m.content));
      tools.appendChild(dl);
      box.appendChild(tools);
    } else {
      if (m.status) box.appendChild(el('p', 'muted topics-status', m.status));
      if (m.content) box.appendChild(el('div', 'topics-streaming', m.content));
    }
    return box;
  };

  const render = () => {
    const n = state.topics.length;
    toggle.querySelector('[data-topics-count]').textContent = n;
    toggle.setAttribute('aria-expanded', String(state.open));
    pane.hidden = !state.open;
    document.body.classList.toggle('topics-open', state.open);
    pane.classList.toggle('wide', !!state.wide);
    hint.hidden = n > 0;
    list.replaceChildren(...state.topics.map(t => {
      const li = el('li');
      li.appendChild(el('p', null, t.text));
      const meta = el('p', 'muted');
      const a = el('a', null, t.label || t.page);
      a.href = t.page;
      meta.appendChild(a);
      const x = el('button', 'quiet', 'Remove');
      x.type = 'button';
      x.addEventListener('click', () => {
        state.topics = state.topics.filter(o => o.id !== t.id);
        save();
        render();
      });
      meta.appendChild(x);
      li.appendChild(meta);
      return li;
    }));
    chat.replaceChildren(...state.messages.map(message));
    pane.querySelectorAll('[data-topics=report], .topics-ask button[type=submit]').forEach(b => {
      b.disabled = !!busy || n === 0;
    });
    const stop = pane.querySelector('[data-topics=stop]');
    if (busy && !stop) {
      const b = el('button', 'quiet', 'Stop');
      b.type = 'button';
      b.dataset.topics = 'stop';
      chat.appendChild(b);
    }
    mark();
  };

  // --- asking ---------------------------------------------------------------------
  // the reply's server-sent events, each with JSON data
  const events = async function* (response) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    for (;;) {
      const {done, value} = await reader.read();
      if (done) break;
      buf += decoder.decode(value, {stream: true});
      let at;
      while ((at = buf.search(/\r?\n\r?\n/)) >= 0) {
        const block = buf.slice(0, at);
        buf = buf.slice(at).replace(/^\r?\n\r?\n/, '');
        let event = 'message';
        const data = [];
        for (const line of block.split(/\r?\n/)) {
          if (line.startsWith('event:')) event = line.slice(6).trim();
          else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        }
        yield [event, JSON.parse(data.join('\n'))];
      }
    }
  };

  const ask = async (mode, extra) => {
    if (busy || !state.topics.length) return;
    const pending = {role: 'assistant', content: '', status: 'Gathering the sources', report: mode === 'report'};
    const history = state.messages.filter(m => !m.error).map(m => ({role: m.role, content: m.raw || m.content}));
    state.messages.push({role: 'user', content: mode === 'chat' ? extra.question
      : `Write a report on my ${state.topics.length === 1 ? 'topic' : `${state.topics.length} topics`}`
        + (extra.focus ? `, focusing on this: ${extra.focus}` : '.')});
    state.messages.push(pending);
    busy = new AbortController();
    render();
    chat.lastElementChild && chat.lastElementChild.scrollIntoView({block: 'nearest'});
    try {
      const response = await fetch('/ask', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        signal: busy.signal,
        body: JSON.stringify({
          mode,
          focus: extra.focus || '',
          topics: state.topics.map(({text, page}) => ({text, page})),
          messages: mode === 'chat' ? [...history, {role: 'user', content: extra.question}] : [],
        }),
      });
      if (!response.ok) throw new Error(`the server answered ${response.status}`);
      let shown = 0;
      for await (const [event, data] of events(response)) {
        if (event === 'status') pending.status = data;
        else if (event === 'delta') pending.content += data;
        else if (event === 'done') {
          Object.assign(pending, {raw: pending.content, markdown: data.markdown, html: data.html, status: null});
        } else if (event === 'error') {
          Object.assign(pending, {error: true, status: null, content: data});
        }
        // a streaming answer is redrawn at most every few hundred characters
        if (event !== 'delta' || pending.content.length - shown > 300) {
          shown = pending.content.length;
          render();
        } else {
          const live = chat.querySelector('.topics-message:last-of-type .topics-streaming');
          if (live) live.textContent = pending.content;
          else render();
        }
      }
      if (!pending.html && !pending.error) Object.assign(pending, {error: true, content: 'The answer stopped short.'});
    } catch (e) {
      Object.assign(pending, e.name === 'AbortError'
        ? {error: true, status: null, content: pending.content || 'Stopped.'}
        : {error: true, status: null, content: `Couldn't get an answer: ${e.message}`});
    } finally {
      busy = null;
      save();
      render();
    }
  };

  pane.addEventListener('click', e => {
    const b = e.target.closest('[data-topics]');
    if (!b) return;
    switch (b.dataset.topics) {
      case 'close': state.open = false; save(); render(); break;
      case 'wide': state.wide = !state.wide; save(); render(); break;
      case 'clear': state.topics = []; save(); render(); break;
      case 'forget': if (!busy) { state.messages = []; save(); render(); } break;
      case 'stop': if (busy) busy.abort(); break;
      case 'report': ask('report', {focus: focus.value.trim()}); break;
    }
  });
  form.addEventListener('submit', e => {
    e.preventDefault();
    const q = question.value.trim();
    if (!q) return;
    question.value = '';
    ask('chat', {question: q});
  });
  question.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); form.requestSubmit(); }
  });
  toggle.addEventListener('click', () => { state.open = !state.open; save(); render(); });
  render();
}
