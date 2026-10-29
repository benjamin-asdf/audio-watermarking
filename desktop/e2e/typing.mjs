// Types into the login email field the way a person does: one key at a time through the WebDriver
// actions API (real keydown/keyup), ~60 ms apart, then compares the field with what was typed.
// Prints lengths and the first differing position only.
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
const sleep = ms => new Promise(r => setTimeout(r, ms));
const text = 'artist.name+test@example.invalid';
const delay = Number(process.env.KEY_DELAY_MS || 60);
const rounds = Number(process.env.ROUNDS || 5);
try {
  for (let i = 0; i < 50 && !(await js('return !!document.querySelector(".drop")')); i++) await sleep(200);
  await js(`history.pushState(null, '', '/login'); dispatchEvent(new PopStateEvent('popstate'));`);
  await sleep(500);
  const el = await wd('POST', `/session/${sid}/element`, { using: 'css selector', value: 'input[type=email]' });
  const elId = Object.values(el)[0];
  let bad = 0;
  for (let round = 1; round <= rounds; round++) {
    await wd('POST', `/session/${sid}/element/${elId}/clear`, {});
    await wd('POST', `/session/${sid}/element/${elId}/click`, {});
    const actions = [];
    for (const ch of text) actions.push({ type: 'keyDown', value: ch }, { type: 'keyUp', value: ch }, { type: 'pause', duration: delay });
    await wd('POST', `/session/${sid}/actions`, { actions: [{ type: 'key', id: 'kb', actions }] });
    await wd('DELETE', `/session/${sid}/actions`);
    await sleep(200);
    const got = await wd('GET', `/session/${sid}/element/${elId}/property/value`);
    let at = 0;
    while (at < Math.min(got.length, text.length) && got[at] === text[at]) at++;
    const ok = got === text;
    if (!ok) bad++;
    console.log(`round ${round}: ${ok ? 'ok' : `MISMATCH got ${got.length}/${text.length} chars, first difference at ${at}`}`);
  }
  console.log(`typing: ${rounds - bad}/${rounds} rounds exact (${delay} ms per key)`);
} finally {
  await wd('DELETE', `/session/${sid}`).catch(() => {});
}
