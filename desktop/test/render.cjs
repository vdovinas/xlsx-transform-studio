const { chromium } = require('playwright');
const path = require('node:path');
const fs = require('node:fs/promises');
const assert = require('node:assert/strict');
const { startEngine } = require('../engine.cjs');
const { pathToFileURL } = require('node:url');
const root = path.resolve(__dirname, '../..');
let browser;
(async () => {
  browser = await chromium.launch({ channel: 'msedge', headless: true });
  const page = await browser.newPage({ viewport: { width: 1380, height: 900 } });
  const profiles = JSON.parse(await fs.readFile(path.join(root, 'src/main/resources/profiles.json'), 'utf8'));
  const editorProfile = {
    name: 'Проверка редактора', sheetName: 'Результат', headerRow: 1,
    filters: [
      { source: 'Сумма', operator: 'GREATER', value: '0' },
      { source: 'Назначение', operator: 'NOT_EMPTY', value: '' }
    ],
    fields: [
      ...['Дата', 'Сумма', 'Контрагент'].map(name => ({ name, source: name, kind: 'TEXT', steps: [] })),
      { name: 'Категория', source: 'Назначение', kind: 'TEXT', steps: [
        { action: 'CLASSIFY', cases: [
          { value: 'Выручка', conditions: [{ source: 'Назначение', operator: 'CONTAINS', value: 'Оплата' }] }
        ] }
      ] }
    ]
  };
  let source = path.join(root, 'target/electron-fixture.xlsx');
  await page.exposeFunction('studioCall', async (operation, request) => {
    if (operation === 'bootstrap') return { profiles, draft: editorProfile };
    if (operation === 'save-profiles') return;
    if (operation === 'choose-input') return { name: path.basename(source), path: source, format: path.extname(source).slice(1) };
    if (operation === 'inspect') return startEngine(path.join(root, 'tools/engine'), { operation: 'inspect', input: source, ...request }).result;
    if (operation === 'convert') {
      const destination = path.join(root, 'target/ui-result.xlsx');
      return { ...await startEngine(path.join(root, 'tools/engine'), { operation: 'convert', input: source, output: destination, ...request }).result, path: destination };
    }
    throw Error('Unexpected call: ' + operation);
  });
  await page.addInitScript(() => {
    window.studio = Object.fromEntries(['bootstrap','saveProfiles','chooseInput','inspect','convert'].map(name => [name, request => window.studioCall(name.replace(/[A-Z]/g, c => '-' + c.toLowerCase()), request)]));
    window.studio.onProgress = window.studio.onClosing = () => {};
  });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.goto(pathToFileURL(path.join(root, 'desktop/renderer/index.html')).href);
  await page.evaluate(`new Promise((resolve, reject) => {
    const start = Date.now();
    function check() { if (document.querySelector('h1')) resolve(); else if (Date.now()-start>5000) reject(Error('Не загрузился интерфейс')); else setTimeout(check,20); }
    check();
  })`);
  assert.equal(await page.evaluate("getComputedStyle(document.querySelector('.sidebar')).display"), 'none');
  assert.ok(await page.evaluate("!!document.querySelector('#profile-select')"));
  await fs.writeFile(path.join(root, 'target/interface-conversion.png'), (await page.screenshot()));
  await page.evaluate("document.querySelector('[data-action=settings]').click()");
  assert.equal(await page.evaluate('state.settings'), true);
  assert.equal(await page.evaluate("getComputedStyle(document.querySelector('.footer')).display"), 'none');
  for (const tab of ['file', 'fields', 'filters']) {
    const content = await page.evaluate(`state.tab = '${tab}'; render(); document.querySelector('h1').textContent`);
    assert.ok(content);
    await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
    const image = await page.screenshot();
    await fs.writeFile(path.join(root, 'target', `interface-${tab}.png`), image);
  }
  const originalName = await page.evaluate('state.profile.name');
  await page.evaluate("document.querySelector('[data-action=close-settings]').click()");
  assert.equal(await page.evaluate('state.settings'), false);
  assert.equal(await page.evaluate('document.querySelector("#profile-select").selectedOptions[0].textContent'), originalName);
  await page.evaluate("document.querySelector('[data-action=settings]').click()");
  const result = await page.evaluate(`(() => {
    state.tab = 'fields'; state.selected = 3; render();
    const text = document.querySelector('.inspector').textContent;
    const profile = clone(state.profile);
    get('fields.3.steps').push({action:'TRIM',argument:'',replacement:'',conditions:[],matchAll:true,cases:[]});
    render();
    const count = document.querySelectorAll('.step').length;
    state.profile = profile;
    return { text, count,
      conditionSource: document.querySelector('[data-path="fields.3.steps.0.cases.0.conditions.0.source"]').value,
      conditionValue: document.querySelector('[data-path="fields.3.steps.0.cases.0.conditions.0.value"]').value,
      branchValue: document.querySelector('[data-path="fields.3.steps.0.cases.0.value"]').value
    };
  })()`);
  assert.ok(result.text.includes('Классификация'));
  assert.equal(result.count, 2);
  assert.equal(result.conditionSource, 'Назначение');
  assert.equal(result.conditionValue, 'Оплата');
  assert.equal(result.branchValue, 'Выручка');
  await page.evaluate(`state.tab='filters'; render(); document.querySelector('[data-action="add-condition"]').click();`);
  assert.equal(await page.evaluate('state.profile.filters.length'), 3);
  await page.evaluate(`document.querySelector('[data-action="undo"]').click()`);
  assert.equal(await page.evaluate('state.profile.filters.length'), 2);
  await page.evaluate(`state.profile=normalize({name:'Проверка',sheetName:'Результат',headerRow:1,fields:[{name:'Расходы',source:'Сумма',kind:'NUMBER'}]});state.settings=false;state.tab='file';render();document.querySelector('[data-action="choose"]').click();`);
  await page.evaluate(`new Promise((resolve,reject)=>{const start=Date.now();function check(){if(state.input&&!state.busy)resolve();else if(Date.now()-start>15000)reject(Error('Чтение XLSX не завершено'));else setTimeout(check,20);}check();})`);
  assert.equal(await page.evaluate('state.columns.length'), 5);
  await page.evaluate(`document.querySelector('[data-action="export"]').click()`);
  await page.evaluate(`new Promise((resolve,reject)=>{const start=Date.now();function check(){if(!state.busy&&!document.querySelector('#notice').hidden)resolve();else if(Date.now()-start>15000)reject(Error('Экспорт не завершён'));else setTimeout(check,20);}check();})`);
  const message = await page.evaluate('document.querySelector("#notice").textContent');
  assert.ok(message.includes('Сохранено строк: 2'), message);
  assert.ok(message.includes('ошибками: 1'), message);
  source = path.join(root, 'target/interface-input.csv');
  await fs.writeFile(source, '\ufeffНазвание;Сумма\n"Текст; с разделителем";"12,50"\n"Две\nстроки";ошибка\n');
  const selectedProfile = await page.evaluate('state.profile.name');
  await page.evaluate(`state.tab='file';render();document.querySelector('[data-action="choose"]').click();`);
  await page.evaluate(`new Promise((resolve,reject)=>{const start=Date.now();function check(){if(state.input?.format==='csv'&&!state.busy)resolve();else if(Date.now()-start>15000)reject(Error('Чтение CSV не завершено'));else setTimeout(check,20);}check();})`);
  assert.equal(await page.evaluate('state.profile.name'), selectedProfile);
  assert.equal(await page.evaluate('state.columns.length'), 2);
  assert.ok(await page.evaluate(`!!document.querySelector('[data-path="csv.encoding"]')`));
  await page.evaluate(`document.querySelector('[data-path="csv.delimiter"]').value=';';document.querySelector('[data-path="csv.delimiter"]').dispatchEvent(new Event('change',{bubbles:true}));`);
  await page.evaluate(`new Promise((resolve,reject)=>{const start=Date.now();function check(){if(!state.busy)resolve();else if(Date.now()-start>15000)reject(Error('Настройки CSV не применились'));else setTimeout(check,20);}check();})`);
  assert.equal(await page.evaluate('state.profile.csv.delimiter'), ';');
  const csvScreenshot = await page.screenshot();
  await fs.writeFile(path.join(root, 'target/interface-csv.png'), csvScreenshot);
  await page.evaluate(`document.querySelector('[data-action="export"]').click()`);
  await page.evaluate(`new Promise((resolve,reject)=>{const start=Date.now();function check(){if(!state.busy&&!document.querySelector('#notice').hidden)resolve();else if(Date.now()-start>15000)reject(Error('Экспорт CSV не завершён'));else setTimeout(check,20);}check();})`);
  const csvMessage = await page.evaluate('document.querySelector("#notice").textContent');
  assert.ok(csvMessage.includes('Сохранено строк: 2'), csvMessage);
  assert.ok(csvMessage.includes('ошибками: 1'), csvMessage);
  assert.deepEqual(errors, []);
  console.log('Проверены экраны, правила, XLSX и CSV: импорт, ручные настройки, сохранение выбранного профиля и экспорт с ошибками.');
  await browser.close();
})().catch(async error => { console.error(error); if (browser) await browser.close(); process.exitCode = 1; });
