// Tests of the settings page's decisions (assets/settings/logic.mjs): the
// endpoint draft rules and what closing the editor does, the certificate
// import's notes, the per-device values with the dashboard's bounds, the
// health, phone call and wake lines, and the instance frame's message. No dependencies:
//   node app/src/test/js/settings-page.test.mjs
import assert from 'node:assert/strict';
import * as L from '../../main/assets/settings/logic.mjs';

let passed = 0;
function ok(name, fn) { fn(); passed++; console.log('  ok  ' + name); }

const home = {
  id: 'home', label: 'Retinue', baseUrl: 'https://agents.example.com', certificateAlias: 'ara-phone',
  username: 'reto', hasPassword: true, healthUpload: true, current: true, name: 'Retinue',
};

ok('base URLs are normalised as the app does, https only', () => {
  assert.equal(L.normalizeBase('agents.example.com'), 'https://agents.example.com');
  assert.equal(L.normalizeBase(' https://agents.example.com/ '), 'https://agents.example.com');
  assert.equal(L.normalizeBase('https://agents.example.com/retinue/?x=1#y'), 'https://agents.example.com/retinue');
  assert.equal(L.normalizeBase('https://agents.example.com:8443'), 'https://agents.example.com:8443');
  for (const bad of ['', '   ', 'http://plain.example', 'ftp://x.example', 'https://']) {
    assert.equal(L.normalizeBase(bad), null, bad);
  }
  assert.ok(L.isCleartext('http://plain.example'));
  assert.ok(!L.isCleartext('https://agents.example.com'));
});

ok('an untouched draft just closes, also once changed and changed back', () => {
  assert.deepEqual(L.onClose(L.draftOf(home)), { action: 'close' });
  assert.deepEqual(L.onClose(L.newDraft('n')), { action: 'close' });
  const d = L.draftOf(home);
  d.baseUrl = 'https://else.example';
  d.baseUrl = ' https://agents.example.com ';
  d.username = 'reto ';
  assert.equal(L.draftChanged(d), false);
});

ok('changes that can be saved are saved on the way out', () => {
  for (const change of [
    (d) => { d.healthUpload = false; },
    (d) => { d.certificateAlias = null; },
    (d) => { d.password = 'new secret'; },
    (d) => { d.username = ''; d.forgetPassword = true; },
    (d) => { d.baseUrl = 'agents.example.com/retinue'; },
  ]) {
    const d = L.draftOf(home);
    change(d);
    assert.deepEqual(L.onClose(d), { action: 'save' });
  }
  const fresh = L.newDraft('n');
  fresh.baseUrl = 'https://admin.example.com';
  assert.deepEqual(L.onClose(fresh), { action: 'save' });
});

ok('changes that cannot be saved are asked about, never dropped', () => {
  const noAddress = L.newDraft('n');
  noAddress.healthUpload = true;
  assert.deepEqual(L.onClose(noAddress), { action: 'ask', problem: 'address' });
  const plain = L.draftOf(home);
  plain.baseUrl = 'http://plain.example';
  assert.deepEqual(L.onClose(plain), { action: 'ask', problem: 'address' });
  const forgot = L.draftOf(home);
  forgot.forgetPassword = true;
  assert.deepEqual(L.onClose(forgot), { action: 'ask', problem: 'password' });
  const noUser = L.newDraft('n');
  noUser.baseUrl = 'https://a.example';
  noUser.password = 'pw';
  assert.deepEqual(L.onClose(noUser), { action: 'ask', problem: 'user' });
  assert.ok(L.PROBLEM_TEXT.address && L.PROBLEM_TEXT.password && L.PROBLEM_TEXT.user);
});

ok('a draft goes to the app without what the app decides itself', () => {
  const d = L.draftOf(home);
  d.password = 'pw';
  assert.deepEqual(L.draftMessage(d), {
    id: 'home', isNew: false, baseUrl: 'https://agents.example.com', certificateAlias: 'ara-phone',
    username: 'reto', password: 'pw', forgetPassword: false, healthUpload: true,
  });
  assert.equal(L.endpointSummary(home), 'Certificate: ara-phone · Sign-in: reto · Health data: on');
  assert.equal(L.endpointSummary({ healthUpload: false }), 'Certificate: none · Sign-in: none · Health data: off');
});

ok('an import says how it ended, and errors read as errors', () => {
  // The keys the app sends (CertificateImport.Outcome), and the two the chooser's answer makes.
  for (const key of ['installed', 'cancelled', 'not-pkcs12', 'too-large', 'unreadable', 'unavailable', 'blocked', 'no-picker', 'selected', 'unselected']) {
    const note = L.importNote(key);
    assert.ok(note && note.text, key);
  }
  for (const quiet of ['none', null, undefined, 42, 'toString', 'unknown']) assert.equal(L.importNote(quiet), null, String(quiet));
  assert.equal(L.importNote('installed').error, false);
  assert.equal(L.importNote('cancelled').error, false, 'leaving the installer is no error');
  assert.equal(L.importNote('not-pkcs12').error, true);
  assert.equal(L.importNote('blocked').error, true);
  assert.match(L.importNote('not-pkcs12').text, /\.p12 or \.pfx/);
  assert.match(L.importNote('blocked').text, /turned off on this device/);
  assert.match(L.importNote('unavailable').text, /no certificate installer/);
});

ok("after an install, the chooser's answer decides what the note says", () => {
  assert.equal(L.importChosen('installed', 'reto-quest'), 'selected');
  assert.equal(L.importChosen('installed', null), 'unselected');
  // Select certificate on its own, or after an import that ended otherwise: nothing changes.
  assert.equal(L.importChosen(null, 'ara-phone'), null);
  assert.equal(L.importChosen('cancelled', 'ara-phone'), 'cancelled');
  assert.match(L.importNote('unselected').text, /Select certificate/);
});

ok('per-device values read with the dashboard bounds and defaults', () => {
  assert.deepEqual(L.voiceSeconds({}), { silence: 1.5, noSpeech: 8 });
  assert.deepEqual(L.voiceSeconds({ 'retinue-voice-silence-ms': '99999', 'retinue-voice-nospeech-ms': 'x' }), { silence: 5, noSpeech: 8 });
  assert.equal(L.voiceValue(L.VOICE_SILENCE, '0.2'), '500');
  assert.equal(L.voiceValue(L.VOICE_NO_SPEECH, '12.4'), '12400');
  assert.equal(L.shelfSize({}), 5);
  assert.equal(L.shelfSize({ 'retinue.shelf.size': '80' }), 50);
  assert.equal(L.shelfSize({ 'retinue.shelf.size': '0' }), 5);
  assert.equal(L.clampShelf('7.4'), 7);
});

ok('health lines mirror the dashboard', () => {
  assert.equal(L.describeHealthSync(null), '');
  assert.match(L.describeHealthSync({ permissions: 'none' }), /not granted yet/);
  assert.equal(L.describeHealthSync({ permissions: 'granted', last_sync_at: null }), 'No update yet.');
  const done = L.describeHealthSync({ permissions: 'partial', last_sync_at: '2026-11-01T10:00:00Z', last_result: 'ok', last_count: 1 }, 'en');
  assert.match(done, /^Last update .+, 1 change\. Access is granted for some data types only\.$/);
  assert.match(L.describeHealthSync({ permissions: 'granted', last_sync_at: '2026-11-01T10:00:00Z', last_result: 'error', last_error: 'HTTP 401' }, 'en'), /failed: HTTP 401$/);
  assert.equal(L.describeHealthSyncEvent({ state: 'started' }), 'Updating health data…');
  assert.equal(L.describeHealthSyncEvent({ state: 'done', count: 3 }), 'Health data updated, 3 changes.');
  assert.equal(L.describeHealthSyncEvent({ state: 'skipped', reason: 'disabled' }), 'Health data update skipped: health data is not uploaded to this endpoint.');
  assert.equal(L.describeHealthSyncEvent({ state: 'other' }), '');
});

ok('direct calls say when Android has not allowed them, for the endpoint shown', () => {
  // `direct` is the current endpoint's own switch (SettingsPageState.callsInfo). Off for it,
  // the missing permission stands in nobody's way, whatever another endpoint has switched on.
  for (const quiet of [undefined, null, {}, { direct: false, permissionGranted: false }, { direct: true, permissionGranted: true }]) {
    assert.deepEqual(L.callNotes(quiet), []);
  }
  const notes = L.callNotes({ direct: true, permissionGranted: false, telephony: true });
  assert.equal(notes.length, 1);
  assert.match(notes[0].text, /calls open the dialer/);
  assert.equal(notes[0].action, 'calls.permission');
});

ok("the call switch is its card's: an endpoint's draft neither carries nor changes it", () => {
  // The list's rows have no call switch; one that slipped into a row would still not reach
  // the editor, so a save sends none and the app keeps the endpoint's own (EndpointDraft.toEndpoint).
  const row = { ...home, directCalls: true };
  const d = L.draftOf(row);
  assert.equal('directCalls' in d, false);
  assert.equal('directCalls' in L.newDraft('n'), false);
  d.healthUpload = false;
  assert.deepEqual(L.onClose(d), { action: 'save' });
  assert.deepEqual(Object.keys(L.draftMessage(d)).sort(), [
    'baseUrl', 'certificateAlias', 'forgetPassword', 'healthUpload', 'id', 'isNew', 'password', 'username',
  ]);
});

ok('the wake notes follow the mode, with the action that fixes each', () => {
  assert.deepEqual(L.wakeNotes({ mode: 'off', micGranted: false, modelsReady: false }), []);
  const visible = L.wakeNotes({ mode: 'visible', micGranted: false, modelsReady: false, notificationsAllowed: false });
  assert.equal(visible.length, 2);
  assert.ok(visible.every((n) => !n.action), 'nothing to open for these');
  const always = L.wakeNotes({ mode: 'always', micGranted: true, modelsReady: true, notificationsAllowed: false, fullScreenAllowed: false, overlayAllowed: false });
  assert.deepEqual(always.map((n) => n.action), ['wake.notifications', 'wake.fullScreen', 'wake.overlay']);
  assert.equal(L.wakeModelsLine({ melspectrogram: true, embedding: false, phrase: true }), 'Melspectrogram: present · Embedding: missing · Phrase: present');
});

ok('the launcher card says whether the tray sees apps opened elsewhere', () => {
  for (const off of [undefined, null, {}, { usageAccess: false }]) {
    const note = L.usageAccessNote(off);
    assert.equal(note.label, 'Allow usage access');
    assert.match(note.text, /apps you open from Ara/);
  }
  const on = L.usageAccessNote({ usageAccess: true });
  assert.equal(on.label, 'Usage access settings');
  assert.match(on.text, /however they were opened/);
});

ok("only the instance section's own message sizes its frame", () => {
  assert.equal(L.instanceHeight({ type: 'retinue-instance-settings', state: 'ready', height: 412.3 }), 413);
  assert.equal(L.instanceHeight({ type: 'retinue-instance-settings', height: 999999 }), 20000);
  for (const bad of [null, 'x', { type: 'other', height: 10 }, { type: 'retinue-instance-settings', height: -1 }, { type: 'retinue-instance-settings' }]) {
    assert.equal(L.instanceHeight(bad), null);
  }
});

console.log(`${passed} settings page checks passed`);
