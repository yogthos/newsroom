// The config page's lists. An add button copies its list's <template> in
// under a fresh index, the template's token replaced by it; the server only
// orders a list by its indices, so any unused number will do.
let next = Date.now();

// An element's own children that match, not those of a list or entry
// nested in it.
const children = (el, selector) => [...el.children].filter((c) => c.matches(selector));
const child = (el, selector) => children(el, selector)[0] || null;
const summaryPart = (entry, selector) => {
  const summary = child(entry, 'summary');
  return summary && summary.querySelector(selector);
};

document.addEventListener('click', (e) => {
  const add = e.target.closest('[data-cfg-add]');
  if (add) {
    e.preventDefault();
    const list = add.closest('[data-cfg-list]');
    const tpl = child(list, 'template');
    if (!tpl) return;
    const html = tpl.innerHTML.split(tpl.dataset.cfgToken).join(String(next++));
    const entries = child(list, '.entries');
    entries.insertAdjacentHTML('beforeend', html);
    const added = entries.lastElementChild;
    const first = added && added.querySelector('input:not([type=hidden]), select, textarea');
    if (first) first.focus();
    return;
  }
  const remove = e.target.closest('[data-cfg-remove]');
  if (remove) {
    e.preventDefault();
    remove.closest('[data-cfg-entry]').remove();
  }
});

// A source's test button sends its entry's fields, named as the form names
// them, with the entry's own name, and shows what came back under it.
document.addEventListener('click', async (e) => {
  const button = e.target.closest('[data-cfg-test]');
  if (!button) return;
  e.preventDefault();
  const entry = button.closest('[data-cfg-entry]');
  const out = child(entry, '.test-result');
  const body = new URLSearchParams();
  for (const el of entry.querySelectorAll('[name]')) {
    // a field put away with its type's fieldset is not the source's
    if (el.closest('template') || el.disabled || el.closest('fieldset[disabled]')) continue;
    if (el.type === 'checkbox' && !el.checked) continue;
    body.append(el.getAttribute('name'), el.value);
  }
  body.append('_test', button.dataset.cfgTest);
  button.disabled = true;
  out.innerHTML = '<p class="muted">Reading…</p>';
  try {
    const resp = await fetch('/config/test-source', { method: 'POST', body });
    out.innerHTML = await resp.text();
  } catch (err) {
    out.textContent = 'The test failed: ' + err;
  } finally {
    button.disabled = false;
  }
});

// A new source's type shows that type's fields and puts the rest away:
// a disabled fieldset's fields are neither shown nor sent.
document.addEventListener('change', (e) => {
  const select = e.target.closest('[data-cfg-type-select]');
  if (!select) return;
  const entry = select.closest('[data-cfg-entry]');
  for (const fields of children(entry, 'fieldset[data-cfg-type]')) {
    const picked = fields.dataset.cfgType === select.value;
    fields.disabled = !picked;
    fields.hidden = !picked;
  }
  const type = summaryPart(entry, '.type');
  if (type) type.textContent = select.value;
  const result = child(entry, '.test-result');
  if (result) result.innerHTML = '';
});

// An entry's title is the name typed into it, a source's :name or a
// provider's alias, and what it was called before when that is cleared.
document.addEventListener('input', (e) => {
  const el = e.target;
  const name = el.getAttribute && el.getAttribute('name');
  if (!name || !/\.(name|_alias)$/.test(name)) return;
  const entry = el.closest('[data-cfg-entry]');
  const title = entry && summaryPart(entry, '.title');
  // a query's name is in an entry of its own, which has no title
  if (!title) return;
  title.textContent = el.value.trim() || title.dataset.cfgDefault;
});

// A prompt's reset button puts the default packaged with newsroom in its
// field. Nothing is saved until the form is: Save keeps it, Discard
// changes brings the old prompt back.
document.addEventListener('click', (e) => {
  const button = e.target.closest('[data-cfg-reset]');
  if (!button) return;
  e.preventDefault();
  const name = button.dataset.cfgReset;
  const field = document.getElementById(name);
  const source = [...document.querySelectorAll('[data-cfg-default-for]')]
    .find((el) => el.dataset.cfgDefaultFor === name);
  if (!field || !source) return;
  field.value = source.value;
  field.dispatchEvent(new Event('input', { bubbles: true }));
  field.focus();
  const state = button.parentElement.querySelector('.reset-state');
  if (state) state.textContent = ' Reset to the default. Save to keep it, or discard changes to undo.';
});

// The provider types there are, by name, with what each fills in for an
// entry that leaves it out: {model, 'base-url', 'key-env', ready}.
function providerTypes() {
  const roles = document.querySelector('[data-cfg-provider-types]');
  try {
    return roles ? JSON.parse(roles.dataset.cfgProviderTypes) : {};
  } catch (err) {
    return {};
  }
}

const field = (entry, key) => entry.querySelector(`[name$=".${key}"]`);
const aliasInputs = () => [...document.querySelectorAll('input[name^="providers."][name$="._alias"]')]
  .filter((el) => !el.closest('template'));
const aliasOf = (el) => el.value.trim().replace(/^:/, '');

// The type an entry works like: the one it names, or the built-in its alias
// names.
function entryType(entry, types) {
  const select = field(entry, 'type');
  const picked = select && select.value.trim().replace(/^:/, '');
  if (picked) return picked;
  const alias = field(entry, '_alias');
  const name = alias ? aliasOf(alias) : '';
  return name in types ? name : '';
}

function entryModel(entry, types) {
  const model = field(entry, 'model');
  const typed = model && model.value.trim();
  const type = types[entryType(entry, types)];
  return typed || (type && type.model) || '';
}

// A provider entry's blank fields show what its type fills in, and its
// summary the model it asks for.
function refreshEntry(entry, types) {
  const type = types[entryType(entry, types)] || {};
  for (const el of entry.querySelectorAll('[data-cfg-from-type]')) {
    el.placeholder = type[el.dataset.cfgFromType] || '';
  }
  const summary = summaryPart(entry, '.type');
  if (summary) summary.textContent = entryModel(entry, types);
}

// Each role is a choice of the reader's providers, the aliases the entries
// declare, typed or removed just now included, then the built-ins they
// haven't changed, each with the model it asks for. What a role has picked
// stays picked, a name that's gone among them, so it can be seen and changed.
function refreshRoles() {
  const roles = document.querySelector('[data-cfg-roles]');
  if (!roles) return;
  const types = providerTypes();
  const mine = new Map();
  for (const el of aliasInputs()) {
    const name = aliasOf(el);
    if (name && !mine.has(name)) mine.set(name, entryModel(el.closest('[data-cfg-entry]'), types));
  }
  const label = (name, model) => (model ? `${name} — ${model}` : name);
  const groups = [
    ['Your providers', [...mine].map(([name, model]) => [name, label(name, model)])],
    ['Built-in', Object.entries(types)
      .filter(([name]) => !mine.has(name))
      .map(([name, t]) => [name, label(name, t.model) + (t.ready ? '' : ' (no key)')])],
  ];
  const values = new Set(groups.flatMap(([, choices]) => choices.map(([value]) => value)));
  const option = (value, text, picked) => {
    const o = document.createElement('option');
    o.value = value;
    o.textContent = text;
    o.selected = value === picked;
    return o;
  };
  for (const select of roles.querySelectorAll('select[data-cfg-role]')) {
    const picked = select.value;
    const parts = [option('', '—', picked)];
    for (const [name, choices] of groups) {
      if (!choices.length) continue;
      const group = document.createElement('optgroup');
      group.label = name;
      group.append(...choices.map(([value, text]) => option(value, text, picked)));
      parts.push(group);
    }
    if (picked && !values.has(picked)) parts.push(option(picked, picked, picked));
    select.replaceChildren(...parts);
  }
}

function providerChanged(e) {
  const name = e.target.getAttribute && e.target.getAttribute('name');
  if (!name || !/^providers\.[^.]+\.(_alias|type|model)$/.test(name)) return;
  const entry = e.target.closest('[data-cfg-entry]');
  if (entry) refreshEntry(entry, providerTypes());
  refreshRoles();
}

document.addEventListener('input', providerChanged);
document.addEventListener('change', providerChanged);

document.addEventListener('click', (e) => {
  if (e.target.closest('[data-cfg-remove]')) setTimeout(refreshRoles, 0);
});

// A provider's test button sends its entry's fields with the entry's name,
// and shows what came back under it. A built-in's tries the entry that
// changes it, when there is one, else the built-in as it is.
document.addEventListener('click', async (e) => {
  const button = e.target.closest('[data-cfg-test-provider]');
  if (!button) return;
  e.preventDefault();
  let target = button.dataset.cfgTestProvider;
  let entry = button.closest('[data-cfg-entry]');
  let out;
  if (entry) {
    out = child(entry, '.test-result');
  } else {
    const row = button.closest('tr').nextElementSibling;
    row.hidden = false;
    out = row.querySelector('.test-result');
    const own = aliasInputs().find((el) => aliasOf(el) === target);
    if (own) {
      entry = own.closest('[data-cfg-entry]');
      target = own.getAttribute('name').replace(/\._alias$/, '');
    }
  }
  const body = new URLSearchParams();
  if (entry) {
    for (const el of entry.querySelectorAll('[name]')) body.append(el.getAttribute('name'), el.value);
  }
  body.append('_test', target);
  button.disabled = true;
  out.innerHTML = '<p class="muted">Asking it…</p>';
  try {
    const resp = await fetch('/config/test-provider', { method: 'POST', body });
    out.innerHTML = await resp.text();
  } catch (err) {
    out.textContent = 'The test failed: ' + err;
  } finally {
    button.disabled = false;
  }
});
