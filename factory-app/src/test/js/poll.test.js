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

test('Available counts only shown tasks that can run', () => {
  function element(attributes, extra) {
    const listeners = {};
    return Object.assign({
      hidden: false,
      getAttribute: key => key in attributes ? attributes[key] : null,
      setAttribute: (key, value) => { attributes[key] = value; },
      addEventListener: (event, callback) => { listeners[event] = callback; },
      fire: event => listeners[event](),
      querySelector: () => null
    }, extra);
  }
  function row(status, runnable) {
    const box = runnable ? element({}, { checked: false, disabled: false }) : null;
    return element({ 'data-status': status }, { querySelector: () => box });
  }
  const rows = [row('Open', false), row('Open', false), row('Open', false), row('In Review', true)];
  const chips = [element({ 'data-status-chip': 'Open', 'aria-pressed': 'true' }),
    element({ 'data-status-chip': 'In Review', 'aria-pressed': 'false' })];
  const parts = {
    '[data-available-count]': element({}, { textContent: '' }),
    '[data-not-eligible]': element({}),
    '[data-not-eligible-count]': element({}, { textContent: '' }),
    '[data-run-selected]': element({}, { textContent: 'Run', disabled: false }),
    '[data-select-all]': element({}, { checked: false, disabled: false })
  };
  const form = {
    querySelectorAll: selector => selector === '[data-status]' ? rows : selector === '[data-status-chip]' ? chips : [],
    querySelector: selector => parts[selector] || null
  };
  let ready;
  vm.runInNewContext(POLL_JS, {
    window: {},
    document: {
      addEventListener: (event, callback) => { ready = callback; },
      querySelector: selector => selector === '[data-task-selection]' ? form : null,
      querySelectorAll: () => []
    }
  });
  ready();

  assert.equal(String(parts['[data-available-count]'].textContent), '0');
  assert.equal(String(parts['[data-not-eligible-count]'].textContent), '3');
  assert.equal(parts['[data-not-eligible]'].hidden, false);
  assert.equal(parts['[data-run-selected]'].disabled, true);
  assert.equal(parts['[data-select-all]'].disabled, true);

  chips[1].fire('click');
  assert.equal(String(parts['[data-available-count]'].textContent), '1');
  assert.equal(String(parts['[data-not-eligible-count]'].textContent), '3');
  assert.equal(parts['[data-select-all]'].disabled, false);

  chips[0].fire('click');
  assert.equal(String(parts['[data-available-count]'].textContent), '1');
  assert.equal(parts['[data-not-eligible]'].hidden, true);
});
