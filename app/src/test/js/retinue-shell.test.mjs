// Tests of assets/retinue-shell.js (the RetinueShell bridge and the
// speechSynthesis stand-in) against a fake native side. No dependencies:
//   node app/src/test/js/retinue-shell.test.mjs
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

globalThis.window = globalThis;
// Node's global object is no EventTarget: give it window's listener methods.
const windowEvents = new EventTarget();
globalThis.addEventListener = windowEvents.addEventListener.bind(windowEvents);
globalThis.removeEventListener = windowEvents.removeEventListener.bind(windowEvents);
globalThis.dispatchEvent = windowEvents.dispatchEvent.bind(windowEvents);
const log = [];
let spoken = [];
let failNext = false;
const timers = new Set();
window.RetinueShellNative = {
  postMessage(s) {
    const m = JSON.parse(s);
    log.push(m.type);
    if (m.type === 'tts.voices') {
      setTimeout(() => window.__retinueShellReceive({ type: 'tts.voices', voices: [
        { name: 'de-de-x-local', lang: 'de-DE', localService: true, default: false },
        { name: 'en-us-x-net', lang: 'en-US', localService: false, default: true }] }), 1);
    } else if (m.type === 'tts.speak') {
      spoken.push(m);
      const id = m.id;
      const f = failNext; failNext = false;
      const t1 = setTimeout(() => window.__retinueShellReceive({ type: 'tts.start', id }), 2);
      const t2 = setTimeout(() => window.__retinueShellReceive(f ? { type: 'tts.error', id, error: 'synthesis-failed' } : { type: 'tts.end', id }), 10);
      timers.add(t1); timers.add(t2);
    } else if (m.type === 'tts.cancel' || m.type === 'tts.pause') {
      // native stop: no events (the page ignores the superseded id anyway)
    }
  },
};
const src = fs.readFileSync(new URL('../../main/assets/retinue-shell.js', import.meta.url), 'utf8').replace('__RETINUE_SHELL_VERSION__', '1.2');
vm.runInThisContext(src);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

assert.equal(window.RetinueShell.platform, 'android');
assert.equal(window.RetinueShell.version, '1.2');
assert.equal(window.RetinueShell.nativeSpeech, true);
window.RetinueShell.setVoiceActive(true);
window.RetinueShell.openSettings();
assert.deepEqual(log.slice(0, 1), ['tts.voices']);
assert.ok(log.includes('voiceActive') && log.includes('openSettings'));

// shell events: the shell asks whether the page listens (ShellMessages.eventScript)
const eventScript = (type) => "(function(){var e=new CustomEvent('retinue-shell',{detail:{\"type\":\"" + type + "\"},cancelable:true});" +
  'window.dispatchEvent(e);' +
  'return e.defaultPrevented||!!(window.__retinueShellListening&&window.__retinueShellListening());})();';
assert.equal(vm.runInThisContext(eventScript('wake')), false);
const seen = [];
const onShell = (e) => seen.push(e.detail.type);
window.addEventListener('retinue-shell', onShell);
window.addEventListener('retinue-shell', onShell); // a duplicate is one listener
assert.equal(vm.runInThisContext(eventScript('wake')), true);
assert.deepEqual(seen, ['wake']);
window.removeEventListener('retinue-shell', onShell);
assert.equal(vm.runInThisContext(eventScript('home')), false);
// A listener the count does not see (added before the script ran) can claim the event.
const claim = (e) => e.preventDefault();
windowEvents.addEventListener('retinue-shell', claim);
assert.equal(vm.runInThisContext(eventScript('home')), true);
windowEvents.removeEventListener('retinue-shell', claim);
assert.equal(vm.runInThisContext(eventScript('home')), false);
console.log('shell event tests passed');

// voiceschanged
let vc = 0;
speechSynthesis.onvoiceschanged = () => vc++;
await sleep(5);
assert.equal(vc, 1);
assert.equal(speechSynthesis.getVoices().length, 2);
assert.equal(speechSynthesis.getVoices()[0].localService, true);

// basic speak: start, end, flags
const ev = [];
const u = new SpeechSynthesisUtterance('Hallo');
u.lang = 'de-DE'; u.voice = speechSynthesis.getVoices()[0]; u.rate = 1.2;
u.onstart = (e) => ev.push('start:' + (e.utterance === u));
u.addEventListener('end', () => ev.push('end-listener'));
u.onend = () => ev.push('end');
speechSynthesis.speak(u);
assert.equal(speechSynthesis.speaking, true);
assert.equal(spoken.at(-1).voice, 'de-de-x-local');
assert.equal(spoken.at(-1).lang, 'de-DE');
await sleep(20);
assert.deepEqual(ev, ['start:true', 'end-listener', 'end']);
assert.equal(speechSynthesis.speaking, false);

// queue + pending + cancel semantics
const a = new SpeechSynthesisUtterance('a'), b = new SpeechSynthesisUtterance('b');
const errs = [];
a.onerror = (e) => errs.push('a:' + e.error); b.onerror = (e) => errs.push('b:' + e.error);
speechSynthesis.speak(a); speechSynthesis.speak(b);
assert.equal(speechSynthesis.pending, true);
await sleep(4); // a started
speechSynthesis.cancel();
assert.equal(speechSynthesis.speaking, false);
assert.equal(speechSynthesis.pending, false);
await sleep(15);
assert.deepEqual(errs, ['a:interrupted', 'b:canceled']);

// error from engine moves on
failNext = true;
const c = new SpeechSynthesisUtterance('c'), d = new SpeechSynthesisUtterance('d');
const cd = [];
c.onerror = (e) => cd.push('c:' + e.error); d.onend = () => cd.push('d:end');
speechSynthesis.speak(c); speechSynthesis.speak(d);
await sleep(40);
assert.deepEqual(cd, ['c:synthesis-failed', 'd:end']);

// pause/resume restarts the current utterance; no second start
const p = new SpeechSynthesisUtterance('p');
const pe = [];
['start', 'pause', 'resume', 'end'].forEach((t) => p.addEventListener(t, () => pe.push(t)));
speechSynthesis.speak(p);
await sleep(4);
speechSynthesis.pause();
assert.equal(speechSynthesis.paused, true);
await sleep(15);
assert.deepEqual(pe, ['start', 'pause']);
const n = spoken.length;
speechSynthesis.resume();
assert.equal(spoken.length, n + 1);
await sleep(20);
assert.deepEqual(pe, ['start', 'pause', 'resume', 'end']);

// paused engine queues silently (Chrome behaviour speech.js works around)
speechSynthesis.pause();
const q = new SpeechSynthesisUtterance('q');
const n2 = spoken.length;
speechSynthesis.speak(q);
assert.equal(spoken.length, n2);
speechSynthesis.resume();
assert.equal(spoken.length, n2 + 1);
await sleep(20);

assert.throws(() => speechSynthesis.speak({ text: 'x' }), TypeError);

for (const t of timers) clearTimeout(t);
console.log('stand-in tests passed');

// ---- Take-over of a WebView speechSynthesis that has no voices ----------
async function runIsolated(voices) {
  const ctx = { setTimeout, clearTimeout, Event, EventTarget, JSON, Object, Number, String, TypeError, console };
  ctx.window = ctx;
  const posts = [];
  ctx.RetinueShellNative = { postMessage: (s) => posts.push(JSON.parse(s).type) };
  const own = { getVoices: () => voices, addEventListener() {}, speak() {} };
  ctx.speechSynthesis = own; ctx.SpeechSynthesisUtterance = function () {};
  vm.createContext(ctx); vm.runInContext(src, ctx);
  assert.equal(ctx.speechSynthesis, own);
  await new Promise((r) => setTimeout(r, 1600));
  return { replaced: ctx.speechSynthesis !== own, native: ctx.RetinueShell.nativeSpeech, posts };
}
const silent = await runIsolated([]);
assert.deepEqual([silent.replaced, silent.native], [true, true]);
assert.ok(silent.posts.includes('tts.voices'));
const working = await runIsolated([{ name: 'x', lang: 'en' }]);
assert.deepEqual([working.replaced, working.native], [false, false]);
console.log('take-over tests passed');
