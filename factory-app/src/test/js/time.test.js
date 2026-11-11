process.env.TZ = 'Europe/Berlin';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const TIME_JS = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/time.js'), 'utf8');

function load() {
  let ready;
  const context = { window: {}, Date, isNaN, document: { addEventListener: (event, callback) => { ready = callback; } } };
  vm.runInNewContext(TIME_JS, context);
  return { window: context.window, ready };
}

function element(attributes, text) {
  return {
    title: '',
    textContent: text,
    getAttribute: name => attributes[name]
  };
}

test('local time keeps the three formats of the server', () => {
  const { window } = load();
  const date = new Date('2026-11-10T22:42:07Z');
  assert.equal(window.formatTime(date, 'timestamp', false), '2026-11-10 23:42:07');
  assert.equal(window.formatTime(date, 'clock', false), '23:42:07');
  assert.equal(window.formatTime(date, 'day', false), 'Nov 10, 23:42');
  assert.equal(window.formatTime(date, 'timestamp', true), '2026-11-10 22:42:07');
});

test('a time after local midnight moves to the next day and pads the hour, not the day', () => {
  const { window } = load();
  const date = new Date('2026-11-30T23:30:00Z');
  assert.equal(window.formatTime(date, 'day', false), 'Dec 1, 00:30');
  assert.equal(window.formatTime(date, 'timestamp', false), '2026-12-01 00:30:00');
  assert.equal(window.formatTime(new Date('2026-03-05T08:04:09Z'), 'day', true), 'Mar 5, 08:04');
});

test('times are rewritten to local time with the UTC time in the title; a relative time keeps its text', () => {
  const { window, ready } = load();
  const day = element({ datetime: '2026-11-10T22:42:00Z', 'data-local': 'day' }, 'Nov 10, 22:42 UTC');
  const ago = element({ datetime: '2026-11-10T22:42:00Z', 'data-local': 'ago' }, '5 min ago');
  const broken = element({ datetime: 'soon', 'data-local': 'clock' }, '22:42:00 UTC');
  window.localizeTimes({ querySelectorAll: () => [day, ago, broken] });

  assert.equal(day.textContent, 'Nov 10, 23:42');
  assert.equal(day.title, '2026-11-10 22:42:00 UTC');
  assert.equal(ago.textContent, '5 min ago');
  assert.equal(ago.title, '2026-11-10 23:42:00');
  assert.equal(broken.textContent, '22:42:00 UTC');
  assert.equal(typeof ready, 'function');
});
