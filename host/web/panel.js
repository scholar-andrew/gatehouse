// Gatekeeper's web panel. Everything shown comes from Gatekeeper as data and is put on the page as text, never as markup.
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
function button(text, onclick, cls) { const b = el('button', cls || '', text); b.type = 'button'; b.addEventListener('click', onclick); return b; }
function can(p) { return me && (me.admin || me.perms.includes(p)); }
function say(r, where) {
  const s = where || $('said');
  s.className = 'said ' + (r && r.result === 'ok' ? 'green' : r && r.result ? 'red' : 'grey');
  s.textContent = r ? (r.message || (r.result === 'ok' ? 'Done.' : '')) : '';
}
async function ask(method, call, body, server) {
  const opt = { method, headers: {}, credentials: 'same-origin' };
  if (method !== 'GET') { opt.headers['Content-Type'] = 'application/json'; opt.headers['X-Panel-Token'] = me ? me.csrf : ''; opt.body = JSON.stringify(body || {}); }
  let res;
  try { res = await fetch('/panel/' + call + (server ? '?server=' + encodeURIComponent(server) : ''), opt); }
  catch (e) { return { result: 'failed', message: "Gatekeeper didn't answer. Is this device still on Tailscale?" }; }
  if (res.status === 401 && call !== 'login') { showSignin(); return { result: 'signin', message: 'Sign in again.' }; }
  try { return await res.json(); } catch (e) { return { result: 'failed', message: 'Gatekeeper answered with something unexpected.' }; }
}
const api = (method, call, body, server) => ask(method, 'api/' + call, body, server);
async function act(call, body, server, confirmText) {
  if (confirmText && !window.confirm(confirmText)) return;
  say({ message: 'Asking Gatekeeper…' });
  say(await api('POST', call, body, server));
  if (views[current]) views[current].draw();
}
function showSignin() { me = null; clearInterval(timer); clearInterval(watch); $('strip').classList.add('hidden'); $('menu').classList.add('hidden'); menu(false); $('nav').replaceChildren(); $('app').classList.add('hidden'); $('signout').classList.add('hidden'); $('whoami').textContent = ''; $('signin').classList.remove('hidden'); $('user').focus(); }
// A card: a square of art with the name's initials, the name, how it is (with a dot), a line under it, and its buttons.
function card(name, stateClass, state, sub, buttons) {
  let h = 0; for (const ch of name) h = (h * 31 + ch.codePointAt(0)) >>> 0;
  const initials = (name.match(/[\p{L}\p{N}]+/gu) || ['?']).slice(0, 2).map(w => w[0].toUpperCase()).join('');
  return el('div', 'card', null, [el('div', 'art h' + (h % 8), initials), el('h3', '', name), el('div', '', null, [el('div', stateClass, state), el('div', 'note', sub)]), buttons]);
}
function phaseWord(s) { return s.running ? 'On, ' + s.players + ' playing' : s.phase === 'starting' ? 'Starting…' : s.phase === 'stopping' ? 'Shutting down…' : 'Off'; }
function phaseClass(s) { return s.running ? 'green' : (s.phase === 'starting' || s.phase === 'stopping') ? 'amber' : 'grey'; }

const views = {
  servers: { name: 'Servers', section: 'SERVERS', needs: 'view', every: 4000, async draw() {
    const o = await api('GET', 'overview');
    if (current !== 'servers' || !o.servers) return;
    const v = el('div', 'grid');
    if (o.host === 'mcss') {
      const up = o.mcss, line = el('div', 'buttons row'), mc = card('MC Server Soft', (up ? 'green' : 'grey') + ' state', o.mcss_phase === 'starting' ? 'Starting…' : o.mcss_phase === 'closing' ? 'Closing…' : up ? 'Running' : 'Not running', 'The program that runs some of this machine\'s servers', line);
      if (can('control')) line.appendChild(up ? button('Close MCSS', () => act('mcss/close', {}, '', 'Close MC Server Soft on the server machine?')) : button('Start MCSS', () => act('mcss/start', {})));
      v.appendChild(mc);
    }
    o.servers.forEach(s => {
      const row = el('div', 'buttons row'), one = card(s.name, phaseClass(s) + ' state', phaseWord(s), 'Port ' + s.port + (o.host === 'mcss' ? (s.native ? '   ·   run by Gatekeeper itself' : '   ·   run by MCSS') : ''), row);
      const up = s.running || s.phase === 'starting';
      if (can('control')) {
        row.appendChild(up ? button('Stop', () => act('server/stop', {}, s.id, 'Stop ' + s.name + '? Anyone playing is disconnected (the world is saved first).')) : button('Start', () => act('server/start', {}, s.id), 'primary'));
        if (s.native) { const r = button('Restart', () => act('tools/action', { action: 'restart' }, s.id, 'Restart ' + s.name + '? Anyone playing is disconnected until it is back.')); r.disabled = !s.running; row.appendChild(r); }
      }
      if (s.native) row.appendChild(button('Open', () => openServer(s.id, s.name), 'quiet'));
      if (!row.children.length) row.appendChild(el('span', 'note', 'Your account may look, not start or stop.'));
      v.appendChild(one);
    });
    const under = el('div');
    if (!o.servers.length) under.appendChild(el('p', 'note', 'This Gatekeeper looks after no servers yet.'));
    if (o.servers.some(s => !s.native)) under.appendChild(el('p', 'note', 'Consoles, settings, backups and tasks for the servers MC Server Soft runs are kept by MCSS.'));
    if (v.contains(document.activeElement)) return;   // (a button there is selected: leave it be until the next look)
    $('view').replaceChildren(v, under);
  } },
  players: { name: 'Players', section: 'PEOPLE', needs: 'view', every: 8000, async draw() {
    const r = await api('GET', 'players');
    if (current !== 'players' || !r.players) return;
    const t = el('table'), names = Object.keys(r.players);
    t.appendChild(el('tr', '', null, ['Player', 'Now', 'Whitelisted', ''].map(h => el('th', '', h))));
    names.forEach(n => {
      const p = r.players[n], cell = el('td', 'row');
      if (can('access')) cell.appendChild(button(p.whitelisted ? 'Take off whitelist' : 'Whitelist', () => act('whitelist', { player: n, value: !p.whitelisted }), 'quiet'));
      if (can('console') && p.now === 'In game') cell.appendChild(button('Kick', () => act('kick', { player: n, reason: 'Kicked from the web panel' }, '', 'Kick ' + n + '?'), 'quiet'));
      t.appendChild(el('tr', '', null, [el('td', '', n + (p.ban ? '  (' + p.ban + ')' : '')), el('td', p.now === 'In game' ? 'green' : 'grey', p.now), el('td', '', p.whitelisted ? 'Yes' : 'No'), cell]));
    });
    const v = el('div', 'card sheet scroll', null, [t]);
    if (!names.length) v.appendChild(el('p', 'note', 'Nobody yet.'));
    if (can('access') && !$('wl-name')) {
      const f = el('input'); f.id = 'wl-name'; f.placeholder = 'Minecraft name';
      v.appendChild(el('div', 'row', null, [f, button('Add to whitelist', () => { const n = f.value.trim(); if (n) act('whitelist', { player: n, value: true }); })]));
    }
    if (document.activeElement && document.activeElement.id === 'wl-name') return;
    $('view').replaceChildren(v);
  } },
  access: { name: 'Launchers', section: 'PEOPLE', needs: 'access', every: 6000, async draw() {
    const r = await api('GET', 'devices');
    if (current !== 'access' || !r.devices) return;
    const v = el('div');
    (r.requests || []).forEach(q => v.appendChild(el('div', 'card row', null, [el('div', 'grow', q.who + (q.player ? ' (' + q.player + ')' : '') + ' asks for: ' + q.type.replace('perm:', '') + (q.server ? ' on ' + q.server : '')),
      button('Allow', () => act('request/answer', { id: q.id, type: q.type, grant: true }, q.server_id)), button('Deny', () => act('request/answer', { id: q.id, type: q.type, grant: false }, q.server_id), 'quiet')])));
    const t = el('table');
    t.appendChild(el('tr', '', null, ['Launcher', 'Player', 'Now', 'Server', 'MCSS', 'Wake/sleep', 'Gatekeeper', ''].map(h => el('th', '', h))));
    Object.keys(r.devices).forEach(id => {
      const d = r.devices[id], row = el('tr', '', null, [el('td', '', d.name || id), el('td', '', d.player || ''), el('td', 'grey', d.now || '')]);
      [['server', d.state_server], ['mcss', d.state_mcss], ['sleep', d.state_machine], ['admin', d.state_admin]].forEach(([perm, st]) => {
        const on = st === 'allowed';
        row.appendChild(el('td', '', null, [button(on ? 'Allowed' : st === 'denied' ? 'Denied' : 'No', () => act('perm', { id, perm, state: on ? 'none' : 'allowed' }), on ? '' : 'quiet')]));
      });
      row.appendChild(el('td', '', null, [button('Forget', () => act('forget', { id }, '', 'Forget this launcher? It can ask again later.'), 'quiet')]));
      t.appendChild(row);
    });
    v.appendChild(el('div', 'card sheet scroll', null, [t]));
    v.appendChild(el('p', 'note', 'Click a permission to allow it or take it away. Blacklisting, suspending and per-player permissions are in Gatekeeper\'s own window.'));
    $('view').replaceChildren(v);
  } },
  machine: { name: 'Sleep and wake', section: 'MACHINE', needs: 'machine', async draw() {
    const r = await api('GET', 'wake');
    if (current !== 'machine') return;
    const v = el('div');
    v.appendChild(el('div', 'card row', null, [el('div', 'grow', null, [el('h3', '', 'This machine (' + me.gatekeeper + ', ' + me.system + ')'), el('div', 'note', 'Put it to sleep, with its servers stopped. This page can\'t wake it again: it runs on that machine. A launcher on its home network can.')]),
      button('Sleep', () => act('sleep', {}, '', 'Put the server machine to sleep? This panel stops answering until it is woken.'), 'danger')]));
    v.appendChild(el('h2', '', 'Wake another device'));
    (r.wake_devices || []).forEach(d => v.appendChild(el('div', 'card row', null, [el('div', 'grow', null, [el('h3', '', d.name), el('div', 'grey', d.mac)]), button('Wake', () => act('wake', { send: d.name }))])));
    if (!(r.wake_devices || []).length) v.appendChild(el('p', 'note', 'None yet.'));
    v.appendChild(el('p', 'note', 'Devices are added in Gatekeeper\'s window (Web panel tab). The wake signal is sent by the server machine on its own network, so it reaches devices in the same home, by cable or (on some adapters) Wi-Fi, that are set to wake on it.'));
    $('view').replaceChildren(v);
  } },
  constellation: { name: 'Clients', section: 'CONSTELLATION', needs: 'updates', every: 5000, async draw() {
    const r = await api('GET', 'constellation');
    if (current !== 'constellation' || !r.gatekeeper) { if (r.message) say(r); return; }
    const word = m => m === 'auto' ? 'Auto' : m === 'constellation' ? 'Constellation' : m === 'normal' ? 'Normal' : 'Not said yet';
    const g = r.gatekeeper, t = el('table');
    t.appendChild(el('tr', '', null, ['Client', 'Kind', 'Player', 'System', 'Version', 'Update mode', 'Now', 'In this constellation'].map(h => el('th', '', h))));
    t.appendChild(el('tr', '', null, [g.name + '  (this Gatekeeper)', 'Gatekeeper', '', g.system, g.version, word(g.mode), 'Running', 'Leads it'].map(x => el('td', '', x || ''))));
    (r.clients || []).forEach(c => {
      const also = (c.also || []).length ? '; also ' + c.also.join(', ') : '';
      const inIt = c.membership === 'asked' ? 'Asked to join: not allowed in yet' : c.membership === 'member' ? 'Member (a Gatekeeper that joined)' : (c.via ? 'Yes, through ' + c.via : 'Yes') + also;
      const row = el('tr', '', null, [(c.via ? '    ' : '') + c.name + (c.here ? '  (this machine)' : ''), c.type, c.player, c.system, c.version || 'Not said', word(c.mode)].map(x => el('td', '', x || '')));
      row.appendChild(el('td', c.open ? 'green' : 'grey', c.open ? 'Open' : 'Closed')); row.appendChild(el('td', '', inIt));
      t.appendChild(row);
    });
    const v = el('div', 'card sheet scroll', null, [el('p', 'note', 'A constellation is a Gatekeeper and every client connected to it: its launchers, other Gatekeepers that have joined it, and their launchers.'), t,
      el('p', '', 'This Gatekeeper: ' + (g.status || '')), el('p', 'note', 'Allowing a Gatekeeper in, removing one, and joining another constellation are done in Gatekeeper\'s own window.')]);
    $('view').replaceChildren(v);
  } },
  updates: { name: 'Update log', section: 'CONSTELLATION', needs: 'updates', every: 15000, async draw() {
    const r = await api('GET', 'updates');
    if (current !== 'updates' || !r.entries) { if (r.message) say(r); return; }
    const t = el('table');
    t.appendChild(el('tr', '', null, ['When', 'Client', 'Kind', 'Player', 'System', 'From', 'To', 'How', 'Fetched from', 'Through'].map(h => el('th', '', h))));
    r.entries.forEach(e => t.appendChild(el('tr', '', null, [e.when, e.client, e.kind, e.player, e.system + (e.info ? ' (' + e.info + ')' : ''), e.from, e.to, e.how, e.source || 'not said', e.via || (e.also ? '(also heard through ' + e.also + ')' : '')].map(x => el('td', '', x || '')))));
    const v = el('div', 'card sheet scroll', null, [t]);
    if (!r.entries.length) v.appendChild(el('p', 'note', 'No updates recorded yet. A line is added each time a client of this constellation goes from one version to another.'));
    v.appendChild(el('p', 'note', r.total + ' recorded in all; the newest ' + r.entries.length + ' are shown.'));
    $('view').replaceChildren(v);
  } },
  log: { name: 'Log', section: 'MACHINE', needs: 'view', every: 8000, async draw() {
    const r = await api('GET', 'log');
    if (current !== 'log' || !r.log) return;
    const pre = el('pre', 'console', r.log.join('\n'));
    $('view').replaceChildren(pre); pre.scrollTop = pre.scrollHeight;
  } },
  gatekeeper: { name: 'Gatekeeper', section: 'OPTIONS', needs: 'admin', async draw() {
    const u = await api('GET', 'update');
    if (current !== 'gatekeeper') return;
    const v = el('div');
    const line = el('div', 'card row', null, [el('div', 'grow', null, [el('h3', '', 'Gatekeeper ' + (u.own || me.version)), el('div', u.outdated ? 'amber' : 'grey', u.outdated ? u.newest + (u.blocking === false ? ' is out. This version still works with it: nothing is held up.' : ' is out. Until it is updated Gatekeeper starts no server.') : 'Up to date.')])]);
    if (u.outdated) line.appendChild(button('Update Gatekeeper', () => act('update', {}, '', 'Update Gatekeeper now? It restarts itself; this page answers again in under a minute.')));
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
      });
      const link = el('a', '', "Minecraft's EULA"); link.href = 'https://aka.ms/MinecraftEULA'; link.target = '_blank'; link.rel = 'noopener noreferrer';
      v.appendChild(el('div', 'card', null, [el('h2', '', 'New server'), el('div', 'row', null, [name, kind, ver, mem]),
        el('label', 'row', null, [eula, el('span', '', 'I have read '), link, el('span', '', ' and I agree to it')]), el('div', 'row', null, [make]), said,
        el('p', 'note', 'Gatekeeper fetches the server\'s program from its makers and sets it up on the server machine. A server from a Forge installer or your own jar is made in Gatekeeper\'s own window.')]));
    }
    $('view').replaceChildren(v);
  } }
};

// One server (where Gatekeeper runs it itself): its state, console, backups and tasks.
function openServer(id, name) {
  current = 'server'; clearInterval(timer);
  $('subnav').replaceChildren(); $('title').textContent = name; menu(false);
  let next = 0;
  const stats = el('div', 'grey'), pre = el('pre', 'console'), line = el('input', 'grow'), extra = el('div');
  line.placeholder = 'Type a command (no slash needed) and press Enter';
  const send = async () => { const c = line.value.trim(); if (!c) return; line.value = ''; say(await api('POST', 'tools/command', { command: c }, id)); };
  line.addEventListener('keydown', e => { if (e.key === 'Enter') send(); });
  const v = el('div', '', null, [el('div', 'row', null, [button('← Servers', () => show('servers'), 'quiet')]), stats]);
  if (can('console')) { v.appendChild(pre); v.appendChild(el('div', 'row', null, [line, button('Send', send)])); }
  v.appendChild(extra);
  $('view').replaceChildren(v);
  let drewExtra = false;
  const tick = async () => {
    if (current !== 'server') return;
    const s = await api('GET', 'tools/server', null, id);
    if (current !== 'server' || !s.id) { if (s.message) say(s); return; }
    stats.className = s.phase === 'online' ? 'green' : s.running ? 'amber' : 'grey';
    stats.textContent = (s.phase === 'online' ? 'On' : s.phase === 'starting' ? 'Starting…' : s.phase === 'stopping' ? 'Shutting down…' : 'Off') + (s.running ? '   ·   up ' + Math.floor(s.uptime / 60) + ' min   ·   memory ' + s.memory_used + ' MB of ' + s.memory + '   ·   ' + s.players + ' playing' : '') + '   ·   port ' + s.port;
    if (can('manage') && !drewExtra) {
      drewExtra = true;
      const tasks = el('div', 'card', null, [el('h3', '', 'Backups and tasks'), el('div', 'note', s.backup_state || ((s.backups || []).length + ' backup(s) kept in ' + s.backup_dir)),
        el('div', 'row', null, [button('Back up now', () => act('tools/backup', {}, id))].concat((s.tasks || []).map((t, i) => button('Run: ' + t.name, () => act('tools/task', { index: i }, id), 'quiet'))))]);
      extra.replaceChildren(tasks, el('p', 'note', 'Settings, game settings and the list of tasks are changed in Gatekeeper\'s own window (Manage).'));
    }
    if (can('console')) {
      const c = await api('POST', 'tools/console', { after: next }, id);
      if (c.lines && c.next !== next) { if (c.next < next) pre.textContent += '\n---- started again ----\n'; pre.textContent += c.lines.join('\n') + (c.lines.length ? '\n' : ''); pre.scrollTop = pre.scrollHeight; next = c.next; }
    }
  };
  tick(); timer = setInterval(tick, 2500);
}

const SECTIONS = ['SERVERS', 'PEOPLE', 'CONSTELLATION', 'MACHINE', 'OPTIONS'];
// On a narrow screen the sections fold away behind a menu button; this opens or closes them.
function menu(open) { $('top').classList.toggle('open', open); $('menu').setAttribute('aria-expanded', open ? 'true' : 'false'); }
$('menu').addEventListener('click', () => menu(!$('top').classList.contains('open')));
document.addEventListener('keydown', e => { if (e.key === 'Escape') menu(false); });
document.addEventListener('click', e => { if (!$('top').contains(e.target)) menu(false); });
const titled = s => s[0] + s.slice(1).toLowerCase();
let mine = [], last = {}, watch = null;
const inSection = s => mine.filter(k => views[k].section === s);
function show(key) {
  current = key; clearInterval(timer); say(null);
  const sec = views[key].section, pages = inSection(sec);
  last[sec] = key;
  document.querySelectorAll('#nav button').forEach(b => b.classList.toggle('on', b.dataset.section === sec));
  $('title').textContent = titled(sec);
  $('menu-now').textContent = sec; menu(false);
  $('subnav').replaceChildren(...(pages.length > 1 ? pages.map(k => button(views[k].name, () => show(k), k === key ? 'on' : 'quiet')) : []));
  $('view').replaceChildren(el('p', 'note', 'Loading…'));
  views[key].draw();
  if (views[key].every) timer = setInterval(() => views[key].draw(), views[key].every);
}
function showSection(sec) { const pages = inSection(sec); if (pages.length) show(pages.includes(last[sec]) ? last[sec] : pages[0]); }
// What stands along the bottom, and the count on People: asked again every few seconds.
async function standing() {
  if (!me) return;
  if (can('access')) {
    const r = await api('GET', 'devices'), n = (r.requests || []).length, b = document.querySelector('#nav button[data-section=PEOPLE]');
    if (b) { const old = b.querySelector('.badge'); if (old) old.remove(); if (n) b.appendChild(el('span', 'badge', String(n))); }
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
  $('signin').classList.add('hidden'); $('app').classList.remove('hidden'); $('signout').classList.remove('hidden'); $('strip').classList.remove('hidden'); $('menu').classList.remove('hidden');
  $('whoami').textContent = me.user + (me.admin ? ' (admin)' : '');
  $('standing').textContent = 'Gatekeeper ' + me.version + '   ·   ' + me.gatekeeper + '   ·   ' + me.system;
  mine = Object.keys(views).filter(k => views[k].needs === 'admin' ? me.admin : can(views[k].needs));
  const nav = $('nav'); nav.replaceChildren();
  SECTIONS.forEach(s => {
    const has = inSection(s).length > 0, b = button(s, () => { if (has) showSection(s); }, has ? '' : 'off');
    b.dataset.section = s;
    if (!has) b.title = "Your account isn't allowed this section. Its permissions are set in Gatekeeper's window.";
    nav.appendChild(b);
  });
  if (mine.length) show(mine[0]); else { $('title').textContent = 'Gatekeeper'; $('view').replaceChildren(el('p', 'note', 'Your account is allowed nothing yet. Its permissions are set in Gatekeeper\'s window.')); }
  clearInterval(watch); standing(); watch = setInterval(standing, 6000);
}
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
$('signout').addEventListener('click', async () => { await ask('POST', 'logout', {}); showSignin(); });
(async () => { const r = await ask('GET', 'me'); if (r.user) start(r); else showSignin(); })();
