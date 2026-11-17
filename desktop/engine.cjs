const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const path = require('node:path');

function startEngine(root, request, onProgress = () => {}) {
  const child = spawn(path.join(root, 'runtime/bin/java.exe'), [
    '-Xmx1536m', '-Dfile.encoding=UTF-8', '-cp', path.join(root, 'xlsx-desktop.jar'),
    'local.xlsx.cli.EngineCommand',
  ], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
  let received;
  let stderr = '';
  const result = new Promise((resolve, reject) => {
    child.on('error', reject);
    child.stdin.on('error', () => {});
    child.stderr.on('data', data => { stderr = (stderr + data).slice(-4000); });
    createInterface({ input: child.stdout }).on('line', line => {
      try {
        const message = JSON.parse(line);
        if (message.type === 'progress') onProgress(message.count);
        else received = message;
      } catch { /* Only the engine's JSON messages are part of the protocol. */ }
    });
    child.on('close', code => {
      if (received?.type === 'result' && code === 0) resolve(received.value);
      else if (received?.type === 'cancelled') resolve({ cancelled: true });
      else reject(new Error(received?.message || stderr || `Обработчик завершился с кодом ${code}`));
    });
    child.stdin.write(`${JSON.stringify(request)}\n`);
  });
  return { result, cancel: () => { if (!child.killed) child.stdin.write('cancel\n'); } };
}

module.exports = { startEngine };
