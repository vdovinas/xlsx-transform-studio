// Exercises the real Tauri bridge in WebView2 without opening native file dialogs.
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const fs = require('node:fs/promises');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../..');

(async () => {
  const folder = await fs.mkdtemp(path.join(root, 'target/webview-smoke-'));
  const child = spawn(path.join(root, 'release/portable/xlsx-studio.exe'), [], {
    windowsHide: true,
    env: { ...process.env, WEBVIEW2_USER_DATA_FOLDER: folder, WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS: '--remote-debugging-port=9229' },
  });
  child.on('error', error => { console.error(error); process.exitCode = 1; });
  let browser;
  try {
    const deadline = Date.now() + 30000;
    while (!browser && Date.now() < deadline) {
      try { browser = await chromium.connectOverCDP('http://127.0.0.1:9229'); }
      catch { await new Promise(resolve => setTimeout(resolve, 200)); }
    }
    assert.ok(browser, 'WebView2 debugging endpoint did not start');
    const page = browser.contexts()[0].pages()[0];
    await page.waitForSelector('#profile-select');
    const result = await page.evaluate(async () => {
      const boot = await window.studio.bootstrap();
      let invalidProfile;
      try { await window.studio.saveProfiles({ profiles: [{ fields: null }], active: 0 }); } catch (error) { invalidProfile = String(error); }
      const unlisten = await window.__TAURI__.event.listen('progress', () => {});
      unlisten();
      return { count: boot.profiles.length, invalidProfile, title: document.title };
    });
    assert.ok(result.count >= 4);
    assert.match(result.invalidProfile, /Некорректный профиль/);
    assert.equal(result.title, 'XLSX Studio');
    await page.screenshot({ path: path.join(root, 'target/interface-tauri.png') });
    console.log('Tauri/WebView2: интерфейс, встроенные профили, валидация и подписка на события проверены.');
  } finally {
    if (browser) await browser.close();
    child.kill();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
