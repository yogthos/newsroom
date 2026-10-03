// The config page's lists. An add button copies its list's <template> in
// under a fresh index, the template's token replaced by it; the server only
// orders a list by its indices, so any unused number will do. A list of
// sources has a template per type, picked by the list's select.
let next = Date.now();

document.addEventListener('click', (e) => {
  const add = e.target.closest('[data-cfg-add]');
  if (add) {
    e.preventDefault();
    const list = add.closest('[data-cfg-list]');
    const select = list.querySelector(':scope > .add-source > [data-cfg-type-select]');
    const tpl = select
      ? list.querySelector(`:scope > template[data-cfg-type="${CSS.escape(select.value)}"]`)
      : list.querySelector(':scope > template');
    if (!tpl) return;
    const html = tpl.innerHTML.replaceAll(tpl.dataset.cfgToken, String(next++));
    const entries = list.querySelector(':scope > .entries');
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
