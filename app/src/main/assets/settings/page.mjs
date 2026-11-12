// The Ara app's settings page (index.html): renders the state the app sends
// and sends back what the user does. The app is reached through
// AraSettingsNative, which it injects for this page's origin only
// (os.retinue.ara.settings.SettingsBridge): postMessage(json) to the app,
// `message` events from it.
//
// To the app: {type: 'hello'} once loaded, 'close', 'retry', 'open' {url},
// and one message per setting (SettingsMessages in the app lists them all).
// From the app: 'state' (the whole page, whenever anything in it changes),
// 'saved' (the answer to an endpoint save), 'certificate' (the chooser's
// answer), 'certificateImport' (how an import ended) and 'health' (a health
// sync's events). The decisions live in logic.mjs; this module only reads and
// writes the page.

import * as L from './logic.mjs';

const $ = (sel) => document.querySelector(sel);
const native = window.AraSettingsNative || null;
// How long the instance's own section may take to say it is there.
const INSTANCE_WAIT_MS = 10000;
// How long a health sync's outcome stays on its line before the status line returns.
const HEALTH_EVENT_MS = 8000;

let state = null;
let firstState = true;
let editor = null;        // {draft, saving: request id | null, certRequest: request id | null, importOutcome: key | null}
let dialogDone = null;    // (confirmed) => void, while the dialog is up
let requestSeq = 0;
let instanceFor = null;   // what the instance frame was loaded for
let instanceTimer = null;
let healthEvent = null;   // {text, until}: a sync's line, shown instead of the status line

function send(msg) {
  if (!native) return;
  try { native.postMessage(JSON.stringify(msg)); } catch (_e) { /* the app is gone */ }
}

function nextRequest() {
  requestSeq += 1;
  return `r${requestSeq}`;
}

function newId() {
  if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
  return `ep-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  return e;
}

function button(text, onClick, cls = 'btn') {
  const b = el('button', cls, text);
  b.type = 'button';
  b.addEventListener('click', onClick);
  return b;
}

// A field the user may be typing in keeps what they typed.
function setValue(input, value) {
  if (document.activeElement !== input) input.value = value;
}

// ── From the app ─────────────────────────────────────────────────────────────

function onMessage(raw) {
  let msg;
  try { msg = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (_e) { return; }
  if (!msg || typeof msg !== 'object') return;
  if (msg.type === 'state' && msg.state && typeof msg.state === 'object') {
    state = msg.state;
    render();
    if (firstState) {
      firstState = false;
      openFromHash();
    }
  } else if (msg.type === 'saved') {
    onSaved(msg);
  } else if (msg.type === 'certificate') {
    onCertificate(msg);
  } else if (msg.type === 'certificateImport') {
    onCertificateImport(msg);
  } else if (msg.type === 'health') {
    onHealthEvent(msg.event);
  }
}

// The app opens the page at an endpoint's editor (#edit=<id>) or a new one's (#add).
function openFromHash() {
  const hash = location.hash || '';
  if (hash === '#add') {
    openEditor(L.newDraft(newId()));
  } else if (hash.startsWith('#edit=')) {
    const id = decodeURIComponent(hash.slice('#edit='.length));
    const row = (state.endpoints || []).find((r) => r.id === id);
    if (row) openEditor(L.draftOf(row));
  }
  if (hash) history.replaceState(null, '', location.pathname);
}

// ── Rendering ────────────────────────────────────────────────────────────────

function render() {
  if (!state) return;
  renderFailure();
  renderEndpoints();
  renderDevice();
  renderHome();
  renderSplit();
  renderWake();
  renderLauncher();
  renderUpdates();
  $('[data-version]').textContent = state.version ? `Ara shell ${state.version}` : '';
  renderEndpointSection();
  renderInstance();
}

function renderFailure() {
  const f = state.failure;
  $('[data-failure]').hidden = !f;
  if (!f) return;
  $('[data-failure-reason]').textContent = f.reason || '';
  $('[data-failure-url]').textContent = f.url || '';
  const errors = Array.isArray(f.consoleErrors) ? f.consoleErrors : [];
  $('[data-failure-console]').hidden = errors.length === 0;
  $('[data-failure-errors]').replaceChildren(...errors.map((e) => el('li', '', String(e))));
}

function renderEndpoints() {
  const rows = Array.isArray(state.endpoints) ? state.endpoints : [];
  $('[data-endpoints-empty]').hidden = rows.length > 0;
  $('[data-endpoints]').replaceChildren(...rows.map(endpointItem));
}

function endpointItem(row) {
  const li = el('li', 'endpoint');
  const who = el('div', 'who');
  who.append(el('div', 'name', row.label), el('div', 'url', row.baseUrl), el('div', 'summary', L.endpointSummary(row)));
  li.append(who);
  if (row.current) {
    li.append(el('span', 'active', L.STATE_TEXT.foreground));
  } else {
    const st = L.instanceState(row);
    li.append(el('span', `state ${st}`, L.STATE_TEXT[st]));
    const use = button('Use', () => send({ type: 'endpoint.select', id: row.id }));
    use.setAttribute('aria-label', `Show ${row.label}`);
    li.append(use);
  }
  const edit = button('Edit', () => openEditor(L.draftOf(row)));
  edit.setAttribute('aria-label', `Edit ${row.label}`);
  li.append(edit);
  return li;
}

function renderDevice() {
  const device = state.device || {};
  const voice = L.voiceSeconds(device);
  setValue($('[data-voice-silence]'), String(voice.silence));
  setValue($('[data-voice-nospeech]'), String(voice.noSpeech));
  setValue($('[data-shelf-size]'), String(L.shelfSize(device)));
}

function renderHome() {
  const isDefault = !!(state.home && state.home.isDefault);
  $('[data-home-status]').textContent = isDefault
    ? 'Ara is your home app: the Home button shows the dashboard.'
    : 'Ara is not your home app yet.';
  $('[data-home-request]').textContent = isDefault ? 'Change home app' : 'Set as default home app';
}

// A note of a card (logic.mjs), with the button that fixes it where the app has one.
function noteBox(n) {
  const box = el('div', 'notes');
  box.append(el('p', '', n.text));
  if (n.action) {
    const row = el('div', 'actions');
    row.append(button(n.label, () => send({ type: n.action })));
    box.append(row);
  }
  return box;
}

function renderSplit() {
  const s = state.split || {};
  $('[data-split-launch]').checked = !!s.launch;
  $('[data-split-recents]').checked = !!s.recents;
}

function renderWake() {
  const w = state.wake || {};
  for (const r of document.querySelectorAll('input[name="wake-mode"]')) r.checked = r.value === w.mode;
  $('[data-wake-status]').textContent = w.status || '';
  const also = L.alsoListeningLine(w);
  $('[data-wake-also]').textContent = also;
  $('[data-wake-also]').hidden = !also;
  const notes = $('[data-wake-notes]');
  notes.replaceChildren(...L.wakeNotes(w).map(noteBox));
  const threshold = $('[data-wake-threshold]');
  if (document.activeElement !== threshold && Number.isFinite(w.threshold)) threshold.value = String(w.threshold);
  $('[data-wake-threshold-value]').textContent = Number(threshold.value).toFixed(2);
  $('[data-wake-models-line]').textContent = L.wakeModelsLine(w);
  $('[data-wake-active]').textContent = L.wakeActiveLine(w);
  const models = Array.isArray(w.models) ? w.models : [];
  $('[data-wake-models]').replaceChildren(...models.map((m) => wakeModelItem(m, w)));
  for (const sel of ['[data-wake-features]', '[data-wake-phrase]', '[data-wake-pick]']) $(sel).disabled = !!w.busy;
  $('[data-wake-message]').textContent = w.busy ? 'Working…' : (w.message || '');
}

function wakeModelItem(m, w) {
  const row = L.wakeModelRow(m, w);
  const li = el('li', `endpoint model${row.active ? ' current' : ''}`);
  const who = el('div', 'who');
  who.append(el('div', 'name', row.name), el('div', 'summary', row.detail));
  li.append(who);
  if (row.active) li.append(el('span', 'active', 'Active'));
  const actions = el('div', 'actions');
  for (const a of row.actions) {
    const b = button(a.label, () => {
      if (!a.confirm) { send(a.msg); return; }
      showDialog({
        title: `Remove ${row.name}?`,
        text: 'Its file is deleted from this phone. To use it again, upload it again.',
        confirm: 'Remove',
        cancel: 'Cancel',
        onConfirm: () => send(a.msg),
      });
    }, `btn${a.primary ? ' primary' : ''}${a.danger ? ' danger' : ''}`);
    b.disabled = !!a.disabled;
    b.setAttribute('aria-label', `${a.label}: ${row.name}`);
    actions.append(b);
  }
  li.append(actions);
  return li;
}

function renderLauncher() {
  const note = L.usageAccessNote(state.launcher);
  $('[data-launcher-usage]').textContent = note.text;
  $('[data-launcher-usage-access]').textContent = note.label;
}

function renderUpdates() {
  const u = state.updates || {};
  $('[data-update-installed]').textContent = u.installed || '';
  $('[data-update-auto]').checked = !!u.auto;
  for (const r of document.querySelectorAll('input[name="update-channel"]')) r.checked = r.value === u.channel;
  $('[data-update-status]').textContent = u.status || '';
  const progress = $('[data-update-progress]');
  const hasProgress = Number.isFinite(u.progress);
  progress.hidden = !hasProgress;
  if (hasProgress) progress.value = Math.max(0, Math.min(1, u.progress));
  $('[data-update-check]').disabled = !u.canCheck;
  const install = $('[data-update-install]');
  install.hidden = !u.installTag;
  install.textContent = u.installTag ? `Install ${u.installTag}` : '';
  $('[data-update-confirm]').hidden = !u.awaitingConfirmation;
  $('[data-update-cancel]').hidden = !u.awaitingConfirmation;
  $('[data-update-permission]').hidden = u.installAllowed !== false;
}

function renderEndpointSection() {
  const cur = state.current;
  for (const sel of ['[data-endpoint-group]', '[data-health]', '[data-calls]', '[data-wake-card]', '[data-notifications]']) $(sel).hidden = !cur;
  if (!cur) return;
  $('[data-endpoint-name]').textContent = cur.label;
  $('[data-wake-name]').textContent = cur.label;
  $('[data-health-name]').textContent = cur.label;
  const st = state.health || {};
  const available = !!st.available;
  $('[data-health-unavailable]').hidden = available;
  $('[data-health-available]').hidden = !available;
  $('[data-health-upload]').checked = !!st.enabled;
  $('[data-health-controls]').hidden = !st.enabled;
  const ev = healthEvent && healthEvent.until > Date.now() ? healthEvent.text : null;
  $('[data-health-line]').textContent = ev || L.describeHealthSync(st);
  $('[data-health-auto]').checked = !!st.auto_on_resume;
  setValue($('[data-health-interval]'), String(st.min_interval_minutes || 5));
  // The switch is the current endpoint's own; the permission and telephony are the phone's.
  const c = state.calls || {};
  $('[data-calls-unavailable]').hidden = !!c.telephony;
  $('[data-calls-available]').hidden = !c.telephony;
  $('[data-calls-direct]').checked = !!c.direct;
  $('[data-calls-notes]').replaceChildren(...L.callNotes(c).map(noteBox));
}

// The instance's own section: its settings-instance.html in a frame, shown once
// it says it is there (and how tall it is); a way to the dashboard otherwise.
function renderInstance() {
  const cur = state.current;
  const frame = $('[data-instance-frame]');
  const fallback = $('[data-instance-fallback]');
  $('[data-instance-group]').hidden = !cur;
  if (!cur || !cur.instanceUrl) {
    instanceFor = null;
    clearTimeout(instanceTimer);
    frame.hidden = true;
    frame.removeAttribute('src');
    // An endpoint whose section cannot be framed here: the way to the dashboard at once.
    fallback.hidden = !cur;
    return;
  }
  // Another endpoint, or a new certificate or sign-in for it: the section loads afresh.
  const key = `${cur.id} ${cur.instanceUrl} ${cur.frameKey || ''}`;
  if (instanceFor === key) return;
  instanceFor = key;
  fallback.hidden = true;
  frame.classList.add('loading');
  frame.hidden = false;
  frame.src = cur.instanceUrl;
  clearTimeout(instanceTimer);
  instanceTimer = setTimeout(() => {
    if (frame.classList.contains('loading')) {
      frame.hidden = true;
      fallback.hidden = false;
    }
  }, INSTANCE_WAIT_MS);
}

window.addEventListener('message', (e) => {
  const cur = state && state.current;
  const frame = $('[data-instance-frame]');
  if (!cur || e.source !== frame.contentWindow || e.origin !== cur.origin) return;
  const height = L.instanceHeight(e.data);
  if (height === null) return;
  clearTimeout(instanceTimer);
  frame.classList.remove('loading');
  frame.hidden = false;
  frame.style.height = `${height}px`;
  $('[data-instance-fallback]').hidden = true;
});

// ── The endpoint editor ──────────────────────────────────────────────────────

function openEditor(draft) {
  editor = { draft, saving: null, certRequest: null, importOutcome: null };
  $('[data-view="main"]').hidden = true;
  $('[data-view="editor"]').hidden = false;
  fillEditor();
  window.scrollTo(0, 0);
}

function closeEditor() {
  editor = null;
  closeDialog();
  $('[data-view="editor"]').hidden = true;
  $('[data-view="main"]').hidden = false;
}

function fillEditor() {
  const d = editor.draft;
  $('[data-editor-title]').textContent = d.isNew ? 'Add endpoint' : (d.name || 'Edit endpoint');
  $('[data-draft-url]').value = d.baseUrl;
  $('[data-draft-user]').value = d.username;
  $('[data-draft-password]').value = d.password;
  $('[data-draft-health]').checked = !!d.healthUpload;
  $('[data-draft-background]').checked = !!d.background;
  $('[data-draft-remove-card]').hidden = !!d.isNew;
  renderEditorState();
}

// What follows from the draft, without touching the text being typed.
function renderEditorState() {
  if (!editor) return;
  const d = editor.draft;
  $('[data-draft-cert]').textContent = d.certificateAlias || 'None selected';
  $('[data-draft-cert-clear]').hidden = !d.certificateAlias;
  const note = L.importNote(editor.importOutcome);
  const noteEl = $('[data-draft-cert-note]');
  noteEl.hidden = !note;
  noteEl.textContent = note ? note.text : '';
  noteEl.className = note && note.error ? 'error' : 'hint';
  const urlBad = String(d.baseUrl).trim() !== '' && !L.normalizeBase(d.baseUrl);
  const urlError = $('[data-draft-url-error]');
  urlError.hidden = !urlBad;
  urlError.textContent = L.isCleartext(d.baseUrl) ? 'Retinue needs https.' : 'This is not an https address.';
  $('[data-draft-password-stored]').hidden = !(d.hasStoredPassword && !d.forgetPassword && !d.password);
  $('[data-draft-forget]').hidden = !(d.hasStoredPassword && !d.forgetPassword);
  const problem = L.draftProblem(d);
  const signin = $('[data-draft-signin-error]');
  const signinProblem = problem === 'password' || problem === 'user';
  signin.hidden = !signinProblem;
  signin.textContent = signinProblem ? L.PROBLEM_TEXT[problem] : '';
  $('[data-editor-save]').disabled = !!problem || !!editor.saving;
}

function saveDraft() {
  if (!editor || editor.saving) return;
  const d = editor.draft;
  if (L.draftProblem(d)) { renderEditorState(); return; }
  editor.saving = nextRequest();
  renderEditorState();
  send({ type: 'endpoint.save', request: editor.saving, draft: L.draftMessage(d) });
}

function onSaved(msg) {
  if (!editor || msg.request !== editor.saving) return;
  editor.saving = null;
  if (msg.ok) { closeEditor(); return; }
  renderEditorState();
  showDialog({
    title: 'The endpoint was not saved',
    text: L.PROBLEM_TEXT[msg.problem] || 'The app could not keep it.',
    confirm: 'OK',
  });
}

function onCertificate(msg) {
  if (!editor || msg.request !== editor.certRequest) return;
  editor.certRequest = null;
  if (msg.alias) editor.draft.certificateAlias = String(msg.alias);
  editor.importOutcome = L.importChosen(editor.importOutcome, msg.alias);
  renderEditorState();
}

// How an import ended. Once installed, the chooser's answer follows with the
// same request (onCertificate); any other outcome ends the request.
function onCertificateImport(msg) {
  if (!editor || msg.request !== editor.certRequest) return;
  editor.importOutcome = typeof msg.outcome === 'string' ? msg.outcome : null;
  if (editor.importOutcome !== 'installed') editor.certRequest = null;
  renderEditorState();
}

// Back, or the arrow in the editor's bar: an untouched draft closes, changes
// are saved on the way out, and changes that cannot be saved are asked about.
function editorBack() {
  if (!editor || editor.saving) return;
  const close = L.onClose(editor.draft);
  if (close.action === 'close') {
    closeEditor();
  } else if (close.action === 'save') {
    saveDraft();
  } else {
    showDialog({
      title: 'Discard your changes?',
      text: `They cannot be saved. ${L.PROBLEM_TEXT[close.problem]}`,
      confirm: 'Discard',
      cancel: 'Keep editing',
      onConfirm: closeEditor,
    });
  }
}

$('[data-editor-back]').addEventListener('click', editorBack);
$('[data-editor-save]').addEventListener('click', saveDraft);
$('[data-draft-url]').addEventListener('input', (e) => { editor.draft.baseUrl = e.target.value; renderEditorState(); });
$('[data-draft-user]').addEventListener('input', (e) => { editor.draft.username = e.target.value; renderEditorState(); });
$('[data-draft-password]').addEventListener('input', (e) => {
  editor.draft.password = e.target.value;
  editor.draft.forgetPassword = false;
  renderEditorState();
});
$('[data-draft-health]').addEventListener('change', (e) => { editor.draft.healthUpload = e.target.checked; renderEditorState(); });
$('[data-draft-background]').addEventListener('change', (e) => { editor.draft.background = e.target.checked; renderEditorState(); });
$('[data-draft-forget]').addEventListener('click', () => {
  editor.draft.forgetPassword = true;
  editor.draft.password = '';
  $('[data-draft-password]').value = '';
  renderEditorState();
});
$('[data-draft-cert-select]').addEventListener('click', () => {
  const d = editor.draft;
  editor.certRequest = nextRequest();
  editor.importOutcome = null;
  renderEditorState();
  send({ type: 'endpoint.certificate', request: editor.certRequest, baseUrl: String(d.baseUrl).trim(), current: d.certificateAlias });
});
$('[data-draft-cert-import]').addEventListener('click', () => {
  editor.certRequest = nextRequest();
  editor.importOutcome = null;
  renderEditorState();
  send({ type: 'endpoint.certificateImport', request: editor.certRequest, baseUrl: String(editor.draft.baseUrl).trim() });
});
$('[data-draft-cert-clear]').addEventListener('click', () => {
  editor.draft.certificateAlias = null;
  editor.importOutcome = null;
  renderEditorState();
});
$('[data-draft-remove]').addEventListener('click', () => {
  const d = editor.draft;
  const label = d.name || L.normalizeBase(d.baseUrl) || 'this endpoint';
  showDialog({
    title: `Remove ${label}?`,
    text: 'Its address, certificate choice and stored password are forgotten on this phone. Nothing changes on the server.',
    confirm: 'Remove',
    cancel: 'Cancel',
    onConfirm: () => {
      send({ type: 'endpoint.remove', id: d.id });
      closeEditor();
    },
  });
});

// ── The in-page dialog ───────────────────────────────────────────────────────

function showDialog({ title, text, confirm, cancel, onConfirm }) {
  $('[data-dialog-title]').textContent = title;
  $('[data-dialog-text]').textContent = text;
  const ok = $('[data-dialog-confirm]');
  ok.textContent = confirm;
  const no = $('[data-dialog-cancel]');
  no.textContent = cancel || '';
  no.hidden = !cancel;
  dialogDone = (confirmed) => {
    closeDialog();
    if (confirmed && onConfirm) onConfirm();
  };
  $('[data-dialog]').hidden = false;
  ok.focus();
}

function closeDialog() {
  dialogDone = null;
  $('[data-dialog]').hidden = true;
}

$('[data-dialog-confirm]').addEventListener('click', () => { if (dialogDone) dialogDone(true); });
$('[data-dialog-cancel]').addEventListener('click', () => { if (dialogDone) dialogDone(false); });

// The phone's Back: the app asks the page first (SettingsBridge), and closes
// the page only when nothing here takes it.
window.__araSettingsBack = () => {
  if (dialogDone) { dialogDone(false); return true; }
  if (editor) { editorBack(); return true; }
  return false;
};

// ── What the user does on the main view ──────────────────────────────────────

$('[data-close]').addEventListener('click', () => send({ type: 'close' }));
$('[data-retry]').addEventListener('click', () => send({ type: 'retry' }));
$('[data-endpoint-add]').addEventListener('click', () => openEditor(L.newDraft(newId())));
$('[data-instance-open]').addEventListener('click', () => {
  if (state && state.current && state.current.settingsUrl) send({ type: 'open', url: state.current.settingsUrl });
});

$('[data-voice-silence]').addEventListener('change', (e) => {
  const value = L.voiceValue(L.VOICE_SILENCE, e.target.value);
  e.target.value = String(Number(value) / 1000);
  send({ type: 'device.set', key: L.VOICE_SILENCE.key, value });
});
$('[data-voice-nospeech]').addEventListener('change', (e) => {
  const value = L.voiceValue(L.VOICE_NO_SPEECH, e.target.value);
  e.target.value = String(Number(value) / 1000);
  send({ type: 'device.set', key: L.VOICE_NO_SPEECH.key, value });
});
$('[data-shelf-size]').addEventListener('change', (e) => {
  const size = L.clampShelf(e.target.value);
  e.target.value = String(size);
  send({ type: 'device.set', key: L.SHELF_SIZE.key, value: String(size) });
});

$('[data-home-request]').addEventListener('click', () => send({ type: 'home.request' }));
$('[data-split-launch]').addEventListener('change', (e) => send({ type: 'split.launch', on: e.target.checked }));
$('[data-split-recents]').addEventListener('change', (e) => send({ type: 'split.recents', on: e.target.checked }));

for (const r of document.querySelectorAll('input[name="wake-mode"]')) {
  r.addEventListener('change', () => { if (r.checked) send({ type: 'wake.mode', mode: r.value }); });
}
$('[data-wake-threshold]').addEventListener('input', (e) => {
  $('[data-wake-threshold-value]').textContent = Number(e.target.value).toFixed(2);
});
$('[data-wake-threshold]').addEventListener('change', (e) => send({ type: 'wake.threshold', value: Number(e.target.value) }));
$('[data-wake-features]').addEventListener('click', () => send({ type: 'wake.features' }));
$('[data-wake-phrase]').addEventListener('click', () => {
  const url = $('[data-wake-url]').value.trim();
  if (url) {
    send({ type: 'wake.phrase', url });
    $('[data-wake-url]').value = '';
  }
});
$('[data-wake-pick]').addEventListener('click', () => send({ type: 'wake.pick' }));

$('[data-launcher-edit]').addEventListener('click', () => send({ type: 'launcher.edit' }));
$('[data-launcher-usage-access]').addEventListener('click', () => send({ type: 'launcher.usageAccess' }));

$('[data-update-auto]').addEventListener('change', (e) => send({ type: 'update.auto', on: e.target.checked }));
for (const r of document.querySelectorAll('input[name="update-channel"]')) {
  r.addEventListener('change', () => { if (r.checked) send({ type: 'update.channel', channel: r.value }); });
}
$('[data-update-check]').addEventListener('click', () => send({ type: 'update.check' }));
$('[data-update-install]').addEventListener('click', () => send({ type: 'update.install' }));
$('[data-update-confirm]').addEventListener('click', () => send({ type: 'update.confirm' }));
$('[data-update-cancel]').addEventListener('click', () => send({ type: 'update.cancel' }));
$('[data-update-allow]').addEventListener('click', () => send({ type: 'update.allowInstalls' }));

$('[data-health-upload]').addEventListener('change', (e) => send({ type: 'health.upload', on: e.target.checked }));
$('[data-health-sync]').addEventListener('click', () => {
  $('[data-health-line]').textContent = 'Updating health data…';
  send({ type: 'health.sync' });
});
$('[data-health-auto]').addEventListener('change', (e) => send({ type: 'health.auto', on: e.target.checked }));
$('[data-health-interval]').addEventListener('change', (e) => {
  const minutes = Math.round(Number(e.target.value));
  if (Number.isFinite(minutes) && minutes >= 1) send({ type: 'health.interval', minutes: Math.min(minutes, 1440) });
});

function onHealthEvent(d) {
  if (!d || typeof d !== 'object') return;
  const text = L.describeHealthSyncEvent(d);
  if (!text) return;
  const btn = $('[data-health-sync]');
  if (d.state === 'started') {
    healthEvent = { text, until: Infinity };
    btn.disabled = true;
  } else {
    healthEvent = { text, until: Date.now() + HEALTH_EVENT_MS };
    btn.disabled = false;
    setTimeout(render, HEALTH_EVENT_MS + 100);
  }
  $('[data-health-line]').textContent = text;
}

$('[data-calls-direct]').addEventListener('change', (e) => send({ type: 'calls.direct', on: e.target.checked }));

// ── Start ────────────────────────────────────────────────────────────────────

if (native) {
  if (typeof native.addEventListener === 'function') native.addEventListener('message', (e) => onMessage(e.data));
  else native.onmessage = (e) => onMessage(e.data);
  send({ type: 'hello' });
} else {
  $('[data-no-bridge]').hidden = false;
}
