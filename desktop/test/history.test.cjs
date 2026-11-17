const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function renderer(timers = { clearTimeout() {}, setTimeout() {} }) {
  const listeners = {};
  const elements = new Map();
  const element = selector => {
    if (!elements.has(selector)) elements.set(selector, { disabled: true, dataset: {}, classList: { toggle() {} }, setAttribute() {}, setCustomValidity() {}, focus() {} });
    return elements.get(selector);
  };
  const context = vm.createContext({ structuredClone, ...timers, window: { studio: {
    onProgress() {}, onClosing() {}, bootstrap: () => new Promise(() => {})
  } }, document: { addEventListener: (type, callback) => { listeners[type] = callback; },
    querySelector: element, querySelectorAll: () => [], body: { classList: { toggle() {} } }
  } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../renderer/app.js'), 'utf8'), context);
  const run = code => vm.runInContext(code, context);
  run("state.profile = normalize({name:'Original',sheetName:'Result',headerRow:1,fields:[{name:'Amount',source:'Sum',steps:[]}],filters:[{source:'Sum',operator:'GREATER',value:'0'}]}); state.history=[];");
  return { run, element, listeners };
}

test('Сообщение скрывается через 10 секунд; новое сообщение перезапускает отсчёт', () => {
  const pending = new Map();
  let id = 0;
  const { run, element } = renderer({
    setTimeout(callback, delay) { pending.set(++id, { callback, delay }); return id; },
    clearTimeout(timer) { pending.delete(timer); }
  });
  run("notify('Первое сообщение')");
  const first = id;
  assert.equal(element('#notice').hidden, false);
  assert.equal(pending.get(first).delay, 10000);
  run("notify('Ошибка', true)");
  assert.equal(pending.has(first), false);
  assert.equal(pending.size, 1);
  assert.equal(element('#notice').textContent, 'Ошибка');
  assert.equal(element('#notice').hidden, false);
  assert.equal(pending.get(id).delay, 10000);
  pending.get(id).callback();
  assert.equal(element('#notice').hidden, true);
});

for (const [property, original, value, type] of [
  ['name', 'Original', 'Changed', 'text'],
  ['fields.0.source', 'Sum', 'Other', 'text'],
  ['filters.0.value', '0', '10', 'text'],
  ['headerRow', 1, '3', 'number']
]) test('Отмена восстанавливает ввод: ' + property, async () => {
  const { run, element, listeners } = renderer();
  const control = { tagName: 'INPUT', type, dataset: { path: property }, value };
  listeners.focusin({ target: control });
  listeners.input({ target: control });
  listeners.input({ target: control });
  await listeners.change({ target: control });
  assert.equal(run('state.history.length'), 1, 'один снимок на редактирование');
  assert.equal(element('#undo').disabled, false);
  assert.equal(run('get(' + JSON.stringify(property) + ')'), type === 'number' ? Number(value) : value);
  const undo = { disabled: false, dataset: { action: 'undo' } };
  await listeners.click({ target: { closest: () => undo } });
  assert.equal(run('get(' + JSON.stringify(property) + ')'), original);
  assert.equal(run('state.history.length'), 0);
});

test('Повторный фокус создаёт новый шаг отмены, change без input тоже сохраняется', async () => {
  const { run, listeners } = renderer();
  const control = { tagName: 'INPUT', type: 'text', dataset: { path: 'name' }, value: 'First' };
  listeners.focusin({ target: control }); listeners.input({ target: control });
  listeners.focusin({ target: control }); control.value = 'Second';
  await listeners.change({ target: control });
  assert.equal(run('state.history.length'), 2);
  const undo = { disabled: false, dataset: { action: 'undo' } };
  await listeners.click({ target: { closest: () => undo } });
  assert.equal(run('state.profile.name'), 'First');
  await listeners.click({ target: { closest: () => undo } });
  assert.equal(run('state.profile.name'), 'Original');
});
