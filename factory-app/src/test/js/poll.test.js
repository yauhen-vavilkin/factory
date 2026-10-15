const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

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
        querySelector: () => ({ getAttribute: key => attributes[key] }),
        querySelectorAll: () => []
      },
      setTimeout: callback => { tick = callback; },
      location: { reload: () => { reloads++; } },
      fetch: async () => ({ ok: true, json: async () => data })
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/poll.js'), 'utf8'), context);
    ready();
    tick();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(reloads, changed ? 1 : 0);
  }
});
