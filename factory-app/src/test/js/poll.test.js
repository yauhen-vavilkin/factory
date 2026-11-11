const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const POLL_JS = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/poll.js'), 'utf8');

test('audit and artifact growth reload an unchanged running step', async () => {
  for (const changed of [null, 'auditTrail', 'artifacts']) {
    let ready, tick, reloads = 0;
    const attributes = {
      'data-poll-url': '/api/executions/id',
      'data-poll-fields': 'execution.status,execution.currentStepIndex,auditTrail.length,artifacts.length',
      'data-poll-baseline': 'RUNNING,3,1,1'
    };
    const data = { execution: { status: 'RUNNING', currentStepIndex: 3 }, auditTrail: [{}], artifacts: [{}] };
    if (changed) data[changed].push({});
    const context = {
      window: {},
      document: {
        hidden: false,
        addEventListener: (event, callback) => { ready = callback; },
        querySelector: selector => selector === '[data-poll-url]' ? { getAttribute: key => attributes[key] } : null,
        querySelectorAll: () => []
      },
      setTimeout: callback => { tick = callback; },
      location: { reload: () => { reloads++; } },
      fetch: async () => ({ ok: true, json: async () => data })
    };
    vm.runInNewContext(POLL_JS, context);
    ready();
    tick();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(reloads, changed ? 1 : 0);
  }
});

test('a hidden run page still polls, slower, and reloads when the round ends; showing it polls at once', async () => {
  const listeners = {};
  const delays = [];
  let tick, fetches = 0, reloads = 0;
  const attributes = {
    'data-poll-url': '/api/executions/id',
    'data-poll-fields': 'execution.status',
    'data-poll-baseline': 'RUNNING'
  };
  const document = {
    hidden: true,
    addEventListener: (event, callback) => { listeners[event] = callback; },
    querySelector: selector => selector === '[data-poll-url]' ? { getAttribute: key => attributes[key] } : null,
    querySelectorAll: () => []
  };
  let status = 'RUNNING';
  vm.runInNewContext(POLL_JS, {
    window: {},
    document,
    setTimeout: (callback, ms) => { tick = callback; delays.push(ms); return delays.length; },
    clearTimeout: () => {},
    location: { reload: () => { reloads++; } },
    fetch: async () => { fetches++; return { ok: true, json: async () => ({ execution: { status } }) }; }
  });
  listeners.DOMContentLoaded();
  assert.equal(delays.at(-1), 30000);

  tick();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fetches, 1);
  assert.equal(reloads, 0);

  status = 'AWAITING_HITL';
  document.hidden = false;
  listeners.visibilitychange();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(fetches, 2);
  assert.equal(reloads, 1);
});

test('a picked effort holds the live reload and shows the notice instead', async () => {
  let ready, tick, reloads = 0;
  const notice = { hidden: true };
  const attributes = { 'data-poll-url': '/tasks/poll', 'data-poll-fields': 'revision', 'data-poll-baseline': 'old' };
  const elements = {
    '[data-poll-url]': { getAttribute: key => attributes[key] },
    '[data-poll-notice]': notice,
    '[data-effort-picked]': {}
  };
  vm.runInNewContext(POLL_JS, {
    window: {},
    document: {
      hidden: false,
      addEventListener: (event, callback) => { ready = callback; },
      querySelector: selector => elements[selector] || null,
      querySelectorAll: () => []
    },
    setTimeout: callback => { tick = callback; },
    location: { reload: () => { reloads++; } },
    fetch: async () => ({ ok: true, json: async () => ({ revision: 'new' }) })
  });
  ready();
  tick();
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(reloads, 0);
  assert.equal(notice.hidden, false);
});

test('text typed for the agent holds the task page reload; an empty text field does not', async () => {
  for (const typed of ['Keep the public API.', '  ']) {
    let ready, tick, reloads = 0;
    const notice = { hidden: true };
    const attributes = { 'data-poll-url': '/tasks/id/poll', 'data-poll-fields': 'revision', 'data-poll-baseline': 'a1' };
    const elements = { '[data-poll-url]': { getAttribute: key => attributes[key] }, '[data-poll-notice]': notice };
    vm.runInNewContext(POLL_JS, {
      window: {},
      document: {
        hidden: false,
        addEventListener: (event, callback) => { if (event === 'DOMContentLoaded') ready = callback; },
        querySelector: selector => elements[selector] || null,
        querySelectorAll: selector => selector === 'textarea' ? [{ value: typed }] : []
      },
      setTimeout: callback => { tick = callback; },
      location: { reload: () => { reloads++; } },
      fetch: async () => ({ ok: true, json: async () => ({ revision: 'b2' }) })
    });
    ready();
    tick();
    await new Promise(resolve => setImmediate(resolve));

    assert.equal(reloads, typed.trim() ? 0 : 1);
    assert.equal(notice.hidden, !typed.trim());
  }
});

test('a model the developer picked holds the reload too', async () => {
  let ready, tick, reloads = 0;
  const notice = { hidden: true };
  const attributes = { 'data-poll-url': '/tasks/poll', 'data-poll-fields': 'revision', 'data-poll-baseline': 'old' };
  const elements = {
    '[data-poll-url]': { getAttribute: key => attributes[key] },
    '[data-poll-notice]': notice,
    '[data-model-picked]': {}
  };
  vm.runInNewContext(POLL_JS, {
    window: {},
    document: {
      hidden: false,
      addEventListener: (event, callback) => { if (event === 'DOMContentLoaded') ready = callback; },
      querySelector: selector => elements[selector] || null,
      querySelectorAll: () => []
    },
    setTimeout: callback => { tick = callback; },
    location: { reload: () => { reloads++; } },
    fetch: async () => ({ ok: true, json: async () => ({ revision: 'new' }) })
  });
  ready();
  tick();
  await new Promise(resolve => setImmediate(resolve));

  assert.equal(reloads, 0);
  assert.equal(notice.hidden, false);
});

test('a PR comment the developer left out holds the run page reload', async () => {
  for (const leftOut of [true, false]) {
    let ready, tick, reloads = 0;
    const notice = { hidden: true };
    const attributes = { 'data-poll-url': '/executions/id/poll', 'data-poll-fields': 'revision', 'data-poll-baseline': 'a1' };
    const elements = { '[data-poll-url]': { getAttribute: key => attributes[key] }, '[data-poll-notice]': notice };
    if (leftOut) elements['input[name="selectedIds"]:not(:checked)'] = {};
    vm.runInNewContext(POLL_JS, {
      window: {},
      document: {
        hidden: false,
        addEventListener: (event, callback) => { if (event === 'DOMContentLoaded') ready = callback; },
        querySelector: selector => elements[selector] || null,
        querySelectorAll: () => []
      },
      setTimeout: callback => { tick = callback; },
      location: { reload: () => { reloads++; } },
      fetch: async () => ({ ok: true, json: async () => ({ revision: 'b2' }) })
    });
    ready();
    tick();
    await new Promise(resolve => setImmediate(resolve));

    assert.equal(reloads, leftOut ? 0 : 1);
    assert.equal(notice.hidden, !leftOut);
  }
});

function selectionForm(field, attributes, count) {
  function box(checked) {
    const listeners = {};
    return { checked, addEventListener: (event, callback) => { listeners[event] = callback; },
      fire: event => listeners[event]() };
  }
  const boxes = Array.from({ length: count }, () => box(false));
  const button = { textContent: '', disabled: false };
  const form = {
    getAttribute: key => key === 'data-selection' ? field : (attributes[key] === undefined ? null : attributes[key]),
    querySelectorAll: selector => selector === 'input[name="' + field + '"]' ? boxes : [],
    querySelector: selector => selector === '[data-run-selected]' ? button : null
  };
  let ready;
  const shown = [];
  vm.runInNewContext(POLL_JS, {
    window: { addEventListener: (event, callback) => { if (event === 'pageshow') shown.push(callback); } },
    document: {
      addEventListener: (event, callback) => { ready = callback; },
      querySelector: () => null,
      querySelectorAll: selector => selector === '[data-selection]' ? [form] : []
    }
  });
  ready();
  return { boxes, button, pageshow: () => shown.forEach(callback => callback()) };
}

test('Run counts the ticks the browser put back when the page is shown again', () => {
  const { boxes, button, pageshow } = selectionForm('taskId', {}, 2);

  boxes[1].checked = true;
  pageshow();

  assert.equal(button.textContent, 'Run 1 task');
  assert.equal(button.disabled, false);
});

test('Run counts the checked tasks and is disabled while none is checked', () => {
  const { boxes, button } = selectionForm('taskId', {}, 3);

  assert.equal(button.textContent, 'Run');
  assert.equal(button.disabled, true);

  boxes[0].checked = true;
  boxes[0].fire('change');
  assert.equal(button.textContent, 'Run 1 task');
  assert.equal(button.disabled, false);

  boxes[2].checked = true;
  boxes[2].fire('change');
  assert.equal(button.textContent, 'Run 2 tasks');
});

test('Fix counts the checked PR comments', () => {
  const { boxes, button } = selectionForm('selectedIds',
    { 'data-selection-verb': 'Fix', 'data-selection-noun': 'comment' }, 2);
  assert.equal(button.textContent, 'Fix');
  assert.equal(button.disabled, true);

  boxes.forEach(b => { b.checked = true; b.fire('change'); });
  assert.equal(button.textContent, 'Fix 2 comments');
  assert.equal(button.disabled, false);
});

test('Effort offers the chosen model levels and a model change keeps only a level the developer picked', () => {
  function node(tag) {
    return { tag, children: [], attributes: {}, listeners: {},
      get firstChild() { return this.children[0] || null; },
      appendChild(child) { this.children.push(child); },
      removeChild(child) { this.children.splice(this.children.indexOf(child), 1); },
      getAttribute(key) { return key in this.attributes ? this.attributes[key] : null; },
      setAttribute(key, value) { this.attributes[key] = value; },
      addEventListener(event, callback) { this.listeners[event] = callback; } };
  }
  function input(label) { return label.children[0]; }
  function toggle(name, efforts, checked) {
    const element = node('div');
    element.attributes['data-effort-name'] = name;
    element.querySelector = selector => selector === 'input:checked'
      ? element.children.map(input).find(i => i.checked) || null : null;
    efforts.forEach(effort => {
      const label = node('label');
      label.appendChild(Object.assign(node('input'), { name, value: effort, checked: effort === checked }));
      element.appendChild(label);
    });
    return element;
  }
  function option(value, efforts, preferred) {
    const attributes = { 'data-efforts': efforts, 'data-default-effort': preferred };
    return { value, getAttribute: key => attributes[key] };
  }
  const five = ['low', 'medium', 'high', 'xhigh', 'max'];
  const untouched = toggle('effort-a', five, 'high');
  const pickedLow = toggle('effort-b', five, 'low');
  const pickedMedium = toggle('effort-c', five, 'medium');
  const toggles = [untouched, pickedLow, pickedMedium];
  const select = { selectedIndex: 0, addEventListener(event, callback) { this.change = callback; },
    setAttribute(key, value) { this[key] = value; },
    options: [option('codemie-sonnet-5.5', five.join(' '), 'high'), option('glm-5.3', 'low high max', 'max')],
    form: { querySelectorAll: selector => selector === '[data-effort-toggle]' ? toggles : [] } };
  let ready;
  vm.runInNewContext(POLL_JS, {
    window: {},
    document: {
      addEventListener: (event, callback) => { ready = callback; },
      querySelector: () => null,
      querySelectorAll: selector => selector === '[data-model-select]' ? [select] : [],
      createElement: node
    }
  });
  ready();
  pickedLow.listeners.change();
  pickedMedium.listeners.change();

  select.selectedIndex = 1;
  select.change();
  assert.equal(select['data-model-picked'], 'true');

  for (const [element, name] of [[untouched, 'effort-a'], [pickedLow, 'effort-b'], [pickedMedium, 'effort-c']]) {
    assert.deepEqual(element.children.map(label => input(label).value), ['low', 'high', 'max']);
    assert.ok(element.children.every(label => input(label).name === name && input(label).type === 'radio'));
    assert.deepEqual(element.children.map(label => label.children[1].textContent), ['low', 'high', 'max']);
  }
  const checked = element => element.children.map(input).filter(i => i.checked).map(i => i.value);
  assert.deepEqual(checked(untouched), ['max']);
  assert.deepEqual(checked(pickedLow), ['low']);
  assert.deepEqual(checked(pickedMedium), ['max']);
});

test('a ticking time uses the same words as the server: s, min, h and min, then d and h', () => {
  const window = {};
  vm.runInNewContext(POLL_JS, { window, document: { addEventListener: () => {} } });
  assert.equal(window.formatDuration(9), '9 s');
  assert.equal(window.formatDuration(59), '59 s');
  assert.equal(window.formatDuration(60), '1 min');
  assert.equal(window.formatDuration(1209), '20 min');
  assert.equal(window.formatDuration(7205), '2 h');
  assert.equal(window.formatDuration(4830), '1 h 20 min');
  assert.equal(window.formatDuration(86399), '23 h 59 min');
  assert.equal(window.formatDuration(86400), '1 d');
  assert.equal(window.formatDuration(451500), '5 d 5 h');
  assert.equal(window.formatDuration(-3), '0 s');
});

function storage() {
  const items = new Map();
  return {
    items,
    getItem: key => (items.has(key) ? items.get(key) : null),
    setItem: (key, value) => items.set(key, String(value)),
    removeItem: key => items.delete(key)
  };
}

// A run page with one Activity list and two <details>: "Round 1 · PR feedback" and "Full transcript".
function runPage(sessionStorage, pathname, { open, expanded, scrollY }) {
  let ready, tick, reloads = 0;
  const clicks = [];
  const scrolls = [];
  const details = ['Round 1 · PR feedback', 'Full transcript'].map((text, index) => ({
    id: '', open: open[index], querySelector: selector => (selector === 'summary' ? { textContent: '\n  ' + text + ' ' } : null)
  }));
  const button = { click: () => clicks.push('show earlier') };
  const list = {
    querySelector: selector => {
      if (selector === '[data-earlier]') return {};
      return selector === '[data-show-earlier]' && !expanded ? button : null;
    }
  };
  const attributes = { 'data-poll-url': pathname + '/poll', 'data-poll-fields': 'revision', 'data-poll-baseline': 'a1' };
  const window = { scrollY, scrollTo: (x, y) => scrolls.push(y) };
  vm.runInNewContext(POLL_JS, {
    window,
    sessionStorage,
    document: {
      hidden: false,
      addEventListener: (event, callback) => { if (event === 'DOMContentLoaded') ready = callback; },
      querySelector: selector => (selector === '[data-poll-url]' ? { getAttribute: key => attributes[key] } : null),
      querySelectorAll: selector => (selector === 'details' ? details : selector === '.run-activity' ? [list] : [])
    },
    setTimeout: callback => { tick = callback; },
    location: { pathname, search: '', reload: () => { reloads++; } },
    fetch: async () => ({ ok: true, json: async () => ({ revision: 'a2' }) })
  });
  return {
    details, clicks, scrolls,
    load: () => ready(),
    poll: async () => { tick(); await new Promise(resolve => setImmediate(resolve)); return reloads; }
  };
}

test('a self-reload keeps the scroll position, the open details and the earlier Activity rows, once', async () => {
  const session = storage();
  const before = runPage(session, '/executions/r1', { open: [false, true], expanded: true, scrollY: 640 });
  before.load();
  assert.equal(await before.poll(), 1);
  assert.equal(session.items.size, 1);

  const after = runPage(session, '/executions/r1', { open: [true, false], expanded: false, scrollY: 0 });
  after.load();
  assert.deepEqual(after.details.map(details => details.open), [false, true]);
  assert.deepEqual(after.clicks, ['show earlier']);
  assert.deepEqual(after.scrolls, [640]);
  // The saved place is used once: the next visit starts as the server renders it.
  assert.equal(session.items.size, 0);
  const next = runPage(session, '/executions/r1', { open: [true, false], expanded: false, scrollY: 0 });
  next.load();
  assert.deepEqual(next.details.map(details => details.open), [true, false]);
  assert.deepEqual(next.scrolls, []);
});

test('a saved place belongs to its URL and goes stale; a page without storage still reloads', async () => {
  const session = storage();
  const before = runPage(session, '/executions/r1', { open: [true, true], expanded: true, scrollY: 300 });
  before.load();
  await before.poll();

  const other = runPage(session, '/executions/r2', { open: [false, false], expanded: false, scrollY: 0 });
  other.load();
  assert.deepEqual(other.scrolls, []);
  assert.deepEqual(other.clicks, []);

  const key = [...session.items.keys()][0];
  session.setItem(key, JSON.stringify({ ...JSON.parse(session.getItem(key)), at: Date.now() - 60000 }));
  const late = runPage(session, '/executions/r1', { open: [false, false], expanded: false, scrollY: 0 });
  late.load();
  assert.deepEqual(late.scrolls, []);
  assert.equal(session.items.size, 0);

  const blocked = { getItem: () => { throw new Error('denied'); }, setItem: () => { throw new Error('denied'); } };
  const noStorage = runPage(blocked, '/executions/r1', { open: [true, false], expanded: true, scrollY: 10 });
  noStorage.load();
  assert.equal(await noStorage.poll(), 1);
});
