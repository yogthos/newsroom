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
