// WebDriver (plain HTTP, no deps) against tauri-driver: start the app, simulate a file drop on the
// read page through Tauri's own drag-drop event, wait for the result, report errors + screenshot.
import { writeFileSync } from 'node:fs';
const WD = 'http://127.0.0.1:4444';
const wd = async (method, path, body) => {
  const r = await fetch(WD + path, { method, headers: { 'content-type': 'application/json' }, body: body && JSON.stringify(body) });
  const j = await r.json();
  if (j.value && j.value.error) throw new Error(`${path}: ${j.value.error}: ${j.value.message}`);
  return j.value;
};
const s = await wd('POST', '/session', { capabilities: { alwaysMatch: { 'tauri:options': { application: '/app/earmark-desktop' } } } });
const sid = s.sessionId;
const js = (script, args = []) => wd('POST', `/session/${sid}/execute/sync`, { script, args });
const shot = async name => writeFileSync(`/out/${name}.png`, Buffer.from(await wd('GET', `/session/${sid}/screenshot`), 'base64'));
const sleep = ms => new Promise(r => setTimeout(r, ms));
// Under Xvfb, WebDriver keystrokes sent right after a render can get lost: type, read back, retry.
const typeInto = async (elementId, text) => {
  for (let i = 0; i < 5; i++) {
    await wd('POST', `/session/${sid}/element/${elementId}/clear`, {});
    await wd('POST', `/session/${sid}/element/${elementId}/value`, { text });
    await sleep(100);
    const got = await wd('GET', `/session/${sid}/element/${elementId}/property/value`);
    // Lengths only: the typed text may be an email address.
    console.log(`typing attempt ${i + 1}: ${got === text ? 'ok' : `mismatch (got ${got.length} chars, want ${text.length})`}`);
    if (got === text) return;
  }
  throw new Error('could not type into field');
};
try {
  for (let i = 0; i < 50 && !(await js('return !!document.querySelector(".drop")')); i++) await sleep(200);
  // Collect page errors and failed fetches from here on.
  await js(`window.__errs = [];
    addEventListener('error', e => __errs.push('error: ' + e.message));
    addEventListener('unhandledrejection', e => __errs.push('rejection: ' + e.reason));
    const f = window.fetch; window.fetch = (u, o) => f(u, o).then(r => {
        if (r.status >= 400) __errs.push(r.status + ' ' + u + (String(u).includes('/auth/request') ? ' body ' + String(o && o.body).replace(/[^"@:{},]+@/, '…@') : ''));
        return r; }, e => { __errs.push('fetch ' + u + ': ' + e); throw e; });`);
  console.log('api base:', await js('return document.baseURI'));
  // Same event Tauri emits for an OS drop on this webview.
  await js(`return window.__TAURI__.event.emitTo({kind: 'Webview', label: 'main'}, 'tauri://drag-drop',
            {paths: [arguments[0]], position: {x: 100, y: 300}})`, [process.env.AUDIO]);
  let text = '';
  for (let i = 0; i < 150; i++) {
    text = await js('const b = document.querySelector(".banner"); return b ? b.textContent : ""');
    if (text && !text.startsWith('Listening')) break;
    await sleep(200);
  }
  console.log('banner:', text || '(none)');
  console.log('errors:', JSON.stringify(await js('return window.__errs')));
  await shot('read');

  if (process.env.MARK_AUDIO) {
    // Sign in like an artist: email in the app, which then waits; the "browser" side (confirming
    // the mailed link) goes through the API. The token is read from the dev server log, never printed.
    const api = process.env.API || 'http://localhost:8090';
    const post = async (path, body) => { const r = await fetch(api + path, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) }); return { status: r.status, body: await r.json().catch(() => null) }; };
    const email = 'e2e-desktop@example.invalid';
    const id = el => Object.values(el)[0];
    await js(`history.pushState(null, '', '/login'); dispatchEvent(new PopStateEvent('popstate'));`);
    for (let i = 0; i < 50 && !(await js('return !!document.querySelector("input[type=email]")')); i++) await sleep(200);
    const emailEl = await wd('POST', `/session/${sid}/element`, { using: 'css selector', value: 'input[type=email]' });
    await typeInto(id(emailEl), email);
    const send = await wd('POST', `/session/${sid}/element`, { using: 'css selector', value: 'form button' });
    await wd('POST', `/session/${sid}/element/${id(send)}/click`, {});
    let appCode = null;
    for (let i = 0; i < 50 && !appCode; i++) { appCode = await js('const c = document.querySelector(".code-big"); return c && c.textContent'); await sleep(200); }
    await shot('login-waiting');
    if (!appCode) {
      // Diagnose: the same request from inside the app.
      const r = await wd('POST', `/session/${sid}/execute/async`, { script: `const done = arguments[arguments.length - 1];
        fetch('${api}/api/auth/request', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({email: '${email}', device: true})})
          .then(r => r.text().then(t => done(r.status + ' ' + t.replace(/"poll":"[^"]*"/, '"poll":"<redacted>"'))), e => done('fetch failed: ' + e));`, args: [] });
      console.log('login request from the app:', r, '| errors:', JSON.stringify(await js('return window.__errs')));
      throw new Error('app did not show a code');
    }
    const { readFileSync } = await import('node:fs');
    const line = readFileSync('/logs/server.log', 'utf8').split('\n').filter(l => l.startsWith('LOGIN LINK for ' + email)).pop();
    const token = line.match(/#(\S+)$/)[1];
    const peek = await post('/api/auth/peek', { token });
    console.log('login: app shows code', appCode, '| confirm page would show', peek.body.code, '| match:', appCode === peek.body.code);
    await post('/api/auth/verify', { token });
    let path = '';
    for (let i = 0; i < 50 && path !== '/works'; i++) { path = await js('return location.pathname'); await sleep(200); }
    console.log('login: app signed in by itself:', path === '/works', '| session stored:', !!(await js('return localStorage.getItem("wm-session")')));
    await js(`history.pushState(null, '', '/mark'); dispatchEvent(new PopStateEvent('popstate'));`);
    for (let i = 0; i < 50 && !(await js('return !!document.querySelector("form input")')); i++) await sleep(200);
    const inputs = await wd('POST', `/session/${sid}/elements`, { using: 'css selector', value: 'form input' });
    await typeInto(id(inputs[0]), 'E2E Artist');
    await typeInto(id(inputs[1]), 'Dropped and marked');
    await js(`return window.__TAURI__.event.emitTo({kind: 'Webview', label: 'main'}, 'tauri://drag-drop',
              {paths: [arguments[0]], position: {x: 100, y: 300}})`, [process.env.MARK_AUDIO]);
    await sleep(300);
    console.log('mark: chosen file shown:', await js('return document.querySelector(".drop").textContent'));
    const btn = await wd('POST', `/session/${sid}/element`, { using: 'css selector', value: 'button.big' });
    await wd('POST', `/session/${sid}/element/${id(btn)}/click`, {});
    let res = '';
    for (let i = 0; i < 300; i++) {
      res = await js('const b = document.querySelector(".banner.ok, .banner.warn"); return b ? b.textContent : ""');
      if (res) break;
      await sleep(200);
    }
    console.log('mark result:', res || '(none after 60 s)');
    console.log('errors:', JSON.stringify(await js('return window.__errs')));
    await shot('mark');
  }
} finally {
  await wd('DELETE', `/session/${sid}`);
}
