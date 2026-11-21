process.env.TZ = 'Europe/Berlin';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const TIME_JS = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/time.js'), 'utf8');

function load(root) {
  let ready;
  const timers = [];
  const document = { addEventListener: (event, callback) => { ready = callback; },
    querySelectorAll: selector => (root ? root.querySelectorAll(selector) : []) };
  const context = { window: {}, Date, isNaN, document,
    setInterval: (callback, delay) => timers.push({ callback, delay }) };
  vm.runInNewContext(TIME_JS, context);
  return { window: context.window, ready, timers };
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

test('a relative time says the words of the server for every span', () => {
  const { window } = load();
  const date = new Date('2026-11-10T12:00:00Z');
  const at = seconds => date.getTime() + seconds * 1000;
  assert.equal(window.agoText(date, at(59)), 'just now');
  assert.equal(window.agoText(date, at(60)), '1 min ago');
  assert.equal(window.agoText(date, at(59 * 60 + 59)), '59 min ago');
  assert.equal(window.agoText(date, at(3600)), '1 h ago');
  assert.equal(window.agoText(date, at(24 * 3600)), '1 day ago');
  assert.equal(window.agoText(date, at(3 * 24 * 3600)), '3 days ago');
  // A browser clock a little behind the server never shows a time in the future.
  assert.equal(window.agoText(date, at(-5)), 'just now');
});

test('an open page moves its relative times on every 30 seconds and leaves the other times alone', () => {
  const ago = element({ datetime: '2026-11-10T12:00:00Z', 'data-local': 'ago' }, 'just now');
  const broken = element({ datetime: 'soon', 'data-local': 'ago' }, 'just now');
  const selectors = [];
  const root = { querySelectorAll: selector => { selectors.push(selector); return selector.includes('ago') ? [ago, broken] : []; } };
  const { window, ready, timers } = load(root);
  ready();

  assert.equal(timers.length, 1);
  assert.equal(timers[0].delay, 30000);
  window.refreshAgo(root, new Date('2026-11-10T12:07:30Z').getTime());
  assert.equal(ago.textContent, '7 min ago');
  assert.equal(broken.textContent, 'just now');
  assert.ok(selectors.includes('time[data-local="ago"][datetime]'));
});
