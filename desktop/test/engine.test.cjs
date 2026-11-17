const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { startEngine } = require('../engine.cjs');
const { validateProfile } = require('../profile.cjs');
const root = path.resolve(__dirname, '../..');
const fixture = path.join(root, 'target/electron-fixture.xlsx');
const engine = path.join(root, 'tools/engine');

test('Проверяет готовые профили и отклоняет некорректные правила', async () => {
  const profiles = JSON.parse(await fs.readFile(path.join(root, 'src/main/resources/profiles.json'), 'utf8'));
  profiles.forEach(validateProfile);
  assert.throws(() => validateProfile({ fields: null }));
  const profile = structuredClone(profiles[0]);
  profile.fields[0].steps = [{ action: 'EXECUTE', conditions: [], cases: [] }];
  assert.throws(() => validateProfile(profile));
});

test('Встроенный обработчик читает XLSX и выгружает строки с отметками ошибок', async () => {
  const layout = await startEngine(engine, { operation: 'inspect', input: fixture, sheet: 0, headerRow: 1 }).result;
  assert.deepEqual(layout.columns, ['Дата', 'Номер', 'Контрагент', 'Назначение', 'Сумма']);
  const profile = { name: 'Тест', sheetName: 'Результат', headerRow: 1, matchAll: true, filters: [], filterGroups: [], fields: [{ name: 'Расходы', source: 'Сумма', kind: 'NUMBER', format: '0.00', required: true, steps: [] }] };
  const output = path.join(root, 'target/electron-result.xlsx');
  const result = await startEngine(engine, { operation: 'convert', input: fixture, output, sheet: 0, profile }).result;
  assert.equal(result.written, 2);
  assert.equal(result.errors, 1);
  assert.ok((await fs.stat(output)).size > 1000);
});

test('Отмена обработки сохраняет существующий файл результата', async () => {
  const profile = { name: 'Тест', sheetName: 'Результат', headerRow: 1, fields: [{ name: 'Сумма', source: 'Сумма', kind: 'NUMBER', steps: [] }] };
  const output = path.join(root, 'target/cancelled-result.xlsx');
  await fs.writeFile(output, 'existing data');
  const job = startEngine(engine, { operation: 'convert', input: fixture, output, sheet: 0, profile });
  job.cancel();
  assert.deepEqual(await job.result, { cancelled: true });
  assert.equal(await fs.readFile(output, 'utf8'), 'existing data');
});

test('CSV передаёт ручные параметры импорта и сохраняет ошибки в XLSX', async () => {
  const source = path.join(root, 'target/electron-input.csv');
  await fs.writeFile(source, '\ufeffНазвание;Сумма\n"Текст; с разделителем";"12,50"\n"Две\nстроки";ошибка\n');
  const layout = await startEngine(engine, { operation: 'inspect', input: source, headerRow: 1, csv: { encoding: 'auto', delimiter: 'auto' } }).result;
  assert.deepEqual(layout.columns, ['Название', 'Сумма']);
  assert.equal(layout.encoding, 'UTF-8');
  assert.equal(layout.delimiter, ';');
  const wrong = await startEngine(engine, { operation: 'inspect', input: source, headerRow: 1, csv: { encoding: 'UTF-8', delimiter: '\t' } }).result;
  assert.equal(wrong.columns.length, 1);
  const profile = { name: 'Выбранный профиль', sheetName: 'Результат', headerRow: 1, csv: { encoding: 'UTF-8', delimiter: ';' }, fields: [{ name: 'Сумма', source: 'Сумма', kind: 'NUMBER', steps: [] }] };
  validateProfile(profile);
  const result = await startEngine(engine, { operation: 'convert', input: source, output: path.join(root, 'target/csv-result.xlsx'), profile }).result;
  assert.equal(result.written, 2);
  assert.equal(result.errors, 1);
});
