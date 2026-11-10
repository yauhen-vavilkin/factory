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
    initModelChoice();
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

  // A reload must not drop what the developer is doing: a selection, a picked effort or a half-typed key.
  function applyUpdate() {
    var notice = document.querySelector('[data-poll-notice]');
    var typedKey = document.querySelector('input[name="key"]');
    var busy = document.querySelector('input[name="taskId"]:checked') || document.querySelector('[data-effort-picked]')
      || (typedKey && typedKey.value.trim());
    if (notice && busy) {
      notice.hidden = false;
      return;
    }
    location.reload();
  }

  // Run forms: Effort offers only the chosen model's levels. A model change gives every Effort choice the new
  // model's default, except a level the developer picked that the new model also has.
  function initModelChoice() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-model-select]'), function (select) {
      var toggles = Array.prototype.slice.call(select.form.querySelectorAll('[data-effort-toggle]'));
      toggles.forEach(function (toggle) {
        toggle.addEventListener('change', function () { toggle.setAttribute('data-effort-picked', 'true'); });
      });
      select.addEventListener('change', function () {
        var chosen = select.options[select.selectedIndex];
        var efforts = chosen.getAttribute('data-efforts').split(' ');
        var preferred = chosen.getAttribute('data-default-effort');
        toggles.forEach(function (toggle) { showEfforts(toggle, efforts, preferred); });
      });
    });
  }

  function showEfforts(toggle, efforts, preferred) {
    var checked = toggle.querySelector('input:checked');
    var kept = toggle.getAttribute('data-effort-picked') === 'true' && checked && efforts.indexOf(checked.value) >= 0;
    var value = kept ? checked.value : preferred;
    var name = toggle.getAttribute('data-effort-name');
    while (toggle.firstChild) toggle.removeChild(toggle.firstChild);
    efforts.forEach(function (effort) {
      var label = document.createElement('label');
      var input = document.createElement('input');
      var text = document.createElement('span');
      input.type = 'radio';
      input.name = name;
      input.value = effort;
      input.checked = effort === value;
      text.textContent = effort;
      label.appendChild(input);
      label.appendChild(text);
      toggle.appendChild(label);
    });
  }

  // A red chip opens the dialog that says how to fix its task; without a script it links to the task page.
  // A selection button counts the checked boxes: "Run 2 tasks", "Fix 1 comment", disabled while none is checked.
  // The form names its checkbox field in data-selection, and the button's verb and noun.
  function initTaskSelection() {
    Array.prototype.forEach.call(document.querySelectorAll('[data-selection]'), initSelection);
  }

  function initSelection(form) {
    var boxes = Array.prototype.slice.call(form.querySelectorAll('input[name="' + form.getAttribute('data-selection') + '"]'));
    var button = form.querySelector('[data-run-selected]');
    var verb = form.getAttribute('data-selection-verb') || 'Run';
    var noun = form.getAttribute('data-selection-noun') || 'task';
    function refresh() {
      var checked = boxes.filter(function (b) { return b.checked; }).length;
      if (!button) return;
      button.textContent = checked ? verb + ' ' + checked + ' ' + noun + (checked === 1 ? '' : 's') : verb;
      button.disabled = checked === 0;
    }
    boxes.forEach(function (b) { b.addEventListener('change', refresh); });
    refresh();
  }
})();
