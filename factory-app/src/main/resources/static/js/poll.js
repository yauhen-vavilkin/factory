(function () {
  function startPolling(url, ms, onData) {
    var interval = ms;
    function schedule() { setTimeout(tick, interval); }
    function tick() {
      if (document.hidden) { schedule(); return; }
      fetch(url, { headers: { Accept: 'application/json' } })
        .then(function (r) { if (!r.ok) throw new Error('status ' + r.status); return r.json(); })
        .then(function (data) { interval = ms; onData(data); schedule(); })
        .catch(function () { interval = Math.min(interval * 2, 60000); schedule(); });
    }
    schedule();
  }
  window.startPolling = startPolling;

  function resolve(obj, path) {
    return path.split('.').reduce(function (acc, key) {
      return (acc === null || acc === undefined) ? undefined : acc[key];
    }, obj);
  }

  document.addEventListener('DOMContentLoaded', function () {
    initTaskSelection();
    function formatDuration(seconds) {
      var safe = Math.max(0, seconds);
      if (safe < 60) return safe + 's';
      var minutes = Math.floor(safe / 60);
      var remainingSeconds = safe % 60;
      if (minutes < 60) return minutes + 'm' + (remainingSeconds ? ' ' + remainingSeconds + 's' : '');
      var hours = Math.floor(minutes / 60);
      var remainingMinutes = minutes % 60;
      return hours + 'h' + (remainingMinutes ? ' ' + remainingMinutes + 'm' : '');
    }
    var clocks = document.querySelectorAll('[data-elapsed-start]');
    if (clocks.length) setInterval(function () {
      clocks.forEach(function (clock) {
        var start = Date.parse(clock.getAttribute('data-elapsed-start'));
        if (Number.isFinite(start)) clock.textContent = formatDuration(Math.floor((Date.now() - start) / 1000));
      });
    }, 1000);
    var el = document.querySelector('[data-poll-url]');
    if (!el) return;
    var url = el.getAttribute('data-poll-url');
    var fields = (el.getAttribute('data-poll-fields') || '').split(',')
      .map(function (f) { return f.trim(); }).filter(Boolean);
    if (!fields.length) return;

    // Baseline comes from the server-rendered page, not the first poll response:
    // a change landing between render and the first poll must still reload.
    var baselineAttr = el.getAttribute('data-poll-baseline');
    var baseline = baselineAttr === null ? null : baselineAttr.split(',');
    startPolling(url, 5000, function (data) {
      var current = fields.map(function (f) { return String(resolve(data, f)); });
      if (baseline === null) { baseline = current; return; }
      for (var i = 0; i < current.length; i++) {
        if (current[i] !== baseline[i]) { applyUpdate(); return; }
      }
    });
  });

  // A reload must not drop what the developer is doing: a selection or a half-typed key.
  function applyUpdate() {
    var notice = document.querySelector('[data-poll-notice]');
    var typedKey = document.querySelector('input[name="key"]');
    if (notice && (document.querySelector('input[name="taskId"]:checked') || (typedKey && typedKey.value.trim()))) {
      notice.hidden = false;
      return;
    }
    location.reload();
  }

  function initTaskSelection() {
    var form = document.querySelector('[data-task-selection]');
    if (!form) return;
    var rows = Array.prototype.slice.call(form.querySelectorAll('[data-status]'));
    var chips = Array.prototype.slice.call(form.querySelectorAll('[data-status-chip]'));
    var counter = form.querySelector('[data-selected-count]');
    var shownCount = form.querySelector('[data-available-count]');
    var notEligible = form.querySelector('[data-not-eligible]');
    var notEligibleCount = form.querySelector('[data-not-eligible-count]');
    var filterEmpty = form.querySelector('[data-filter-empty]');
    var runButton = form.querySelector('[data-run-selected]');
    var selectAll = form.querySelector('[data-select-all]');
    var storageKey = 'myTasks.statuses';

    function box(row) { return row.querySelector('input[name="taskId"]'); }
    function shownBoxes() {
      return rows.filter(function (row) { return !row.hidden && box(row); }).map(box);
    }
    function refresh() {
      var boxes = shownBoxes();
      var checked = boxes.filter(function (b) { return b.checked; }).length;
      if (counter) counter.textContent = boxes.length ? checked + ' of ' + boxes.length + ' selected' : '';
      if (runButton) {
        runButton.textContent = checked ? 'Run ' + checked + (checked === 1 ? ' task' : ' tasks') : 'Run';
        runButton.disabled = checked === 0;
      }
      if (selectAll) {
        selectAll.disabled = boxes.length === 0;
        selectAll.checked = checked > 0 && checked === boxes.length;
        selectAll.indeterminate = checked > 0 && checked < boxes.length;
      }
    }
    // A hidden row never runs: it is unchecked and disabled until its status is shown again.
    // Available counts only shown rows that can run, the same rule as the server render.
    function applyFilter() {
      var pressed = chips.filter(function (chip) { return chip.getAttribute('aria-pressed') === 'true'; })
        .map(statusOf);
      var shown = 0, runnable = 0;
      rows.forEach(function (row) {
        var visible = !chips.length || pressed.indexOf(row.getAttribute('data-status')) >= 0;
        row.hidden = !visible;
        var b = box(row);
        if (b) {
          if (!visible) b.checked = false;
          b.disabled = !visible;
        }
        if (visible) {
          shown++;
          if (b) runnable++;
        }
      });
      if (shownCount) shownCount.textContent = runnable;
      if (notEligibleCount) notEligibleCount.textContent = shown - runnable;
      if (notEligible) notEligible.hidden = shown === runnable;
      if (filterEmpty) filterEmpty.hidden = !(rows.length && shown === 0);
      refresh();
    }
    function statusOf(chip) { return chip.getAttribute('data-status-chip'); }
    function remembered() {
      try {
        var saved = JSON.parse(sessionStorage.getItem(storageKey));
        return saved && Array.isArray(saved.pressed) && Array.isArray(saved.known) ? saved : null;
      } catch (e) { return null; }
    }
    // Statuses first seen after the last toggle keep the server default.
    var saved = remembered();
    if (saved) chips.forEach(function (chip) {
      if (saved.known.indexOf(statusOf(chip)) >= 0)
        chip.setAttribute('aria-pressed', saved.pressed.indexOf(statusOf(chip)) >= 0 ? 'true' : 'false');
    });
    chips.forEach(function (chip) {
      chip.addEventListener('click', function () {
        chip.setAttribute('aria-pressed', chip.getAttribute('aria-pressed') === 'true' ? 'false' : 'true');
        try {
          sessionStorage.setItem(storageKey, JSON.stringify({
            pressed: chips.filter(function (c) { return c.getAttribute('aria-pressed') === 'true'; }).map(statusOf),
            known: chips.map(statusOf)
          }));
        } catch (e) { /* the filter still works for this page */ }
        applyFilter();
      });
    });
    rows.forEach(function (row) {
      var b = box(row);
      if (b) b.addEventListener('change', refresh);
    });
    if (selectAll) selectAll.addEventListener('change', function () {
      shownBoxes().forEach(function (b) { b.checked = selectAll.checked; });
      refresh();
    });
    applyFilter();
  }
})();
