// Gatekeeper's web panel. Everything shown comes from Gatekeeper as data and is put on the page as text, never as markup.
// R42: made for a phone first: the Systems page (every Gate System at a glance), chat with pictures and files, a bar of
// sections along the bottom on a narrow screen, and questions and messages that suit a touch screen.
'use strict';
let me = null, timer = null, current = '';
const $ = id => document.getElementById(id);
function el(tag, cls, text, kids) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined && text !== null) e.textContent = text;
  (kids || []).forEach(k => k && e.appendChild(k));
  return e;
}
const SVGNS = 'http://www.w3.org/2000/svg';
function icon(d, cls) { const s = document.createElementNS(SVGNS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('aria-hidden', 'true'); if (cls) s.setAttribute('class', cls); const p = document.createElementNS(SVGNS, 'path'); p.setAttribute('d', d); s.appendChild(p); return s; }
const ICONS = {
  systems: 'M4 5h16v10H4zM2 17h20v2H2zM8 8h3v4H8zm5 0h3v4h-3z',
  servers: 'M4 4h16v6H4zm0 10h16v6H4zM7 6.2a1 1 0 1 0 0 1.6 1 1 0 0 0 0-1.6zm0 10a1 1 0 1 0 0 1.6 1 1 0 0 0 0-1.6z',
  players: 'M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm7 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 20c0-3.3 3.1-6 7-6s7 2.7 7 6zm15.5 0c0-2-.8-3.8-2.1-5.1 3.2.3 5.6 2.4 5.6 5.1z',
  chat: 'M4 4h16a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H9l-5 4v-4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z',
  updates: 'M12 3a9 9 0 1 0 9 9h-2.5A6.5 6.5 0 1 1 12 5.5V9l5-4.5L12 0zM11 8h2v5l3.5 2-1 1.7L11 14z',
  keeper: 'M3 21V5h3v2h2V5h3v5h2V5h3v2h2V5h3v16h-7v-4a2 2 0 0 0-4 0v4z',
  launcher: 'M7 7h10a5 5 0 0 1 4.8 6.4l-1 3.3A2.5 2.5 0 0 1 16.5 18l-2.3-2.5h-4.4L7.5 18a2.5 2.5 0 0 1-4.3-1.3l-1-3.3A5 5 0 0 1 7 7zm0 3v1.5H5.5v1.5H7v1.5h1.5V13H10v-1.5H8.5V10zm8.5.5a1 1 0 1 0 0 2 1 1 0 0 0 0-2zm2 2a1 1 0 1 0 0 2 1 1 0 0 0 0-2z',
  battery: 'M3 7h15a1 1 0 0 1 1 1v2h2v4h-2v2a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1zm1 2v6h9V9z',
  share: 'M18 8a3 3 0 1 0-2.8-4l-7.4 4.1a3 3 0 1 0 0 3.8l7.4 4.1A3 3 0 1 0 16 14l-7.3-4.1a3 3 0 0 0 0-.8L16 5a3 3 0 0 0 2 .9z',
  file: 'M6 2h8l6 6v14H6zm8 1.5V9h5.5z',
  clock: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm1 5v5.4l4 2.3-1 1.7-5-2.9V7z'
};
function button(text, onclick, cls) { const b = el('button', cls || '', text); b.type = 'button'; b.addEventListener('click', onclick); return b; }
function can(p) { return me && (me.admin || me.perms.includes(p)); }
// (a message from Gatekeeper: in a line of its own where one is given, else a toast that goes by itself)
let toastTimer = null;
function say(r, where) {
  if (where) {
    where.className = 'said ' + (r && r.result === 'ok' ? 'green' : r && r.result ? 'red' : 'grey');
    where.textContent = r ? (r.message || (r.result === 'ok' ? 'Done.' : '')) : '';
    return;
  }
  const t = $('toast'), text = r ? (r.message || (r.result === 'ok' ? 'Done.' : '')) : '';
  clearTimeout(toastTimer);
  if (!text) { t.classList.remove('show'); return; }
  t.textContent = text; t.className = 'show' + (r.result === 'ok' ? ' ok' : r.result && r.result !== 'signin' ? ' bad' : '');
  toastTimer = setTimeout(() => t.classList.remove('show'), r.result ? 4200 : 9000);
}
async function ask(method, call, body, server) {
  const opt = { method, headers: {}, credentials: 'same-origin' };
  if (method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.headers['X-Panel-Token'] = me ? me.csrf : ''; opt.body = JSON.stringify(body || {}); }
  let res;
  try { res = await fetch('/panel/' + call + (server ? '?server=' + encodeURIComponent(server) : ''), opt); }
  catch (e) { $('live').classList.add('hidden'); return { result: 'failed', message: "Gatekeeper didn't answer. Is this device still on Tailscale?" }; }
  $('live').classList.remove('hidden');
  if (res.status === 401 && call !== 'login') { showSignin(); return { result: 'signin', message: 'Sign in again.' }; }
  try { return await res.json(); } catch (e) { return { result: 'failed', message: 'Gatekeeper answered with something unexpected.' }; }
}
const api = (method, call, body, server) => ask(method, 'api/' + call, body, server);
// A question that needs an answer, as a sheet from the bottom on a phone (a box in the middle on a wide screen).
function confirmSheet(text, ok, danger) {
  return new Promise(done => {
    const d = $('sheet');
    if (!d.showModal) { done(window.confirm(text)); return; }
    $('sheet-text').textContent = text; $('sheet-ok').textContent = ok || 'OK'; $('sheet-ok').className = (danger ? 'danger' : 'primary') + ' big';
    d.returnValue = '';
    d.addEventListener('close', () => done(d.returnValue === 'yes'), { once: true });
    d.showModal();
  });
}
async function act(call, body, server, confirmText, okWord) {
  if (confirmText && !(await confirmSheet(confirmText, okWord || 'Yes', /stop|close|kick|forget|sleep|restart|take off/i.test(confirmText)))) return;
  say({ message: 'Asking Gatekeeper…' });
  say(await api('POST', call, body, server));
  if (views[current]) views[current].draw();
}
function showSignin() {
  me = null; clearInterval(timer); clearInterval(watch);
  ['strip', 'tabbar', 'more', 'app', 'signout'].forEach(k => $(k).classList.add('hidden')); drawer(false);
  $('nav').replaceChildren(); $('tabbar').replaceChildren(); $('whoami').textContent = '';
  $('signin').classList.remove('hidden'); $('user').focus();
}
// A server card: a square of art with the name's initials, the name, how it is (with a dot), a line under it, and its buttons.
function card(name, stateClass, state, sub, buttons) {
  let h = 0; for (const ch of name) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  const initials = (name.match(/[\p{L}\p{N}]+/gu) || ['?']).slice(0, 2).map(w => w[0].toUpperCase()).join('');
  return el('div', 'card server', null, [el('div', 'art h' + (h % 8), initials), el('h3', '', name), el('div', '', null, [el('div', stateClass, state), el('div', 'note small', sub)]), buttons]);
}
// A table that becomes a list of cards on a phone: each cell knows its column's name.
function table(heads, rows) {
  const t = el('table', 'cards');
  t.appendChild(el('tr', '', null, heads.map(h => el('th', '', h))));
  rows.forEach(r => {
    const tr = el('tr');
    r.forEach((c, i) => { const td = c instanceof Node ? el('td', '', null, [c]) : el('td', '', c === undefined || c === null ? '' : String(c)); if (heads[i]) td.dataset.label = heads[i]; tr.appendChild(td); });
    t.appendChild(tr);
  });
  return t;
}
function fact(text, cls, ic) { const f = el('span', 'fact' + (cls ? ' ' + cls : ''), null, ic ? [icon(ic)] : []); f.appendChild(document.createTextNode(text)); return f; }
function pingFact(ms) {
  const lit = ms <= 0 ? 0 : ms < 60 ? 4 : ms < 150 ? 3 : ms < 400 ? 2 : 1, b = el('span', 'bars');
  for (let i = 0; i < 4; i++) b.appendChild(el('i', i < lit ? 'lit' : ''));
  const f = el('span', 'fact ' + (lit >= 3 ? 'good' : lit === 2 ? 'warn' : 'bad'), null, [b]); f.appendChild(document.createTextNode(ms + ' ms')); return f;
}
function phaseWord(s) { return s.running ? 'On · ' + s.players + ' playing' : s.phase === 'starting' ? 'Starting…' : s.phase === 'stopping' ? 'Shutting down…' : 'Off'; }
function phaseClass(s) { return s.running ? 'green' : (s.phase === 'starting' || s.phase === 'stopping') ? 'amber' : 'grey'; }
const modeWord = m => m === 'auto' ? 'Auto' : m === 'constellation' ? 'Constellation' : m === 'normal' ? 'Normal' : '';
// (redrawn only while nothing is being typed in it: a tapped button doesn't hold the page still)
// (and not at all when nothing in it changed, so a page asked again every few seconds doesn't flicker)
const stable = (where, v) => { const a = document.activeElement; if (a && where.contains(a) && /^(INPUT|SELECT|TEXTAREA)$/.test(a.tagName)) return false; if (where.children.length === 1 && where.firstChild.outerHTML === v.outerHTML) return true; where.replaceChildren(v); return true; };

const views = {
  systems: { name: 'Systems', section: 'SYSTEMS', needs: 'updates', every: 5000, filter: 'all', async draw() {
    const r = await api('GET', 'glance');
    if (current !== 'systems' || !r.gatekeeper) { if (r.message && current === 'systems') say(r); return; }
    const g = r.gatekeeper, v = el('div');
    newestSeen = g.newest && g.version && older(g.version, g.newest) ? g.newest : (g.version || g.newest || '');
    // this machine: its Gatekeeper (and its own Gatehouse, when it has one), and what waits on its owner
    const hero = el('div', 'card hero', null, [el('div', 'top', null, [el('div', 'glyph', null, [icon(ICONS.keeper)]),
      el('div', 'grow', null, [el('div', 'name', null, [el('h3', '', g.name), el('span', 'tag', 'This machine')]), halvesLine(true, !!g.gatehouse), el('div', 'note small', g.system + (modeWord(g.mode) ? ' · ' + modeWord(g.mode) + ' updates' : ''))])])]);
    hero.appendChild(halfLine('Gatekeeper', { now: 'Online', version: g.version }));
    if (g.gatehouse) hero.appendChild(halfLine('Gatehouse', g.gatehouse));
    const f = el('div', 'facts');
    f.appendChild(g.update_ready ? fact(g.newest + ' is out', 'warn', ICONS.updates) : fact('Up to date', 'good'));
    if (g.requests) { const q = fact(g.requests + (g.requests === 1 ? ' request' : ' requests') + ' waiting', 'bad'); if (can('access')) { q.classList.add('tap'); q.addEventListener('click', () => show('access')); } f.appendChild(q); }
    if (g.battery) f.appendChild(batteryFact(g.battery));
    if (g.hands_out) f.appendChild(fact(g.hands_out, 'info', ICONS.share));
    hero.appendChild(f);
    (g.servers || []).forEach(s => hero.appendChild(el('div', 'serverline', null, [el('span', phaseClass(s) + ' state', s.name), el('span', 'who', s.running ? (s.players.length ? s.players.join(', ') : 'Nobody playing') : phaseWord(s))])));
    v.appendChild(hero);
    // every other machine of the constellation, as cards; the filter chips count them
    const all = (r.machines || []).slice().sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
    const inGame = all.filter(m => m.gatehouse && m.gatehouse.now === 'In game'), up = all.filter(m => !offline(m));
    const st = views.systems, chips = el('div', 'chips');
    [['all', 'All', all.length], ['up', 'Online', up.length], ['game', 'In game', inGame.length]].forEach(([k, label, n]) => {
      const b = button(label + '  ' + n, () => { st.filter = k; st.draw(); }, st.filter === k ? 'on' : ''); chips.appendChild(b);
    });
    v.appendChild(chips);
    const list = st.filter === 'game' ? inGame : st.filter === 'up' ? up : all, grid = el('div', 'grid');
    list.forEach(m => grid.appendChild(machineCard(m)));
    v.appendChild(list.length ? grid : el('div', 'card empty', st.filter === 'all' ? 'No other machines have connected yet.' : 'None right now.'));
    v.appendChild(el('p', 'note small center', 'One card for each machine, with the halves of Gate System it has: Gatehouse (to play), Gatekeeper (to host), or both. Asked again every few seconds. Ping is how long its last word to this Gatekeeper took; laptops and Steam Decks tell their battery.'));
    stable($('view'), v);
  } },
  servers: { name: 'Servers', section: 'SERVERS', needs: 'view', every: 4000, async draw() {
    const o = await api('GET', 'overview');
    if (current !== 'servers' || !o.servers) return;
    const v = el('div', 'grid');
    if (o.host === 'mcss') {
      const up = o.mcss, line = el('div', 'buttons row'), mc = card('MC Server Soft', (up ? 'green' : 'grey') + ' state', o.mcss_phase === 'starting' ? 'Starting…' : o.mcss_phase === 'closing' ? 'Closing…' : up ? 'Running' : 'Not running', 'The program that runs the servers', line);
      if (can('control')) line.appendChild(up ? button('Close MCSS', () => act('mcss/close', {}, '', 'Close MC Server Soft on the server machine?', 'Close')) : button('Start MCSS', () => act('mcss/start', {})));
      v.appendChild(mc);
    }
    o.servers.forEach(s => {
      const row = el('div', 'buttons row'), one = card(s.name, phaseClass(s) + ' state', phaseWord(s), 'Port ' + s.port + (o.host === 'mcss' ? (s.native ? ' · run by Gatekeeper' : ' · run by MCSS') : ''), row);
      const up = s.running || s.phase === 'starting';
      if (can('control')) {
        row.appendChild(up ? button('Stop', () => act('server/stop', {}, s.id, 'Stop ' + s.name + '? Anyone playing is disconnected (the world is saved first).', 'Stop')) : button('Start', () => act('server/start', {}, s.id), 'primary'));
        if (s.native) { const r = button('Restart', () => act('tools/action', { action: 'restart' }, s.id, 'Restart ' + s.name + '? Anyone playing is disconnected until it is back.', 'Restart')); r.disabled = !s.running; row.appendChild(r); }
      }
      if (s.native) row.appendChild(button('Open', () => openServer(s.id, s.name), 'quiet'));
      if (!row.children.length) row.appendChild(el('span', 'note small', 'Your account may look, not start or stop.'));
      v.appendChild(one);
    });
    const wrap = el('div', '', null, [v]);
    if (!o.servers.length) wrap.appendChild(el('div', 'card empty', 'This Gatekeeper looks after no servers yet.'));
    if (o.servers.some(s => !s.native)) wrap.appendChild(el('p', 'note small', 'Consoles, settings, backups and tasks for the servers MC Server Soft runs are kept by MCSS.'));
    stable($('view'), wrap);
  } },
  players: { name: 'Players', section: 'PLAYERS', needs: 'view', every: 8000, async draw() {
    const r = await api('GET', 'players');
    if (current !== 'players' || !r.players) return;
    const names = Object.keys(r.players).sort((a, b) => (r.players[a].now === 'In game' ? 0 : 1) - (r.players[b].now === 'In game' ? 0 : 1) || a.localeCompare(b));
    const rows = names.map(n => {
      const p = r.players[n], cell = el('div', 'row');
      if (can('access')) cell.appendChild(button(p.whitelisted ? 'Take off whitelist' : 'Whitelist', () => act('whitelist', { player: n, value: !p.whitelisted }, '', p.whitelisted ? 'Take ' + n + ' off the whitelist?' : null, 'Take off'), 'quiet small'));
      if (can('console') && p.now === 'In game') cell.appendChild(button('Kick', () => act('kick', { player: n, reason: 'Kicked from the web panel' }, '', 'Kick ' + n + ' off the server now?', 'Kick'), 'quiet small'));
      return [n + (p.ban ? '  (' + p.ban + ')' : ''), el('span', (p.now === 'In game' ? 'green' : p.now === 'In the launcher' ? 'blue' : 'grey') + ' state', p.now), p.whitelisted ? 'Yes' : 'No', cell];
    });
    const v = el('div', 'card sheet scroll', null, [table(['Player', 'Now', 'Whitelisted', ''], rows)]);
    if (!names.length) v.appendChild(el('div', 'empty', 'Nobody yet.'));
    if (can('access')) {
      if (document.activeElement && document.activeElement.id === 'wl-name') return;
      const f = el('input', 'grow'); f.id = 'wl-name'; f.placeholder = 'Minecraft name'; f.autocapitalize = 'none';
      v.appendChild(el('div', 'row', null, [f, button('Add to whitelist', () => { const n = f.value.trim(); if (n) act('whitelist', { player: n, value: true }); }, 'primary')]));
    }
    stable($('view'), v);
  } },
  chat: { name: 'Chat', section: 'CHAT', needs: 'access', every: 2500, async draw() { await drawChat(); } },
  access: { name: 'Launchers', section: 'PLAYERS', needs: 'access', every: 6000, async draw() {
    const r = await api('GET', 'devices');
    if (current !== 'access' || !r.devices) return;
    const v = el('div');
    (r.requests || []).forEach(q => v.appendChild(el('div', 'card row', null, [el('div', 'grow', null, [el('div', 'fact bad', 'Request'), el('h3', '', q.who + (q.player ? ' (' + q.player + ')' : '')), el('div', 'note', 'asks for ' + q.type.replace('perm:', '') + (q.server ? ' on ' + q.server : ''))]),
      button('Allow', () => act('request/answer', { id: q.id, type: q.type, grant: true }, q.server_id), 'primary'), button('Deny', () => act('request/answer', { id: q.id, type: q.type, grant: false }, q.server_id), 'quiet')])));
    const rows = Object.keys(r.devices).map(id => {
      const d = r.devices[id], row = [d.name || id, d.player || '', el('span', (d.now === 'In game' ? 'green' : d.now === 'In the launcher' ? 'blue' : 'grey') + ' state', d.now || 'Offline')];
      [['server', d.state_server], ['mcss', d.state_mcss], ['sleep', d.state_machine], ['admin', d.state_admin]].forEach(([perm, st]) => {
        const on = st === 'allowed';
        row.push(button(on ? 'Allowed' : st === 'denied' ? 'Denied' : 'No', () => act('perm', { id, perm, state: on ? 'none' : 'allowed' }), (on ? 'ok' : st === 'denied' ? 'no' : 'quiet') + ' small'));
      });
      row.push(button('Forget', () => act('forget', { id }, '', 'Forget this launcher? It can ask again later.', 'Forget'), 'quiet small'));
      return row;
    });
    v.appendChild(el('div', 'card sheet scroll', null, [table(['Launcher', 'Player', 'Now', 'Server', 'MCSS', 'Wake/sleep', 'Gatekeeper', ''], rows)]));
    v.appendChild(el('p', 'note small', 'Tap a permission to allow it or take it away. Blacklisting, suspending and per-player permissions are in Gatekeeper\'s own window.'));
    stable($('view'), v);
  } },
  machine: { name: 'The machine', section: 'MENU', needs: 'machine', async draw() {
    const r = await api('GET', 'wake');
    if (current !== 'machine') return;
    const v = el('div');
    v.appendChild(el('div', 'card row', null, [el('div', 'grow', null, [el('h3', '', 'This machine'), el('div', 'note', me.gatekeeper + ' · ' + me.system + '. Put it to sleep with its servers stopped. This page can\'t wake it again: it runs on that machine.')]),
      button('Sleep', () => act('sleep', {}, '', 'Put the server machine to sleep? This panel stops answering until it is woken.', 'Sleep'), 'danger')]));
    v.appendChild(el('h2', '', 'Wake another device'));
    (r.wake_devices || []).forEach(d => v.appendChild(el('div', 'card row', null, [el('div', 'grow', null, [el('h3', '', d.name), el('div', 'grey small', d.mac)]), button('Wake', () => act('wake', { send: d.name }), 'primary')])));
    if (!(r.wake_devices || []).length) v.appendChild(el('div', 'card empty', 'None yet.'));
    v.appendChild(el('p', 'note small', 'Devices are added in Gatekeeper\'s window (Web panel). The wake signal is sent by the server machine on its own network, so it reaches devices in the same home that are set to wake on one.'));
    $('view').replaceChildren(v);
  } },
  constellation: { name: 'Constellation', section: 'MENU', needs: 'updates', every: 5000, async draw() {
    const r = await api('GET', 'constellation');
    if (current !== 'constellation' || !r.gatekeeper) { if (r.message) say(r); return; }
    const g = r.gatekeeper;
    const rows = [[g.name + '  (this Gatekeeper)', 'Gatekeeper', '', g.system, g.version, modeWord(g.mode) || 'Not said yet', el('span', 'green state', 'Running'), 'Leads it']];
    (r.clients || []).forEach(c => {
      const also = (c.also || []).length ? '; also ' + c.also.join(', ') : '';
      const inIt = c.membership === 'asked' ? 'Asked to join: not allowed in yet' : c.membership === 'member' ? 'Member (a Gatekeeper that joined)' : (c.via ? 'Yes, through ' + c.via : 'Yes') + also;
      rows.push([(c.via ? '↳ ' : '') + c.name + (c.here ? '  (this machine)' : ''), c.type, c.player, c.system, c.version || 'Not said', modeWord(c.mode) || 'Not said yet', el('span', (c.open ? 'green' : 'grey') + ' state', (c.open ? 'Open' : 'Closed') + (c.hands_out ? ' · ' + c.hands_out : '')), inIt]);
    });
    const v = el('div', '', null, [el('p', 'note', 'A constellation is a Gatekeeper and every client connected to it: its launchers, other Gatekeepers that have joined it, and their launchers.'),
      el('div', 'card sheet scroll', null, [table(['Client', 'Kind', 'Player', 'System', 'Version', 'Update mode', 'Now', 'In this constellation'], rows)]),
      el('p', '', 'This Gatekeeper: ' + (g.status || '')), el('p', 'note small', 'Allowing a Gatekeeper in, removing one, and joining another constellation are done in Gatekeeper\'s own window.')]);
    stable($('view'), v);
  } },
  updates: { name: 'Update log', section: 'UPDATES', needs: 'updates', every: 15000, async draw() {
    const r = await api('GET', 'updates');
    if (current !== 'updates' || !r.entries) { if (r.message) say(r); return; }
    const rows = r.entries.map(e => [e.client + ': ' + e.from + ' → ' + e.to, e.when, e.kind, e.player, e.system + (e.info ? ' (' + e.info + ')' : ''), e.how, e.source || 'not said', e.via || (e.also ? '(also heard through ' + e.also + ')' : '')]);
    const v = el('div', 'card sheet scroll', null, [table(['Update', 'When', 'Kind', 'Player', 'System', 'How', 'Fetched from', 'Through'], rows)]);
    if (!r.entries.length) v.appendChild(el('div', 'empty', 'No updates recorded yet. A line is added each time a client of this constellation goes from one version to another.'));
    v.appendChild(el('p', 'note small', r.total + ' recorded in all; the newest ' + r.entries.length + ' are shown.'));
    stable($('view'), v);
  } },
  log: { name: 'Activity log', section: 'MENU', needs: 'view', every: 8000, async draw() {
    const r = await api('GET', 'log');
    if (current !== 'log' || !r.log) return;
    const pre = el('pre', 'console', r.log.join('\n'));
    $('view').replaceChildren(pre); pre.scrollTop = pre.scrollHeight;
  } },
  gatekeeper: { name: 'Options', section: 'MENU', needs: 'admin', async draw() {
    const u = await api('GET', 'update');
    if (current !== 'gatekeeper') return;
    const v = el('div');
    const line = el('div', 'card row', null, [el('div', 'grow', null, [el('h3', '', 'Gatekeeper ' + (u.own || me.version)), el('div', u.outdated ? 'amber' : 'grey', u.outdated ? u.newest + (u.blocking === false ? ' is out. This version still works with it: nothing is held up.' : ' is out. Until it is updated Gatekeeper starts no server.') : 'Up to date.')])]);
    if (u.outdated) line.appendChild(button('Update Gatekeeper', () => act('update', {}, '', 'Update Gatekeeper now? It restarts itself; this page answers again in under a minute.', 'Update'), 'primary'));
    v.appendChild(line);
    if (me.host === 'builtin') {
      const n = await api('GET', 'tools/new');
      const name = el('input'), kind = el('select'), ver = el('select'), mem = el('input'), eula = el('input'), said = el('p', 'said');
      name.placeholder = 'Name'; mem.value = '4G'; eula.type = 'checkbox';
      (n.kinds || []).filter(k => k.id !== 'forge' && k.id !== 'file').forEach(k => { const o = el('option', '', k.name); o.value = k.id; kind.appendChild(o); });
      const fill = async () => { ver.replaceChildren(); const r = await api('POST', 'tools/versions', { kind: kind.value }); (r.versions || []).forEach(x => ver.appendChild(el('option', '', x))); if (r.message) say(r, said); };
      kind.addEventListener('change', fill); fill();
      const make = button('Make the server', async () => {
        say(await api('POST', 'tools/new', { name: name.value, kind: kind.value, version: ver.value, memory: mem.value, eula: eula.checked }), said);
        const poll = setInterval(async () => { const m = (await api('GET', 'tools/new')).making || {}; if (m.state === 'working') say({ message: m.step }, said); else { clearInterval(poll); say({ result: m.state === 'done' ? 'ok' : 'failed', message: m.message }, said); } }, 1000);
      }, 'primary');
      const link = el('a', '', "Minecraft's EULA"); link.href = 'https://aka.ms/MinecraftEULA'; link.target = '_blank'; link.rel = 'noopener noreferrer';
      v.appendChild(el('div', 'card', null, [el('h3', '', 'New server'), el('div', 'row', null, [name, kind, ver, mem]),
        el('label', 'row', null, [eula, el('span', '', 'I have read '), link, el('span', '', ' and I agree to it')]), el('div', 'row', null, [make]), said,
        el('p', 'note small', 'Gatekeeper fetches the server\'s program from its makers and sets it up on the server machine. A server from a Forge installer or your own jar is made in Gatekeeper\'s own window.')]));
    }
    $('view').replaceChildren(v);
  } }
};
// (in game first, then online, then offline)
// (the newest version this Gatekeeper knows of: a half older than it is marked "update ready")
let newestSeen = '';
const older = (a, b) => { const x = a.split('.').map(n => parseInt(n, 10) || 0), y = b.split('.').map(n => parseInt(n, 10) || 0); for (let i = 0; i < Math.max(x.length, y.length); i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) < (y[i] || 0); } return false; };
const offline = m => (!m.gatehouse || m.gatehouse.now === 'Offline') && (!m.gatekeeper || m.gatekeeper.now === 'Offline');
function rank(m) { return m.gatehouse && m.gatehouse.now === 'In game' ? 0 : offline(m) ? 2 : 1; }
// Which halves a machine has, as badges: Gatehouse, Gatekeeper, or both.
function halvesLine(keeper, house) {
  const l = el('div', 'halves');
  if (house) l.appendChild(el('span', 'half house', null, [icon(ICONS.launcher), el('span', '', 'Gatehouse')]));
  if (keeper) l.appendChild(el('span', 'half keeper', null, [icon(ICONS.keeper), el('span', '', 'Gatekeeper')]));
  return l;
}
// One half's line: where it is now (in game on which server, in the launcher, online, offline), its version and update mode.
function halfLine(which, h) {
  const where = h.now || 'Offline', cls = where === 'In game' ? 'green' : where === 'Offline' ? 'grey' : 'blue';
  const line = el('div', 'halfline', null, [el('span', 'which', which), el('span', cls + ' state', where + (h.server ? ' on ' + h.server : ''))]);
  const behind = h.version && newestSeen && older(h.version, newestSeen);
  const meta = [h.version ? 'v' + h.version + (behind ? ', update ready' : '') : '', modeWord(h.mode), h.player].filter(Boolean).join(' · ');
  if (meta) line.appendChild(el('span', (behind ? 'amber' : 'dim') + ' small', meta));
  return line;
}
function batteryFact(b) { const pct = parseInt(b, 10); return fact(b, /charging|full|plugged/.test(b) ? 'good' : pct <= 20 ? 'bad' : pct <= 40 ? 'warn' : '', ICONS.battery); }
function machineCard(m) {
  const house = m.gatehouse || null, keeper = m.gatekeeper || null;
  const cls = house && house.now === 'In game' ? 'ingame' : offline(m) ? 'off' : 'open';
  const c = el('div', 'card sys ' + cls);
  const name = el('div', 'name', m.name);
  if (m.via) name.appendChild(el('span', 'tag', 'via ' + m.via));
  if (m.membership === 'asked') name.appendChild(el('span', 'tag', 'Asked to join'));
  c.appendChild(el('div', 'glyph', null, [icon(keeper ? ICONS.keeper : ICONS.launcher)]));
  c.appendChild(el('div', '', null, [name, halvesLine(!!keeper, !!house), el('div', 'sub', m.system || '')]));
  const lines = el('div', 'lines');
  if (keeper) lines.appendChild(halfLine('Gatekeeper', keeper));
  if (house) lines.appendChild(halfLine('Gatehouse', house));
  c.appendChild(lines);
  const f = el('div', 'facts'), ping = house && house.ping > 0 ? house.ping : 0;
  if (ping) f.appendChild(pingFact(ping));
  if (m.battery) f.appendChild(batteryFact(m.battery));
  if (m.hands_out) f.appendChild(fact(m.hands_out, 'info', ICONS.share));
  if (offline(m) && m.last_seen) f.appendChild(fact('Last seen ' + m.last_seen, '', ICONS.clock));
  if (f.children.length) c.appendChild(f);
  return c;
}

// ---- chat: rooms as chips with a count of new messages, messages as bubbles, pictures shown, other files to save
const seenKey = room => 'gk-chat-seen-' + room;
function seen(room) { try { return +(localStorage.getItem(seenKey(room)) || 0); } catch (e) { return 0; } }
function markSeen(room, n) { try { localStorage.setItem(seenKey(room), String(n)); } catch (e) { /* (a private window: counts start again) */ } }
const IMAGE = /\.(png|jpe?g|gif|webp)$/i;
async function drawChat() {
  const st = views.chat.st || (views.chat.st = { room: '', next: 0, rooms: '' });
  const r = await api('POST', 'chat', { room: st.room, after: st.room ? st.next : 0 });
  if (current !== 'chat' || !r.rooms) { if (r.message) say(r); return; }
  if (!st.room && r.rooms.length) { st.room = r.rooms[0].id; st.next = 0; return drawChat(); }
  const sig = r.rooms.map(x => x.id + ':' + x.last).join('|') + '#' + st.room;
  if (!$('chat-log') || st.rooms.split('#')[1] !== st.room) {
    const chips = el('div', 'chips'); chips.id = 'chat-rooms';
    const log = el('div', 'log'); log.id = 'chat-log';
    const line = el('input'); line.id = 'chat-line'; line.placeholder = 'Message as Host'; line.maxLength = 500; line.enterKeyHint = 'send';
    const send = async () => { const t = line.value.trim(); if (!t) return; line.value = ''; const a = await api('POST', 'chat', { room: st.room, after: st.next, text: t }); if (a.message) say({ result: 'refused', message: a.message }); drawChat(); };
    line.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
    $('view').replaceChildren(chips, el('div', 'card chat', null, [log, el('div', 'composer', null, [line, button('Send', send, 'primary')])]),
      el('p', 'note small', 'A server\'s room is for the launchers let onto that server; this Gatekeeper\'s is for everyone in any of them. Emptying a room is done in Gatekeeper\'s own window.'));
    st.next = 0; st.rooms = '';
  }
  if (st.rooms !== sig) {
    st.rooms = sig;
    const chips = $('chat-rooms'); chips.replaceChildren();
    r.rooms.forEach(x => {
      const unread = x.id === st.room ? 0 : Math.max(0, x.last - seen(x.id));
      const b = button(x.name, () => { if (st.room === x.id) return; st.room = x.id; st.next = 0; $('chat-log').replaceChildren(); st.rooms = ''; drawChat(); }, x.id === st.room ? 'on' : '');
      if (unread) b.appendChild(el('span', 'badge', unread > 99 ? '99+' : String(unread)));
      chips.appendChild(b);
    });
  }
  const log = $('chat-log');
  if (r.messages && r.messages.length) {
    const atEnd = log.scrollHeight - log.scrollTop - log.clientHeight < 80;
    log.querySelectorAll('.empty').forEach(x => x.remove());
    r.messages.forEach(m => log.appendChild(bubble(m)));
    if (atEnd || st.next === 0) log.scrollTop = log.scrollHeight;
  } else if (st.next === 0 && !log.children.length) log.appendChild(el('div', 'empty', 'No messages yet. Say hello.'));
  if (st.room) { st.next = r.next || 0; markSeen(st.room, st.next); }
  chatBadge(r.rooms);
}
function bubble(m) {
  const mine = m.who === 'Host', b = el('div', 'bubble', m.file ? null : m.text);
  if (m.file) {
    const f = m.file, url = '/panel/file?id=' + encodeURIComponent(f.id || '');
    b.appendChild(el('div', '', mine ? 'Offered a file' : 'Offers a file'));
    if (IMAGE.test(f.name || '')) {
      const img = el('img', 'shot'); img.src = url; img.alt = f.name; img.loading = 'lazy';
      img.addEventListener('click', () => { const d = $('viewer'); $('viewer-img').src = url; $('viewer-img').alt = f.name; if (d.showModal) d.showModal(); });
      b.appendChild(img);
    } else {
      const a = el('a', 'filechip', null, [icon(ICONS.file)]); a.href = url; a.download = f.name || 'file';
      a.appendChild(document.createTextNode((f.name || 'file') + (f.size ? ' · ' + size(f.size) : ''))); b.appendChild(a);
    }
  }
  return el('div', 'msg' + (mine ? ' mine' : ''), null, [el('div', 'meta', (mine ? 'You (Host)' : m.who) + ' · ' + m.when), b]);
}
const size = n => n > 1048576 ? (n / 1048576).toFixed(1) + ' MB' : n > 1024 ? Math.round(n / 1024) + ' KB' : n + ' B';
function chatBadge(rooms) {
  const st = views.chat.st || {}, n = (rooms || []).reduce((t, x) => t + (current === 'chat' && x.id === st.room ? 0 : Math.max(0, x.last - seen(x.id))), 0);
  document.querySelectorAll('[data-section=CHAT]').forEach(b => { const old = b.querySelector('.badge'); if (old) old.remove(); if (n) b.appendChild(el('span', 'badge', n > 99 ? '99+' : String(n))); });
}

// One server (where Gatekeeper runs it itself): its state, console, backups and tasks.
function openServer(id, name) {
  current = 'server'; clearInterval(timer);
  $('subnav').replaceChildren(); $('title').textContent = name;
  let next = 0;
  const stats = el('div', 'grey'), pre = el('pre', 'console'), line = el('input', 'grow'), extra = el('div');
  line.placeholder = 'Type a command (no slash needed)'; line.enterKeyHint = 'send'; line.autocapitalize = 'none';
  const send = async () => { const c = line.value.trim(); if (!c) return; line.value = ''; say(await api('POST', 'tools/command', { command: c }, id)); };
  line.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
  const v = el('div', '', null, [el('div', 'row', null, [button('‹ Servers', () => show('servers'), 'quiet')]), el('div', 'card', null, [stats])]);
  if (can('console')) { v.appendChild(pre); v.appendChild(el('div', 'row', null, [line, button('Send', send, 'primary')])); }
  v.appendChild(extra);
  $('view').replaceChildren(v);
  let drewExtra = false;
  const tick = async () => {
    if (current !== 'server') return;
    const s = await api('GET', 'tools/server', null, id);
    if (current !== 'server' || !s.id) { if (s.message) say(s); return; }
    stats.className = (s.phase === 'online' ? 'green' : s.running ? 'amber' : 'grey') + ' state';
    stats.textContent = (s.phase === 'online' ? 'On' : s.phase === 'starting' ? 'Starting…' : s.phase === 'stopping' ? 'Shutting down…' : 'Off') + (s.running ? ' · up ' + Math.floor(s.uptime / 60) + ' min · memory ' + s.memory_used + ' MB of ' + s.memory + ' · ' + s.players + ' playing' : '') + ' · port ' + s.port;
    if (can('manage') && !drewExtra) {
      drewExtra = true;
      const tasks = el('div', 'card', null, [el('h3', '', 'Backups and tasks'), el('div', 'note', s.backup_state || ((s.backups || []).length + ' backup(s) kept in ' + s.backup_dir)),
        el('div', 'row', null, [button('Back up now', () => act('tools/backup', {}, id), 'primary')].concat((s.tasks || []).map((t, i) => button('Run: ' + t.name, () => act('tools/task', { index: i }, id), 'quiet'))))]);
      extra.replaceChildren(tasks, el('p', 'note small', 'Settings, game settings and the list of tasks are changed in Gatekeeper\'s own window (Manage).'));
    }
    if (can('console')) {
      const c = await api('POST', 'tools/console', { after: next }, id);
      if (c.lines && c.next !== next) { if (c.next < next) pre.textContent += '\n---- started again ----\n'; pre.textContent += c.lines.join('\n') + (c.lines.length ? '\n' : ''); pre.scrollTop = pre.scrollHeight; next = c.next; }
    }
  };
  tick(); timer = setInterval(tick, 2500);
}

// (R38: the same sections as Gatekeeper's own window, and R42's Systems first; the pages about the machine and
// Gatekeeper itself are in the menu, the button with three lines at the top left, as there)
const SECTIONS = ['SYSTEMS', 'SERVERS', 'PLAYERS', 'CHAT', 'UPDATES'];
const SECTION_ICON = { SYSTEMS: ICONS.systems, SERVERS: ICONS.servers, PLAYERS: ICONS.players, CHAT: ICONS.chat, UPDATES: ICONS.updates };
function drawer(open) { $('drawer').classList.toggle('hidden', !open); $('scrim').classList.toggle('hidden', !open); $('more').setAttribute('aria-expanded', open ? 'true' : 'false'); }
$('more').addEventListener('click', e => { e.stopPropagation(); drawer($('drawer').classList.contains('hidden')); });
$('scrim').addEventListener('click', () => drawer(false));
document.addEventListener('keydown', e => { if (e.key === 'Escape') drawer(false); });
const titled = s => s[0] + s.slice(1).toLowerCase();
let mine = [], last = {}, watch = null;
const inSection = s => mine.filter(k => views[k].section === s);
function show(key) {
  current = key; clearInterval(timer); say(null);
  const sec = views[key].section, pages = inSection(sec);
  last[sec] = key;
  document.querySelectorAll('[data-section]').forEach(b => b.classList.toggle('on', b.dataset.section === sec));
  document.querySelectorAll('#drawer button').forEach(b => b.classList.toggle('on', b.dataset.page === key));
  const inMenu = sec === 'MENU';
  $('title').textContent = inMenu ? views[key].name : titled(sec); drawer(false);
  $('subnav').replaceChildren(...(!inMenu && pages.length > 1 ? pages.map(k => button(views[k].name, () => show(k), k === key ? 'on' : '')) : []));
  $('subnav').classList.toggle('hidden', !$('subnav').children.length);
  $('view').replaceChildren(el('div', 'card empty', 'Loading…'));
  $('view').classList.add('fresh'); setTimeout(() => $('view').classList.remove('fresh'), 900);   // (cards rise in when a page opens, not on every look)
  window.scrollTo(0, 0);
  views[key].draw();
  if (views[key].every) timer = setInterval(() => { if (!document.hidden) views[key].draw(); }, views[key].every);
}
function showSection(sec) { const pages = inSection(sec); if (pages.length) show(pages.includes(last[sec]) ? last[sec] : pages[0]); }
// What stands along the bottom, and the counts on Players and Chat: asked again every few seconds.
async function standing() {
  if (!me || document.hidden) return;
  if (can('access')) {
    const r = await api('GET', 'devices'), n = (r.requests || []).length;
    document.querySelectorAll('[data-section=PLAYERS]').forEach(b => { const old = b.querySelector('.badge'); if (old) old.remove(); if (n) b.appendChild(el('span', 'badge', String(n))); });
    if (current !== 'chat') { const c = await api('POST', 'chat', { room: '' }); if (c.rooms) chatBadge(c.rooms); }
  }
  if (can('updates')) {
    const a = await api('GET', 'auto'), pips = (a.gather && a.gather.pips) || [];
    $('pips').classList.toggle('hidden', !pips.length);
    $('pips').replaceChildren(...pips.map(p => { const d = el('span', 'pip ' + p.s + (p.me ? ' me' : '')); d.title = (p.me ? 'This Gatekeeper' : p.n || (p.k === 'gatekeeper' ? 'A Gatekeeper' : 'A launcher')) + ': ' + (p.s === 'ready' ? 'ready' : p.s === 'busy' ? 'a server it runs is still on' : "hasn't seen the update yet"); return d; }));
    const others = pips.filter(p => !p.me), wait = others.filter(p => p.s !== 'ready').length;
    $('pipline').textContent = !pips.length ? (a.status || '') : !others.length ? 'Nobody else is open in this constellation' : wait ? 'Waiting for ' + wait + ' of ' + others.length + ' other' + (others.length === 1 ? '' : 's') : 'All ' + others.length + ' other' + (others.length === 1 ? ' is' : 's are') + ' ready';
  }
}
function start(who) {
  me = who;
  $('signin').classList.add('hidden');
  ['app', 'signout', 'strip', 'tabbar'].forEach(k => $(k).classList.remove('hidden'));
  $('whoami').textContent = me.user + (me.admin ? ' (admin)' : '');
  $('standing').textContent = 'Gatekeeper ' + me.version + ' · ' + me.gatekeeper + ' · ' + me.system;
  mine = Object.keys(views).filter(k => views[k].needs === 'admin' ? me.admin : can(views[k].needs));
  const nav = $('nav'), bar = $('tabbar'); nav.replaceChildren(); bar.replaceChildren();
  SECTIONS.forEach(s => {
    const has = inSection(s).length > 0, go = () => { if (has) showSection(s); };
    const b = button(s, go, has ? '' : 'off'); b.dataset.section = s;
    const t = el('button', has ? '' : 'off', null, [icon(SECTION_ICON[s]), el('span', '', titled(s))]); t.type = 'button'; t.dataset.section = s; t.addEventListener('click', go);
    if (!has) { b.title = t.title = "Your account isn't allowed this section. Its permissions are set in Gatekeeper's window."; }
    if (s === 'SYSTEMS' && !has) return;   // (an account that can't see the constellation has no Systems page at all)
    nav.appendChild(b); bar.appendChild(t);
  });
  // the menu: its pages this account may see, and who is signed in
  const dr = $('drawer'); dr.replaceChildren(el('div', 'drawer-head', 'MENU'));
  inSection('MENU').forEach(k => { const b = button(views[k].name, () => show(k)); b.dataset.page = k; dr.appendChild(b); });
  dr.appendChild(el('div', 'drawer-foot', null, [el('span', '', 'Signed in as ' + me.user + (me.admin ? ' (admin)' : '')), el('span', 'dim', 'Gatekeeper ' + me.version + ' · ' + me.gatekeeper), button('Sign out', signOut, 'quiet')]));
  $('more').classList.remove('hidden');
  const first = SECTIONS.map(s => inSection(s)[0]).find(Boolean);
  if (first) show(first); else { $('title').textContent = 'Gatekeeper'; $('view').replaceChildren(el('div', 'card empty', 'Your account is allowed nothing yet. Its permissions are set in Gatekeeper\'s window.')); }
  clearInterval(watch); standing(); watch = setInterval(standing, 6000);
}
document.addEventListener('visibilitychange', () => { if (!document.hidden && me && views[current]) { views[current].draw(); standing(); } });
// Q and E flip the sections, as the bumpers do in Gatekeeper's own window (not while something is being typed).
document.addEventListener('keydown', e => {
  if (!me || e.ctrlKey || e.altKey || e.metaKey || /^(INPUT|SELECT|TEXTAREA)$/.test(document.activeElement.tagName)) return;
  const by = e.key === 'q' || e.key === 'Q' ? -1 : e.key === 'e' || e.key === 'E' ? 1 : 0;
  if (!by) return;
  const open = SECTIONS.filter(s => inSection(s).length), on = document.querySelector('#nav button.on'), i = on ? open.indexOf(on.dataset.section) : -1;
  if (open.length) showSection(open[(i + by + open.length) % open.length]);
});
$('signin-form').addEventListener('submit', async e => {
  e.preventDefault();
  const r = await ask('POST', 'login', { user: $('user').value, password: $('password').value });
  $('password').value = '';
  if (r.user) { say(null, $('signin-said')); start(r); } else say(r, $('signin-said'));
});
async function signOut() { await ask('POST', 'logout', {}); showSignin(); }
$('signout').addEventListener('click', signOut);
(async () => { const r = await ask('GET', 'me'); if (r.user) start(r); else showSignin(); })();
