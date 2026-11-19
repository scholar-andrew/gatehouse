// Gatekeeper's web panel. Everything shown comes from Gatekeeper as data and is put on the page as text, never as markup.
// R42: made for a phone first: the Systems page (every Gate System at a glance), chat with pictures and files, a bar of
// sections along the bottom on a narrow screen, and questions and messages that suit a touch screen.
'use strict';
let me = null, timer = null, current = '';
const $ = id => document.getElementById(id);
// R100: every time Gatekeeper sends is a unix time (milliseconds since 1970, UTC) in a field named "at" or ending "_at";
// this shows it in this device's own time: today's as just the time, older ones with the date. A record from before R100
// has only its text time (the Gatekeeper machine's clock), shown as it is.
function shownTime(at, text) {
  const ms = Number(at);
  if (!(ms > 0)) return text || '';
  const t = new Date(ms);
  return t.toLocaleString([], t.toDateString() === new Date().toDateString() ? { timeStyle: 'short' } : { dateStyle: 'medium', timeStyle: 'short' });
}
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
// R54: a link to another site is tried first (Gatekeeper tries it: a page can't), and one that isn't working says why,
// with Open anyway. The new tab is opened at once, while the press still counts, and pointed at the link once it answers.
document.addEventListener('click', async e => {
  const a = e.target.closest && e.target.closest('a[href^="https://"]');
  if (!a || !me || e.defaultPrevented || e.ctrlKey || e.metaKey || e.shiftKey) return;
  e.preventDefault();
  const url = a.href, tab = window.open('', '_blank');
  if (tab) { tab.opener = null; try { tab.document.title = 'Opening…'; tab.document.body.textContent = 'Checking the link…'; } catch (x) { /* (a browser that won't let it be written) */ } }
  let r = {};
  try { r = await api('POST', 'linkcheck', { url }); } catch (x) { r = {}; }
  if (r.ok !== false) { if (tab) tab.location.replace(url); else window.open(url, '_blank', 'noopener'); return; }
  if (tab) tab.close();
  if (await confirmSheet("This link isn't working right now. " + (r.why || ''), 'Open anyway')) window.open(url, '_blank', 'noopener');
});
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
    const hero = el('div', 'card hero', null, [el('div', 'top', null, [el('div', 'glyph', null, [castle(true, !!g.gatehouse)]),
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
      // (R34: each game's own word for its list of who may join; a game with a password has no list to change)
      const w = r.allow_word || 'whitelist', listed = r.allow_kind === 'names' || r.allow_kind === 'steam_ids' || !r.allow_kind;
      if (can('access') && listed) cell.appendChild(button(p.whitelisted ? 'Take off ' + (w === 'whitelist' ? 'whitelist' : 'the list') : w === 'whitelist' ? 'Whitelist' : 'Allow to join', () => act('whitelist', { player: n, value: !p.whitelisted }, '', p.whitelisted ? 'Take ' + n + ' off the ' + w + '?' : null, 'Take off'), 'quiet small'));
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
    const rows = r.entries.map(e => [e.client + ': ' + e.from + ' → ' + e.to, shownTime(e.at, e.when), e.kind, e.player, e.system + (e.info ? ' (' + e.info + ')' : ''), e.how, e.source || 'not said', e.via || (e.also ? '(also heard through ' + e.also + ')' : '')]);
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
    v.appendChild(await notifyCard());
    if (me.host === 'builtin') {
      const n = await api('GET', 'tools/new');
      const name = el('input'), game = el('select'), kind = el('select'), ver = el('select'), mem = el('input'), eula = el('input'), said = el('p', 'said');
      // (R34: the game is chosen first, and the rest shows once it is: nothing is Minecraft by default)
      const none = el('option', '', 'Choose a game'); none.value = ''; game.appendChild(none);
      (n.games || []).forEach(g => { const o = el('option', '', g.name); o.value = g.id; game.appendChild(o); });
      name.placeholder = 'Name'; mem.value = '4G'; eula.type = 'checkbox';
      (n.kinds || []).filter(k => k.id !== 'forge' && k.id !== 'file').forEach(k => { const o = el('option', '', k.name); o.value = k.id; kind.appendChild(o); });
      const fill = async () => { ver.replaceChildren(); const r = await api('POST', 'tools/versions', { kind: kind.value }); (r.versions || []).forEach(x => ver.appendChild(el('option', '', x))); if (r.message) say(r, said); };
      kind.addEventListener('change', fill); fill();
      const make = button('Make the server', async () => {
        const body = game.value === 'minecraft' ? { name: name.value, game: game.value, kind: kind.value, version: ver.value, memory: mem.value, eula: eula.checked }
          : { name: name.value, game: game.value, port: oPort.value, world: oWorld.value, password: oPass.value, eula: oTerms.checked };
        say(await api('POST', 'tools/new', body), said);
        const poll = setInterval(async () => { const m = (await api('GET', 'tools/new')).making || {}; if (m.state === 'working') say({ message: m.step }, said); else { clearInterval(poll); say({ result: m.state === 'done' ? 'ok' : 'failed', message: m.message }, said); } }, 1000);
      }, 'primary');
      const link = el('a', '', "Minecraft's EULA"); link.href = 'https://aka.ms/MinecraftEULA'; link.target = '_blank'; link.rel = 'noopener noreferrer';
      const mcRow = el('div', 'row', null, [kind, ver, mem]), mcEula = el('label', 'row', null, [eula, el('span', '', 'I have read '), link, el('span', '', ' and I agree to it')]);
      // (R34 stage 2: a game described by a file: port, world, password, its terms; SteamCMD installs it)
      const oPort = el('input'), oWorld = el('input'), oPass = el('input'), oTerms = el('input'), oLink = el('a', '', 'its terms'), oNote = el('p', 'note small');
      oPort.placeholder = 'Port'; oWorld.placeholder = 'World name'; oPass.placeholder = 'Password (5+ characters)'; oTerms.type = 'checkbox';
      oLink.target = '_blank'; oLink.rel = 'noopener noreferrer';
      const oRow = el('div', 'row', null, [oPort, oWorld, oPass]), oTermsRow = el('label', 'row', null, [oTerms, el('span', '', 'I have read '), oLink, el('span', '', ' and I agree to them')]);
      const info = () => (n.games || []).find(g => g.id === game.value) || {};
      const gameShown = () => {
        const g = info(), mc = game.value === 'minecraft', other = !!game.value && !mc;
        mcRow.hidden = mcEula.hidden = !mc; oRow.hidden = oNote.hidden = !other; oTermsRow.hidden = !other || !g.terms; oPass.hidden = !g.password;
        if (other && !oPort.value) oPort.value = g.port || '';
        if (g.terms) oLink.href = g.terms;
        oNote.textContent = other ? (g.steam ? 'Gatekeeper installs the server with SteamCMD, Valve\'s tool for game servers' + (g.steamcmd_here ? '.' : ', which it fetches from Valve first (a few MB). Nothing is fetched until you press Make the server.') : 'Gatekeeper can\'t install this game\'s server: put it in a folder of its own on the machine, then add it in Server setup there.') : '';
        make.disabled = !(mc || (other && g.steam));
      };
      game.addEventListener('change', gameShown); gameShown();
      v.appendChild(el('div', 'card', null, [el('h3', '', 'New server'), el('div', 'row', null, [name, game]), mcRow,
        mcEula, oRow, oTermsRow, oNote, el('div', 'row', null, [make]), said,
        el('p', 'note small', 'Gatekeeper fetches the server\'s program from its makers and sets it up on the server machine. A server from a Forge installer or your own jar is made in Gatekeeper\'s own window.')]));
    }
    $('view').replaceChildren(v);
  } }
};
// Phone notifications (ntfy), set from the panel as in Gatekeeper's Options: on or off, which kinds, the server, the
// topic (shown, copied, or opened straight in the ntfy app on this phone), a new topic, a test, and which servers are
// started again after a crash. Admin accounts only: the topic works like a password.
async function notifyCard() {
  const n = await api('GET', 'notify'), c = el('div', 'card notify');
  c.appendChild(el('h3', '', 'Phone notifications'));
  c.appendChild(el('p', 'note small', 'Through ntfy (free, no account): install the ntfy app on your phone, subscribe to the topic below, and press Send a test. Only names and a sentence are sent (for chat, who wrote and the start of the message). The topic works like a password: keep it to yourself.'));
  if (n.message && !('on' in n)) { c.appendChild(el('p', 'red', n.message)); return c; }
  const said = el('p', 'said');
  const save = async patch => { say({ message: 'Saving…' }, said); const r = await api('POST', 'notify', patch); say({ result: r.message ? 'refused' : 'ok', message: r.message || 'Saved.' }, said); };
  const sw = (label, key, on) => { const b = el('input'); b.type = 'checkbox'; b.checked = !!on; b.addEventListener('change', () => save({ [key]: b.checked })); return el('label', 'row switchrow', null, [b, el('span', '', label)]); };
  const main = sw('Send notifications to my phone', 'on', n.on);
  c.appendChild(main);
  const kinds = el('div', 'kinds', null, [sw('When a friend joins a server', 'join', n.join), sw('When a server goes down', 'down', n.down),
    sw('When an update needs me (or Gatekeeper restarted itself)', 'update', n.update), sw('When someone writes in chat (at most one a minute for each room)', 'chat', n.chat),
    sw('When a Gate System updates (those within a minute of each other in one message)', 'updated', n.updated)]);
  c.appendChild(kinds);
  // the topic, and subscribing to it from this very phone
  const host = (n.url || 'https://ntfy.sh').replace(/^https?:\/\//, '').replace(/\/+$/, '');
  if (n.topic) {
    const t = el('code', 'topic', n.topic), copy = button('Copy', async () => { try { await navigator.clipboard.writeText(n.topic); say({ result: 'ok', message: 'Topic copied.' }, said); } catch (e) { say({ result: 'refused', message: 'Copying isn’t allowed here: select the topic and copy it.' }, said); } }, 'quiet small');
    // (only ntfy's Android app opens ntfy:// links; on an iPhone, and on a computer, the topic is copied and the steps shown)
    const android = /Android/i.test(navigator.userAgent);
    let sub;
    if (android) { sub = el('a', 'btn-link', 'Subscribe on this phone'); sub.href = 'ntfy://' + host + '/' + encodeURIComponent(n.topic); sub.title = 'Opens the ntfy app on this phone with the topic filled in (the app has to be installed)'; }
    else {
      sub = button('Copy and add in ntfy', async () => {
        let copied = false; try { await navigator.clipboard.writeText(n.topic); copied = true; } catch (e) { /* (shown below to copy by hand) */ }
        steps.hidden = false; hint.hidden = false; say({ result: copied ? 'ok' : 'refused', message: copied ? 'Topic copied. Now add it in the ntfy app:' : 'Copy the topic above by hand, then add it in the ntfy app:' }, hint);
        steps.scrollIntoView({ block: 'nearest' });
      }, 'primary');
    }
    const steps = el('ol', 'steps', null, [el('li', '', 'Install ntfy from the ' + (/iPhone|iPad|iPod/i.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1) ? 'App Store' : 'App Store or Google Play') + ', if you haven’t yet.'),
      el('li', '', 'Open ntfy and tap +.'), el('li', '', 'Paste the topic' + (host === 'ntfy.sh' ? ' and leave the server as ntfy.sh.' : '. Turn on Use another server and enter ' + host + '.')), el('li', '', 'Tap Subscribe, then press Send a test here.')]);
    steps.hidden = true;
    const hint = el('p', 'said'); hint.hidden = true;
    c.appendChild(el('div', 'topicrow', null, [el('span', 'grey small', 'Topic'), t, copy, sub]));
    c.appendChild(hint); c.appendChild(steps);
  } else c.appendChild(el('p', 'note small', 'No topic yet: turning notifications on makes one.'));
  // the server, a new topic, a test
  const url = el('input', 'grow'); url.value = n.url || 'https://ntfy.sh'; url.autocapitalize = 'none'; url.spellcheck = false; url.setAttribute('aria-label', 'ntfy server');
  c.appendChild(el('div', 'row', null, [el('span', 'grey small', 'Server'), url, button('Save', () => save({ url: url.value.trim() }), 'quiet small')]));
  c.appendChild(el('div', 'row', null, [
    button('Send a test', async () => { say({ message: 'Sending…' }, said); const r = await api('POST', 'notify/test', {}); say(r, said); }, 'primary'),
    button('New topic', async () => { if (!(await confirmSheet('Make a new topic? Phones subscribed to the old one stop getting notifications until they subscribe to the new one.', 'New topic', true))) return; await api('POST', 'notify/topic', {}); views.gatekeeper.draw(); }, 'quiet')]));
  if (n.last) c.appendChild(el('p', 'note small', 'Last test: ' + n.last));
  // crash watch: which servers are started again when they go down
  if ((n.servers || []).length) {
    c.appendChild(el('h3', 'sub2', 'Start again after a crash'));
    n.servers.forEach(s => { const b = el('input'); b.type = 'checkbox'; b.checked = !!s.keep; b.addEventListener('change', async () => { say({ message: 'Saving…' }, said); await api('POST', 'notify/keep', { server: s.id, on: b.checked }); say({ result: 'ok', message: b.checked ? s.name + ' is started again when it goes down (at most three times in ten minutes).' : s.name + ' is left off when it goes down.' }, said); }); c.appendChild(el('label', 'row switchrow', null, [b, el('span', '', s.name)])); });
  }
  c.appendChild(said);
  const dim = () => { kinds.classList.toggle('off', !main.querySelector('input').checked); };
  main.querySelector('input').addEventListener('change', () => { dim(); if (main.querySelector('input').checked && !n.topic) setTimeout(() => views.gatekeeper.draw(), 600); });
  dim();
  return c;
}

// (in game first, then online, then offline)
// (the newest version this Gatekeeper knows of: a half older than it is marked "update ready")
let newestSeen = '';
const older = (a, b) => { const x = a.split('.').map(n => parseInt(n, 10) || 0), y = b.split('.').map(n => parseInt(n, 10) || 0); for (let i = 0; i < Math.max(x.length, y.length); i++) { if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) < (y[i] || 0); } return false; };
const offline = m => (!m.gatehouse || m.gatehouse.now === 'Offline') && (!m.gatekeeper || m.gatekeeper.now === 'Offline');
function rank(m) { return m.gatehouse && m.gatehouse.now === 'In game' ? 0 : offline(m) ? 2 : 1; }
// R105: a machine's castle in its programs' colours: Gatehouse yellow, Gatekeeper blue, and with both, half each,
// joined along a 45-degree seam.
let castleN = 0;
function castle(keeper, house) {
  const s = document.createElementNS(SVGNS, 'svg'); s.setAttribute('viewBox', '0 0 24 24'); s.setAttribute('aria-hidden', 'true'); s.setAttribute('class', 'castle');
  const p = document.createElementNS(SVGNS, 'path'); p.setAttribute('d', ICONS.keeper);
  if (keeper && house) {
    const id = 'cg' + (++castleN), defs = document.createElementNS(SVGNS, 'defs'), g = document.createElementNS(SVGNS, 'linearGradient');
    g.setAttribute('id', id); g.setAttribute('x1', '0'); g.setAttribute('y1', '0'); g.setAttribute('x2', '1'); g.setAttribute('y2', '1');
    [['0', '#f5be1e'], ['0.5', '#f5be1e'], ['0.5', '#66c0f4'], ['1', '#66c0f4']].forEach(([o, c]) => { const st = document.createElementNS(SVGNS, 'stop'); st.setAttribute('offset', o); st.setAttribute('stop-color', c); g.appendChild(st); });
    defs.appendChild(g); s.appendChild(defs); p.setAttribute('fill', 'url(#' + id + ')');
  } else p.setAttribute('fill', keeper ? '#66c0f4' : '#f5be1e');
  s.appendChild(p);
  return s;
}
// Which halves a machine has, as badges: Gatehouse, Gatekeeper, or both.
function halvesLine(keeper, house) {
  const l = el('div', 'halves');
  if (house) l.appendChild(el('span', 'half house', null, [castle(false, true), el('span', '', 'Gatehouse')]));
  if (keeper) l.appendChild(el('span', 'half keeper', null, [castle(true, false), el('span', '', 'Gatekeeper')]));
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
  c.appendChild(el('div', 'glyph', null, [castle(!!keeper, !!house)]));   // (R105)
  c.appendChild(el('div', '', null, [name, halvesLine(!!keeper, !!house), el('div', 'sub', m.system || '')]));
  const lines = el('div', 'lines');
  if (keeper) lines.appendChild(halfLine('Gatekeeper', keeper));
  if (house) lines.appendChild(halfLine('Gatehouse', house));
  c.appendChild(lines);
  const f = el('div', 'facts'), ping = house && house.ping > 0 ? house.ping : 0;
  if (ping) f.appendChild(pingFact(ping));
  if (m.battery) f.appendChild(batteryFact(m.battery));
  if (m.hands_out) f.appendChild(fact(m.hands_out, 'info', ICONS.share));
  if (offline(m) && (m.last_seen_at || m.last_seen)) f.appendChild(fact('Last seen ' + shownTime(m.last_seen_at, m.last_seen), '', ICONS.clock));
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
// R85: web links in a message can be opened (in a new tab, or the site's own app on a phone)
function linked(text) {
  const out = [], re = /https?:\/\/[^\s<>"']+/gi; let at = 0, m;
  while ((m = re.exec(text))) {
    let u = m[0]; while (/[.,;:!?)\]}'"]$/.test(u)) u = u.slice(0, -1);
    if (m.index > at) out.push(document.createTextNode(text.slice(at, m.index)));
    const a = el('a', 'msglink', u); a.href = u; a.target = '_blank'; a.rel = 'noopener noreferrer'; out.push(a);
    at = m.index + u.length; re.lastIndex = at;
  }
  if (at < text.length) out.push(document.createTextNode(text.slice(at)));
  return out;
}
// R120: a message's first link gets the buttons Gatehouse's chat has. Nothing is fetched until one is pressed. Show
// preview asks this Gatekeeper to fetch the page (it never fetches this machine, the home network or Tailscale
// addresses) and shows its title, site, description and picture; a picture or a video straight from the link, X's
// photos and video, and YouTube's player (its privacy-enhanced one) come from their own sites, once pressed.
const LINKRE = /https?:\/\/[^\s<>"']+/i, PICLINK = /\.(png|jpe?g|gif|webp)$/i;
function firstLink(t) { const m = LINKRE.exec(t || ''); if (!m) return null; let u = m[0]; while (/[.,;:!?)\]}'"]$/.test(u)) u = u.slice(0, -1); return u; }
function ytId(u) {
  try {
    const x = new URL(u), h = x.hostname.replace(/^(www|m|music)\./, '');
    if (h === 'youtu.be') { const v = x.pathname.slice(1, 12); return /^[\w-]{11}$/.test(v) ? v : null; }
    if (h === 'youtube.com' || h === 'youtube-nocookie.com') { const v = x.searchParams.get('v') || (x.pathname.match(/^\/(?:shorts|embed|live)\/([\w-]{11})/) || [])[1]; return v && /^[\w-]{11}$/.test(v) ? v : null; }
  } catch (e) {}
  return null;
}
function xPost(u) { try { const x = new URL(u), h = x.hostname.replace(/^(www|mobile)\./, ''); if (h !== 'x.com' && h !== 'twitter.com') return null; const m = x.pathname.match(/^\/[^/]+\/status(?:es)?\/(\d{1,25})/); return m ? m[1] : null; } catch (e) { return null; } }
function linkTools(link) {
  const box = el('div', 'linkbox'), row = el('div', 'linkrow'), out = el('div', 'linkout');
  const yt = ytId(link), xid = xPost(link), https = /^https:/i.test(link), path = link.split(/[?#]/)[0], name = decodeURIComponent(path.split('/').pop() || 'link');
  const site = yt ? 'YouTube' : xid ? 'X' : /^https?:\/\/(www\.)?vimeo\.com\//i.test(link) ? 'Vimeo' : null;
  const open = el('a', 'linkbtn', site ? 'Watch on ' + site : 'Open link'); open.href = link; open.target = '_blank'; open.rel = 'noopener noreferrer';
  const shown = {};
  // (a part shown under the buttons, or taken away again, by the same button)
  const part = (b, key, label, make) => {
    if (shown[key]) { shown[key].remove(); delete shown[key]; b.textContent = label; return; }
    const p = make(); if (!p) return; shown[key] = p; out.appendChild(p); b.textContent = 'Hide ' + label.replace(/^Show /, '').replace(/^Play here$/, 'player').replace(/^Play$/, 'player');
  };
  const add = (label, make, key) => { const b = button(label, () => part(b, key, label, make), 'quiet small'); row.appendChild(b); return b; };
  if (https && MEDIA.test(path)) add('Play', () => player(link, name, 0), 'play');
  else if (https && PICLINK.test(path)) add('Show picture', () => { const i = el('img', 'shot'); i.src = link; i.alt = 'A picture from the link'; i.referrerPolicy = 'no-referrer'; return i; }, 'pic');
  if (yt) add('Play here', () => { const f = el('iframe', 'ytframe'); f.src = 'https://www.youtube-nocookie.com/embed/' + yt + '?autoplay=1&rel=0&playsinline=1'; f.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen'; f.allowFullscreen = true; f.title = 'YouTube video'; f.setAttribute('referrerpolicy', 'strict-origin-when-cross-origin'); return f; }, 'yt');
  row.appendChild(open);
  // Show preview (Show post for an X post): asked of this Gatekeeper once, then shown or hidden
  let asked = null;
  const ask = () => asked || (asked = ask0());
  const ask0 = async () => { const r = await ask0Fetch(); if (!r || r.result !== 'ok') { asked = null; say(r || { result: 'failed', message: "Gatekeeper didn't answer." }); return null; } return r; };
  const ask0Fetch = async () => { try { const res = await fetch('/panel/preview?u=' + encodeURIComponent(link), { credentials: 'same-origin' }); if (res.status === 401) { showSignin(); return null; } return await res.json(); } catch (e) { return { result: 'failed', message: "Gatekeeper didn't answer." }; } };
  // (an X post: one button, Show post: the post itself with its photos and video, and the post it quotes with its own)
  if (xid) {
    const xb = button('Show post', async () => {
      if (shown.post) { shown.post.remove(); delete shown.post; xb.textContent = 'Show post'; return; }
      xb.disabled = true; const r = await ask(); xb.disabled = false; if (!r) return;
      const c = r.kind === 'x' ? xCard(r.post, r.quoted, true) : pageCard(r, link);
      shown.post = c; out.appendChild(c); xb.textContent = 'Hide post';
    }, 'quiet small');
    row.appendChild(xb);
  } else {
    const pv = button('Show preview', async () => {
      if (shown.prev) { shown.prev.remove(); delete shown.prev; pv.textContent = 'Show preview'; return; }
      pv.disabled = true; const r = await ask(); pv.disabled = false; if (!r) return;
      const c = r.kind === 'x' ? xCard(r.post, r.quoted, true) : pageCard(r, link);
      shown.prev = c; out.appendChild(c); pv.textContent = 'Hide preview';
    }, 'quiet small');
    row.appendChild(pv);
  }
  box.appendChild(row); box.appendChild(out);
  return box;
}
function pageCard(r, link) {
  const c = el('a', 'lcard'); c.href = link; c.target = '_blank'; c.rel = 'noopener noreferrer';
  if (r.picture) { const i = el('img', 'lpic'); i.src = r.picture; i.alt = ''; c.appendChild(i); }
  const t = el('div', 'ltext'); t.appendChild(el('b', '', r.title || link)); if (r.site) t.appendChild(el('div', 'lsite', r.site)); if (r.description) t.appendChild(el('div', 'ldesc', r.description));
  c.appendChild(t); return c;
}
// (an X post's photos, and its video in the panel's player: straight from X's own servers)
function xMediaInto(c, p) {
  if (!p) return;
  const row = el('div', 'xpics');
  (p.photos || []).forEach(u => { const i = el('img', 'shot'); i.src = u; i.alt = 'A photo from the post'; i.referrerPolicy = 'no-referrer'; row.appendChild(i); });
  if (row.childNodes.length) c.appendChild(row);
  if (p.video) { const pl = player(p.video, 'x-' + (p.id || 'video') + '.mp4', 0), v = pl.querySelector('video'); if (v && p.thumb) v.poster = p.thumb; c.appendChild(pl); }   // (its still picture until Play)
}
// R121: who wrote an X post and what, and (as a card of its own, under it) the post it quotes
function xCard(p, quoted, media, isQuote) {
  const c = el('div', isQuote ? 'lcard xquote' : 'lcard xpost');
  if (!p || !p.known) {
    const id = p && p.id; const a = el('a', 'msglink', id ? 'Quoting a post X didn\'t give: https://x.com/i/status/' + id : 'Quoting a post X didn\'t give (it may be deleted or private).');
    if (id) { a.href = 'https://x.com/i/status/' + id; a.target = '_blank'; a.rel = 'noopener noreferrer'; }
    c.appendChild(a); return c;
  }
  const t = el('div', 'ltext');
  const head = el('div', '', null, [isQuote ? el('span', 'lsite', 'Quoting ') : null, el('b', '', p.name || 'A post on X'), el('span', 'lsite', ' @' + (p.handle || ''))].filter(Boolean));
  t.appendChild(head);
  if (p.text) t.appendChild(el('div', 'ldesc', p.text));
  c.appendChild(t);
  if (media) xMediaInto(c, p);
  if (quoted) c.appendChild(xCard(quoted, null, media, true));
  return c;
}
function bubble(m) {
  const mine = m.who === 'Host', b = el('div', 'bubble', null, m.file ? null : linked(String(m.text || '')));
  if (!m.file) { const lk = firstLink(String(m.text || '')); if (lk) b.appendChild(linkTools(lk)); }   // (R120)
  if (m.file) {
    const f = m.file, url = '/panel/file?id=' + encodeURIComponent(f.id || '');
    b.appendChild(el('div', '', mine ? 'Offered a file' : 'Offers a file'));
    if (MEDIA.test(f.name || '')) {
      b.appendChild(player(url, f.name, f.size));
      b.appendChild(chip(url, f));
    } else if (IMAGE.test(f.name || '')) {
      const img = el('img', 'shot'); img.src = url; img.alt = f.name; img.loading = 'lazy';
      img.addEventListener('click', () => { const d = $('viewer'); $('viewer-img').src = url; $('viewer-img').alt = f.name; if (d.showModal) d.showModal(); });
      b.appendChild(img);
    } else {
      b.appendChild(chip(url, f));
    }
  }
  const when = shownTime(m.at, m.when);
  return el('div', 'msg' + (mine ? ' mine' : ''), null, [el('div', 'meta', (mine ? 'You (Host)' : m.who) + ' · ' + when), b]);
}
function chip(url, f) {
  const a = el('a', 'filechip', null, [icon(ICONS.file)]); a.href = url; a.download = f.name || 'file';
  a.appendChild(document.createTextNode((f.name || 'file') + (f.size ? ' · ' + size(f.size) : ''))); return a;
}
// R62: a video or a sound plays in the chat: play/pause, back and forward 10 seconds, a seek bar with the time, mute and
// volume, theater (the message as wide as the chat) and full screen. Keys while it has focus: Space/K, Left/Right or
// J/L, Up/Down, M, T, F. Nothing is fetched until Play is pressed (preload none), as for pictures on a phone's data.
const MEDIA = /\.(mp4|m4v|webm|mov|mkv|ogv|mp3|m4a|wav|ogg|oga|opus|flac|aac)$/i, VIDEO = /\.(mp4|m4v|webm|mov|mkv|ogv)$/i;
const PICONS = {
  play: 'M8 5v14l11-7z', pause: 'M7 5h4v14H7zm6 0h4v14h-4z',
  vol: 'M4 9v6h4l5 4V5L8 9zm12 3a4 4 0 0 0-2.2-3.6v7.2A4 4 0 0 0 16 12z',
  muted: 'M4 9v6h4l5 4V5L8 9zm11.3.3 1.4-1.4 2 2 2-2 1.4 1.4-2 2 2 2-1.4 1.4-2-2-2 2-1.4-1.4 2-2z',
  skip: 'M12 5V1L7 6l5 5V7c3.31 0 6 2.69 6 6s-2.69 6-6 6-6-2.69-6-6H4c0 4.42 3.58 8 8 8s8-3.58 8-8-3.58-8-8-8z',
  theater: 'M2 6h20v12H2zm2 2v8h16V8z', full: 'M4 4h6v2H6v4H4zm10 0h6v6h-2V6h-4zM4 14h2v4h4v2H4zm14 0h2v6h-6v-2h4z'
};
const clock = s => { s = Math.floor(s || 0); const h = Math.floor(s / 3600), mm = Math.floor(s / 60) % 60, ss = String(s % 60).padStart(2, '0'); return h ? h + ':' + String(mm).padStart(2, '0') + ':' + ss : mm + ':' + ss; };
function player(url, name, bytes) {
  const video = VIDEO.test(name), m = el(video ? 'video' : 'audio');
  m.preload = 'none'; m.src = url; m.setAttribute('playsinline', '');
  const box = el('div', 'player ' + (video ? 'video' : 'sound')); box.tabIndex = 0; box.setAttribute('aria-label', 'Player: ' + name);
  const ib = (d, label, fn) => { const b = button(null, fn, 'pbtn'); b.appendChild(icon(d)); b.setAttribute('aria-label', label); b.title = label; return b; };
  // (R92: the skip keys are the standard round arrow with the seconds inside, the forward one turned the other way)
  const sk = (label, fwd, fn) => { const b = ib(PICONS.skip, label, fn), s = b.firstChild; if (fwd) s.querySelector('path').setAttribute('transform', 'matrix(-1 0 0 1 24 0)');
    const t = document.createElementNS(SVGNS, 'text'); t.setAttribute('x', '12'); t.setAttribute('y', '16'); t.setAttribute('text-anchor', 'middle'); t.setAttribute('class', 'skipn'); t.textContent = '10'; s.appendChild(t); return b; };
  const swap = (b, d, label) => { b.replaceChildren(icon(d)); b.setAttribute('aria-label', label); b.title = label; };
  const toggle = () => { if (m.paused) m.play().catch(() => {}); else m.pause(); };
  const fullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (box.requestFullscreen) box.requestFullscreen().catch(() => { if (m.webkitEnterFullscreen) m.webkitEnterFullscreen(); });
    else if (m.webkitEnterFullscreen) m.webkitEnterFullscreen();
  };
  const play = ib(PICONS.play, 'Play (Space)', toggle);
  const back = sk('Back 10 seconds (Left)', false, () => { m.currentTime = Math.max(0, m.currentTime - 10); });
  const fwd = sk('Forward 10 seconds (Right)', true, () => { if (m.duration) m.currentTime = Math.min(m.duration, m.currentTime + 10); });
  const seek = el('input', 'seek'); seek.type = 'range'; seek.min = 0; seek.max = 1000; seek.value = 0; seek.setAttribute('aria-label', 'Position');
  const at = el('span', 'ptime', bytes ? size(bytes) : '0:00');
  const mute = ib(PICONS.vol, 'Mute (M)', () => { m.muted = !m.muted; });
  const vol = el('input', 'vol'); vol.type = 'range'; vol.min = 0; vol.max = 1; vol.step = 0.05; vol.value = 1; vol.setAttribute('aria-label', 'Volume');
  const kids = [play, back, fwd, seek, at, mute, vol];
  let theater = null, full = null;
  if (video) {
    theater = ib(PICONS.theater, 'Theater (T)', () => { const msg = box.closest('.msg'); if (msg) msg.classList.toggle('theater'); });
    full = ib(PICONS.full, 'Full screen (F)', fullscreen);
    kids.push(theater, full);
    box.appendChild(m);
    m.addEventListener('click', toggle);
    m.addEventListener('dblclick', fullscreen);
  } else {
    box.appendChild(el('div', 'pname', name));
    box.appendChild(m);
  }
  box.appendChild(el('div', 'pbar', null, kids));
  let held = false;
  const show = () => { if (!held) seek.value = m.duration ? Math.round(m.currentTime / m.duration * 1000) : 0; at.textContent = clock(m.currentTime) + ' / ' + clock(m.duration); };
  m.addEventListener('timeupdate', show);
  m.addEventListener('loadedmetadata', show);
  // R93: a video's player is as wide as its picture (at most 270 high and 640 wide, never enlarged), not the chat's width
  if (video) m.addEventListener('loadedmetadata', () => { const w = m.videoWidth, h = m.videoHeight; if (!w || !h) return; let ph = Math.min(270, h), pw = ph * w / h; if (pw > 640) pw = 640; box.style.setProperty('--pw', Math.round(pw) + 'px'); });
  m.addEventListener('play', () => swap(play, PICONS.pause, 'Pause (Space)'));
  m.addEventListener('pause', () => swap(play, PICONS.play, 'Play (Space)'));
  m.addEventListener('volumechange', () => swap(mute, m.muted || m.volume === 0 ? PICONS.muted : PICONS.vol, m.muted ? 'Unmute (M)' : 'Mute (M)'));
  seek.addEventListener('input', () => { held = true; if (m.duration) at.textContent = clock(seek.value / 1000 * m.duration) + ' / ' + clock(m.duration); });
  seek.addEventListener('change', () => { if (m.duration) m.currentTime = seek.value / 1000 * m.duration; held = false; });
  vol.addEventListener('input', () => { m.volume = +vol.value; m.muted = +vol.value === 0; });
  m.addEventListener('error', () => { box.replaceWith(el('div', 'note', "This browser can't play " + name + ': save it below and open it on this device.')); });
  box.addEventListener('keydown', e => {
    const k = e.key.toLowerCase(), inRange = e.target.tagName === 'INPUT';
    if (k === ' ' || k === 'k') { if (e.target.tagName === 'BUTTON') return; toggle(); }
    else if ((k === 'arrowleft' || k === 'j') && !inRange) back.click();
    else if ((k === 'arrowright' || k === 'l') && !inRange) fwd.click();
    else if (k === 'arrowup' && !inRange) { m.volume = Math.min(1, m.volume + 0.1); vol.value = m.volume; }
    else if (k === 'arrowdown' && !inRange) { m.volume = Math.max(0, m.volume - 0.1); vol.value = m.volume; }
    else if (k === 'm') mute.click();
    else if (k === 't' && theater) theater.click();
    else if (k === 'f' && full) full.click();
    else return;
    e.preventDefault();
  });
  return box;
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
      const tasks = el('div', 'card', null, [el('h3', '', 'Backups and tasks'), el('div', 'note', (s.backup_at && /^Backed up .*?: /.test(s.backup_state || '') ? s.backup_state.replace(/^Backed up .*?: /, 'Backed up ' + shownTime(s.backup_at) + ': ') : s.backup_state) || ((s.backups || []).length + ' backup(s) kept in ' + s.backup_dir)),
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
  if (me.gatekeeper) document.title = me.gatekeeper + ' · Gatekeeper';   // (R111: which Gatekeeper this tab is for)
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
  // help: the user guide and the program's GitHub page, in a new tab (R43, R44)
  dr.appendChild(el('div', 'drawer-head', 'HELP'));
  [['User guide', 'https://github.com/scholar-andrew/gatehouse/blob/main/docs/user-guide.md'], ['GitHub page', 'https://github.com/scholar-andrew/gatehouse']].forEach(([name, href]) => {
    const a = el('a', 'drawer-link', name); a.href = href; a.target = '_blank'; a.rel = 'noopener noreferrer'; dr.appendChild(a);
  });
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
