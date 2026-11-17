'use strict';

const api = window.studio;
const $ = selector => document.querySelector(selector);
const clone = value => structuredClone(value);
const escape = value => String(value ?? '').replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const operators = { EQUALS: 'Равно', NOT_EQUALS: 'Не равно', CONTAINS: 'Содержит', NOT_CONTAINS: 'Не содержит', STARTS_WITH: 'Начинается с', EMPTY: 'Пусто', NOT_EMPTY: 'Не пусто', GREATER: 'Больше', LESS: 'Меньше', DATE_BEFORE: 'Дата раньше', DATE_AFTER: 'Дата позже' };
const actions = { TRIM: 'Убрать пробелы по краям', LOWER: 'Нижний регистр', UPPER: 'Верхний регистр', REPLACE: 'Заменить текст', EXTRACT: 'Извлечь по шаблону', CONSTANT: 'Задать значение', IF_EMPTY: 'Если пусто, задать значение', IF_EMPTY_SOURCE: 'Если пусто, взять другой столбец', CLASSIFY: 'Классификация по условиям', NUMBER: 'Преобразовать в число', DATE: 'Преобразовать в дату' };
const types = { TEXT: 'Текст', NUMBER: 'Число', DATE: 'Дата' };
const state = { profiles: [], active: 0, profile: null, tab: 'file', settings: false, selected: 0, input: null, sheets: [], columns: [], sheet: 0, busy: false, history: [], csvDetected: null };
let draftTimer;
let noticeTimer;
let saveQueue = Promise.resolve();

function options(values, selected) {
  return Object.entries(values).map(([value, label]) => `<option value="${escape(value)}" ${value === selected ? 'selected' : ''}>${escape(label)}</option>`).join('');
}

function input(label, path, value, extra = '') {
  return `<label>${escape(label)}<input data-path="${path}" value="${escape(value)}" ${extra}></label>`;
}

function select(label, path, value, values) {
  return `<label>${escape(label)}<select data-path="${path}">${options(values, value)}</select></label>`;
}

function source(label, path, value) { return input(label, path, value, 'list="source-columns" placeholder="Название столбца или @A"'); }
function button(label, action, path = '', extra = '') { return `<button type="button" data-action="${action}" data-list="${path}" ${extra}>${label}</button>`; }
function smallActions(path, index) {
  return `<div class="row-actions">${button('↑', 'up', `${path}.${index}`, 'title="Переместить выше" aria-label="Переместить выше"')}${button('↓', 'down', `${path}.${index}`, 'title="Переместить ниже" aria-label="Переместить ниже"')}${button('Удалить', 'remove', `${path}.${index}`, 'class="danger"')}</div>`;
}

function conditions(owner, path, caption = 'Условия') {
  return `<div class="condition-set"><div class="condition-head"><strong>${caption}</strong><select aria-label="Как объединять условия" data-path="${path}.matchAll" data-boolean><option value="true" ${owner.matchAll ? 'selected' : ''}>Все условия</option><option value="false" ${!owner.matchAll ? 'selected' : ''}>Хотя бы одно</option></select></div>
    ${owner.conditions.length ? owner.conditions.map((condition, index) => {
      const key = `${path}.conditions.${index}`;
      return `<div class="condition"><div class="condition-row">${source('Столбец', `${key}.source`, condition.source)}${select('Условие', `${key}.operator`, condition.operator, operators)}${!['EMPTY', 'NOT_EMPTY'].includes(condition.operator) ? input('Значение', `${key}.value`, condition.value) : ''}</div><div class="condition-footer"><label class="check"><input type="checkbox" data-path="${key}.ignoreCase" ${condition.ignoreCase ? 'checked' : ''}>Без учёта регистра</label>${button('Удалить условие', 'remove', key, 'class="text-button danger"')}</div></div>`;
    }).join('') : '<p class="help">Нет условий — выполняется всегда.</p>'}
    ${button('+ Добавить условие', 'add-condition', `${path}.conditions`, 'class="text-button"')}</div>`;
}

function branches(step, path) {
  return `<p class="help">Используется первая совпавшая ветка. Если совпадений нет, значение сохраняется. Последняя ветка без условий задаёт значение по умолчанию.</p>
    ${step.cases.map((branch, index) => `<details class="branch"><summary>Ветка ${index + 1}: ${escape(branch.value || 'Новое значение')}</summary><div class="section-heading"><h3>Настройка ветки</h3>${smallActions(`${path}.cases`, index)}</div>${input('Выдать значение', `${path}.cases.${index}.value`, branch.value)}${conditions(branch, `${path}.cases.${index}`)}</details>`).join('')}
    ${button('+ Добавить ветку', 'add-branch', `${path}.cases`, 'class="text-button"')}`;
}

function transformation(step, index, path) {
  const key = `${path}.${index}`;
  const needsArgument = ['REPLACE', 'EXTRACT', 'CONSTANT', 'IF_EMPTY', 'IF_EMPTY_SOURCE', 'DATE'].includes(step.action);
  const argumentLabel = { REPLACE: 'Найти текст', EXTRACT: 'Шаблон извлечения', CONSTANT: 'Значение', IF_EMPTY: 'Значение при пустой ячейке', IF_EMPTY_SOURCE: 'Другой входной столбец', DATE: 'Входной формат даты (необязательно)' }[step.action];
  return `<details class="step" open><summary>${index + 1}. ${escape(actions[step.action])}</summary><div class="step-content"><div class="step-tools">${smallActions(path, index)}</div>${select('Действие', `${key}.action`, step.action, actions)}
    ${needsArgument ? (step.action === 'IF_EMPTY_SOURCE' ? source(argumentLabel, `${key}.argument`, step.argument) : input(argumentLabel, `${key}.argument`, step.argument)) : ''}
    ${step.action === 'REPLACE' ? input('Заменить на', `${key}.replacement`, step.replacement) : ''}
    ${step.action === 'EXTRACT' ? '<p class="help">Возвращается первая группа в круглых скобках или всё совпадение. Например: комиссия:\\s*([0-9]+(?:[.,][0-9]+)?)</p>' : ''}
    ${step.action === 'DATE' ? '<p class="help">Например dd.MM.yyyy. Без шаблона: дд.ММ.гггг, гггг-ММ-дд или дд/ММ/гггг.</p>' : ''}
    ${step.action === 'CLASSIFY' ? branches(step, key) : ''}
    <details><summary class="help">Выполнять только при условии (${step.conditions.length})</summary>${conditions(step, key)}</details>
  </div></details>`;
}

function renderFile() {
  const profile = state.profile;
  const csv = state.input?.format === 'csv';
  return `<div class="page conversion-page"><div class="page-heading"><h1>Преобразование файла</h1></div>
    <section class="paper file-panel"><div class="file-mark" aria-hidden="true">${csv ? 'CSV' : 'XLSX'}</div><div class="file-info"><h2>${state.input ? escape(state.input.name) : 'Выберите исходный файл'}</h2><p class="subtitle">${state.input ? escape(state.input.path) : ''}</p></div>${button(state.input ? 'Заменить файл' : 'Выбрать XLSX или CSV', 'choose', '', 'class="primary"')}</section>
    <section class="paper conversion-profile"><label>Профиль обработки<select id="profile-select">${state.profiles.map((item, index) => `<option value="${index}" ${index === state.active ? 'selected' : ''}>${escape(index === state.active ? profile.name : item.name)}</option>`).join('')}</select></label><span class="profile-summary">Выходных столбцов: ${profile.fields.length}</span></section>
    <section class="paper reading-panel" ${state.input ? '' : 'hidden'}><h2>Параметры чтения</h2>${csv ? `<div class="form-grid">${select('Кодировка CSV', 'csv.encoding', profile.csv.encoding, {auto:'Определить автоматически','UTF-8':'UTF-8','windows-1251':'Windows-1251'})}${select('Разделитель CSV', 'csv.delimiter', profile.csv.delimiter, {auto:'Определить автоматически',';':'Точка с запятой (;) ', ',':'Запятая (,)','\t':'Табуляция'})}</div><p class="help">${state.csvDetected ? `Определено: ${escape(state.csvDetected.encoding)}, разделитель — ${escape(state.csvDetected.delimiter === '\t' ? 'табуляция' : state.csvDetected.delimiter)}.` : 'Проверяем параметры CSV…'}</p>` : ''}<div class="form-grid ${csv ? 'csv-header' : 'three'}">${csv ? '' : `<label>Лист<select id="sheet" ${state.input ? '' : 'disabled'}>${state.sheets.map((name, index) => `<option value="${index}" ${index === state.sheet ? 'selected' : ''}>${escape(name)}</option>`).join('') || '<option>Сначала выберите файл</option>'}</select></label>`}${input(csv ? 'Запись с заголовками' : 'Строка заголовков', 'headerRow', profile.headerRow, 'type="number" min="1" max="1048576" required')}${button('Обновить столбцы', 'inspect', '', state.input ? '' : 'disabled')}</div>
    ${state.columns.length ? `<details class="source-details"><summary>Исходные столбцы (${state.columns.length})</summary><div class="column-list">${state.columns.map((name, index) => `<span class="column-label"><b>${letter(index)}</b>${escape(name)}</span>`).join('')}</div></details>` : ''}</section>
    ${!profile.fields.length ? '<p class="help">В профиле нет выходных полей. Выберите другой профиль или добавьте поля в настройках ⚙.</p>' : ''}
  </div>`;
}

function renderProfile() {
  const profile = state.profile;
  return `<div class="page"><div class="page-heading"><h1>Настройки профиля</h1></div><section class="paper"><label>Выбрать профиль<select id="profile-select">${state.profiles.map((item, index) => `<option value="${index}" ${index === state.active ? 'selected' : ''}>${escape(index === state.active ? profile.name : item.name)}</option>`).join('')}</select></label><div class="profile-actions">${button('Создать пустой профиль', 'new-profile')}${button('Копировать текущий', 'copy-profile')}${button('Удалить профиль', 'delete-profile', '', 'class="danger"' + (state.profiles.length === 1 ? ' disabled title="Должен остаться хотя бы один профиль"' : ''))}</div><div class="form-grid">${input('Название профиля', 'name', profile.name, 'required')}${input('Название листа в результате', 'sheetName', profile.sheetName, 'required maxlength="31"')}</div></section></div>`;
}

function renderFields() {
  const fields = state.profile.fields;
  state.selected = Math.max(0, Math.min(state.selected, fields.length - 1));
  const field = fields[state.selected];
  const path = `fields.${state.selected}`;
  return `<div class="field-workspace"><div class="page-heading"><div><h1>Выходные поля</h1></div>${button('+ Добавить поле', 'add-field', '', 'class="primary"')}</div><div class="field-split"><div class="field-list" aria-label="Поля результата">${fields.map((item, index) => `<button class="field-item ${index === state.selected ? 'active' : ''}" data-action="select-field" data-index="${index}" aria-pressed="${index === state.selected}"><span class="index">${String(index + 1).padStart(2, '0')}</span><span><strong>${escape(item.name || 'Без названия')}</strong><small>${escape(item.source || 'Значение из правил')} / ${escape(types[item.kind])}</small></span></button>`).join('') || '<div class="empty">Добавьте первое поле результата.</div>'}</div>
    <div class="inspector">${field ? `<div class="section-heading"><h2>Настройки поля</h2>${smallActions('fields', state.selected)}</div><div class="form-grid">${input('Название в результате', `${path}.name`, field.name, 'required')}${select('Тип ячейки', `${path}.kind`, field.kind, types)}</div><div class="section"><h2>Источник значения</h2>${source('Входной столбец', `${path}.source`, field.source)}<p class="help">Оставьте пустым, если значение задаётся правилом. Для выбора по позиции используйте @A, @B и так далее.</p><label class="check"><input type="checkbox" data-path="${path}.required" ${field.required ? 'checked' : ''}>Обязательное значение</label>${input('Формат Excel (необязательно)', `${path}.format`, field.format, `placeholder="${field.kind === 'DATE' ? 'dd.mm.yyyy' : field.kind === 'NUMBER' ? '0.00' : 'По умолчанию'}"`)}</div>
    <div class="section"><div class="section-heading"><h2>Преобразования</h2><span class="help">Сверху вниз</span></div>${field.steps.length ? field.steps.map((step, index) => transformation(step, index, `${path}.steps`)).join('') : '<p class="help">Значение будет скопировано из входного столбца без изменений.</p>'}${button('+ Добавить преобразование', 'add-step', `${path}.steps`)}</div>` : '<div class="empty"><h2>Какие поля нужны в результате?</h2><p>Добавьте столбец и выберите для него источник данных.</p></div>'}</div></div></div>`;
}

function renderFilters() {
  const profile = state.profile;
  const base = { conditions: profile.filters, matchAll: profile.matchAll };
  // The base filter list keeps the original profile schema for compatibility.
  const html = conditions(base, 'baseFilters', 'Оставить строку, если');
  return `<div class="page filters-page"><div class="page-heading"><div><h1>Фильтры строк</h1><p class="subtitle">Укажите, какие строки должны попасть в результат. Условия проверяются по исходным значениям.</p></div></div>
    <section class="paper">${html}</section><section class="paper"><h2>Дополнительные группы</h2><p class="help">Строка должна пройти основной фильтр и каждую дополнительную группу.</p>${profile.filterGroups.map((group, index) => `<div class="branch"><div class="section-heading"><h3>Группа ${index + 1}</h3>${smallActions('filterGroups', index)}</div>${conditions(group, `filterGroups.${index}`)}</div>`).join('')}${button('+ Добавить группу', 'add-group', 'filterGroups')}</section>
    <div class="callout">Пустые фильтры пропускают все строки. Если условие невозможно проверить, строка сохранится, а ошибка будет отмечена в итоговом файле.</div></div>`;
}

function render() {
  if (!state.profile) return;
  document.querySelectorAll('[data-tab]').forEach(button => { button.classList.toggle('active', button.dataset.tab === state.tab); button.setAttribute('aria-current', button.dataset.tab === state.tab ? 'page' : 'false'); });
  $('#field-count').textContent = state.profile.fields.length;
  $('#filter-count').textContent = state.profile.filters.length + state.profile.filterGroups.reduce((count, group) => count + group.conditions.length, 0);
  $('#undo').disabled = !state.history.length;
  document.body.classList.toggle('settings', state.settings);
  $('.settings-toggle').setAttribute('aria-expanded', String(state.settings));
  $('#content').innerHTML = (!state.settings ? renderFile() : state.tab === 'file' ? renderProfile() : state.tab === 'fields' ? renderFields() : renderFilters()) + `<datalist id="source-columns">${state.columns.map(name => `<option value="${escape(name)}"></option>`).join('')}${state.columns.map((_name, index) => `<option value="@${letter(index)}"></option>`).join('')}</datalist>`;
  document.body.classList.toggle('busy', state.busy);
  $('#export').disabled = state.busy || !state.input || !state.profile.fields.length;
  $('#cancel').hidden = !state.busy;
  document.querySelectorAll('main input, main select, main button, .toolbar button, .toolbar select, nav button').forEach(control => { control.disabled ||= state.busy; });
}

function letter(index) { let value = ''; do { value = String.fromCharCode(65 + index % 26) + value; index = Math.floor(index / 26) - 1; } while (index >= 0); return value; }
function normalize(profile) {
  profile = clone(profile);
  profile.name ??= 'Новый профиль'; profile.sheetName ??= 'Результат'; profile.headerRow ??= 1; profile.matchAll ??= true;
  profile.filters ??= []; profile.filterGroups ??= []; profile.fields ??= [];
  profile.csv ??= { encoding: 'auto', delimiter: 'auto' };
  const group = value => { value.matchAll ??= true; value.conditions ??= []; value.conditions.forEach(c => { c.ignoreCase ??= true; c.value ??= ''; }); };
  profile.filterGroups.forEach(group);
  profile.filters.forEach(c => { c.ignoreCase ??= true; c.value ??= ''; });
  for (const field of profile.fields) {
    field.source ??= ''; field.kind ??= 'TEXT'; field.format ??= ''; field.required ??= false; field.steps ??= [];
    for (const step of field.steps) { step.argument ??= ''; step.replacement ??= ''; step.cases ??= []; group(step); step.cases.forEach(group); }
  }
  return profile;
}

function resolve(path) {
  if (path.startsWith('baseFilters.conditions')) path = path.replace('baseFilters.conditions', 'filters');
  if (path === 'baseFilters.matchAll') path = 'matchAll';
  const parts = path.split('.'); const key = parts.pop();
  return { owner: parts.reduce((object, part) => object[part], state.profile), key };
}
function get(path) { const { owner, key } = resolve(path); return owner[key]; }
function remember() { state.history.push(clone(state.profile)); if (state.history.length > 30) state.history.shift(); }
function nameError() {
  const name = state.profile.name.trim().toLowerCase();
  return !name ? 'Введите название профиля' : state.profiles.some((p, i) => i !== state.active && p.name.trim().toLowerCase() === name) ? 'Профиль с таким названием уже существует' : '';
}
function uniqueName(base, copy = false) {
  let name = base, number = 2;
  if (copy) name = base + ' ' + number++;
  while (state.profiles.some(p => p.name.trim().toLowerCase() === name.toLowerCase())) name = base + ' ' + number++;
  return name;
}
async function saveCurrent(explicit = false) {
  clearTimeout(draftTimer);
  const problem = nameError();
  const control = document.querySelector('[data-path="name"]');
  if (control) control.setCustomValidity(problem);
  if (problem) { $('#draft-status').textContent = problem; notify(problem, true); return false; }
  state.profile.name = state.profile.name.trim();
  state.profiles[state.active] = clone(state.profile);
  const selectedOption = document.querySelector('#profile-select')?.options[state.active];
  if (selectedOption) selectedOption.textContent = state.profile.name;
  const snapshot = { profiles: clone(state.profiles), active: state.active };
  $('#draft-status').textContent = 'Сохраняю профиль…';
  const pending = saveQueue.catch(() => {}).then(() => api.saveProfiles(snapshot));
  saveQueue = pending;
  try {
    await pending;
    if (state.active === snapshot.active && JSON.stringify(state.profile) === JSON.stringify(snapshot.profiles[snapshot.active])) $('#draft-status').textContent = 'Профиль сохранён';
    if (explicit) { if (control) control.value = state.profile.name; notify('Профиль сохранён'); }
    return true;
  } catch (error) { $('#draft-status').textContent = 'Не удалось сохранить профиль'; notify(String(error.message || error), true); return false; }
}
function persist() {
  clearTimeout(draftTimer);
  const problem = nameError();
  const control = document.querySelector('[data-path="name"]');
  if (control) control.setCustomValidity(problem);
  $('#draft-status').textContent = problem || 'Есть несохранённые изменения';
  if (!problem) draftTimer = setTimeout(() => saveCurrent(), 350);
}
function notify(message, error = false) {
  clearTimeout(noticeTimer);
  $('#notice').hidden = false; $('#notice').classList.toggle('error', error); $('#notice').textContent = message;
  noticeTimer = setTimeout(() => { $('#notice').hidden = true; }, 10000);
}
function busy(value, text = '') { state.busy = value; $('#status').textContent = text || 'Готово к обработке'; if (!value) document.querySelectorAll('button, input, select').forEach(c => { c.disabled = false; }); render(); }
async function inspect() {
  busy(true, 'Читаю структуру таблицы…');
  try {
    const layout = await api.inspect({ sheet: state.sheet, headerRow: state.profile.headerRow, csv: clone(state.profile.csv) });
    if (layout.cancelled) { notify('Чтение отменено'); return; }
    state.sheets = layout.sheets; state.columns = layout.columns;
    state.csvDetected = layout.encoding ? { encoding: layout.encoding, delimiter: layout.delimiter } : null;
    if (!layout.columns.length) notify('В выбранной строке нет заголовков. Проверьте номер строки.', true);
    else $('#notice').hidden = true;
  } finally { busy(false, `Доступно столбцов: ${state.columns.length}`); }
}

document.addEventListener('focusin', event => {
  const control = event.target;
  if (control.tagName === 'INPUT' && control.dataset.path && control.type !== 'checkbox') control.dataset.editing = 'false';
});
function rememberInput(control) {
  if (control.dataset.editing !== 'true') {
    remember(); control.dataset.editing = 'true'; $('#undo').disabled = false;
  }
}

document.addEventListener('input', event => {
  const control = event.target;
  if (!control.dataset.path || control.type === 'checkbox' || control.tagName !== 'INPUT') return;
  rememberInput(control);
  const { owner, key } = resolve(control.dataset.path);
  owner[key] = control.type === 'number' ? Number(control.value) : control.value;
  persist();
});

document.addEventListener('change', async event => {
  const control = event.target;
  try {
    if (control.id === 'profile-select') { const index = Number(control.value); if (!await saveCurrent()) { control.value = state.active; return; } state.active = index; state.profile = normalize(state.profiles[index]); state.history = []; state.selected = 0; await saveCurrent(); render(); if (state.input) await inspect(); return; }
    if (control.id === 'sheet') { state.sheet = Number(control.value); await inspect(); return; }
    if (!control.dataset.path) return;
    if (control.tagName !== 'INPUT' || control.type === 'checkbox') remember();
    else rememberInput(control);
    const { owner, key } = resolve(control.dataset.path);
    owner[key] = control.type === 'checkbox' ? control.checked : control.dataset.boolean !== undefined ? control.value === 'true' : control.type === 'number' ? Number(control.value) : control.value;
    persist();
    if (control.dataset.path.startsWith('csv.') && state.input) { await inspect(); return; }
    if (['operator', 'action', 'kind'].includes(key)) render();
    else if (key === 'name' && state.tab === 'fields') {
      const selected = document.querySelector('.field-item.active strong'); if (selected) selected.textContent = control.value || 'Без названия';
    }
  } catch (error) { notify(error.message, true); }
});

document.addEventListener('click', async event => {
  const target = event.target.closest('button'); if (!target || target.disabled) return;
  try {
    if (target.dataset.tab) { state.tab = target.dataset.tab; render(); return; }
    const action = target.dataset.action, path = target.dataset.list;
    if (!action) return;
    if (action === 'settings' || action === 'close-settings') { state.settings = action === 'settings'; render(); (state.settings ? $('.settings-back') : $('.settings-toggle')).focus(); return; }
    if (action === 'choose') { const input = await api.chooseInput(); if (input) { state.input = input; state.sheet = 0; state.sheets = []; state.columns = []; state.csvDetected = null; await inspect(); } return; }
    if (action === 'inspect') { await inspect(); return; }
    if (action === 'select-field') { state.selected = Number(target.dataset.index); render(); return; }
    if (action === 'cancel') { await api.cancel(); $('#status').textContent = 'Отменяю обработку…'; return; }
    if (action === 'reveal') { await api.revealOutput(); return; }
    if (action === 'new-profile' || action === 'copy-profile') {
      if (!await saveCurrent()) return;
      const copying = action === 'copy-profile';
      const profile = copying ? clone(state.profile) : normalize({});
      profile.name = uniqueName(copying ? state.profile.name : 'Новый профиль', copying);
      state.active = state.profiles.length; state.profiles.push(profile); state.profile = normalize(profile); state.history = []; state.selected = 0;
      await saveCurrent(true); render(); if (state.input) await inspect(); return;
    }
    if (action === 'delete-profile') {
      if (state.profiles.length === 1) return;
      busy(true, 'Удаление профиля');
      let deleted = false;
      try {
        if (!await api.confirmProfileDeletion(state.profile.name)) return;
        clearTimeout(draftTimer);
        const profiles = state.profiles.filter((_profile, index) => index !== state.active);
        const active = Math.min(state.active, profiles.length - 1);
        const pending = saveQueue.catch(() => {}).then(() => api.saveProfiles({ profiles: clone(profiles), active }));
        saveQueue = pending;
        await pending;
        state.profiles = profiles; state.active = active; state.profile = normalize(profiles[active]); state.history = []; state.selected = 0;
        $('#draft-status').textContent = 'Профиль сохранён'; notify('Профиль удалён'); deleted = true;
      } catch (error) { notify(String(error.message || error), true); }
      finally { busy(false); }
      if (deleted && state.input) await inspect();
      return;
    }
    if (action === 'save-profile') { await saveCurrent(true); return; }
    if (action === 'undo') { if (state.history.length) { state.profile = state.history.pop(); persist(); render(); } return; }
    if (action === 'export') {
      if (!document.querySelector('main input:invalid')) {
        busy(true, 'Обрабатываю таблицу…'); $('#progress').hidden = false; $('#progress').value = 0; $('#reveal').hidden = true; $('#notice').hidden = true;
        try {
          const result = await api.convert({ profile: clone(state.profile), sheet: state.sheet });
          if (result?.cancelled) notify('Обработка отменена. Существующий результат не изменён.');
          else if (result) { notify(`Сохранено строк: ${result.written}. Ячеек с ошибками: ${result.errors}. ${result.errors ? 'Ошибки выделены розовым и снабжены пояснениями. ' : ''}Файл: ${result.path}`); $('#reveal').hidden = false; }
        } finally { busy(false); $('#progress').hidden = true; }
      } else { document.querySelector('main input:invalid').reportValidity(); }
      return;
    }
    remember();
    if (action === 'add-field') { state.profile.fields.push({ name: `Поле ${state.profile.fields.length + 1}`, source: '', kind: 'TEXT', format: '', required: false, steps: [] }); state.selected = state.profile.fields.length - 1; state.tab = 'fields'; }
    if (action === 'add-step') get(path).push({ action: 'TRIM', argument: '', replacement: '', conditions: [], matchAll: true, cases: [] });
    if (action === 'add-condition') get(path).push({ source: '', operator: 'CONTAINS', value: '', ignoreCase: true });
    if (action === 'add-branch') get(path).push({ value: '', matchAll: true, conditions: [] });
    if (action === 'add-group') get(path).push({ value: '', matchAll: true, conditions: [] });
    if (['remove', 'up', 'down'].includes(action)) {
      const { owner, key } = resolve(path); const index = Number(key);
      if (action === 'remove') owner.splice(index, 1);
      else { const destination = index + (action === 'up' ? -1 : 1); if (destination >= 0 && destination < owner.length) { [owner[index], owner[destination]] = [owner[destination], owner[index]]; if (path.startsWith('fields.') && path.split('.').length === 2) state.selected = destination; } }
    }
    persist(); render();
  } catch (error) { notify(error.message, true); if (state.busy) busy(false); }
});

document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && state.settings && !state.busy) { state.settings = false; render(); $('.settings-toggle').focus(); }
  if (event.ctrlKey && event.key.toLowerCase() === 's') { event.preventDefault(); document.activeElement?.blur(); document.querySelector('[data-action="save-profile"]').click(); }
});
api.onProgress(count => { $('#progress').value = count; $('#status').textContent = `Прочитано строк: ${count.toLocaleString('ru-RU')}`; });
api.onClosing(() => notify('Отменяем обработку. После её завершения окно можно закрыть.'));
api.bootstrap().then(({ profiles, draft, active = 0 }) => {
  state.profiles = profiles.map(normalize); state.active = active;
  if (draft) {
    const index = state.profiles.findIndex(p => p.name.trim().toLowerCase() === draft.name.trim().toLowerCase());
    state.active = index < 0 ? state.profiles.length : index;
    state.profiles[state.active] = normalize(draft);
  }
  state.profile = normalize(state.profiles[state.active]);
  render();
}).catch(error => notify(error.message, true));
