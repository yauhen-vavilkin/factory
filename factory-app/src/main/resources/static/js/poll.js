(function () {
  // A hidden page still polls, every 30 s, so it reaches the finished state by itself; it polls at once
  // when it is shown again.
  var HIDDEN_MS = 30000;
  function startPolling(url, ms, onData) {
    var interval = ms;
    var timer = null;
    var busy = false;
    function schedule() {
      timer = setTimeout(tick, document.hidden ? Math.max(interval, HIDDEN_MS) : interval);
    }
    function tick() {
      if (busy) return;
      busy = true;
      fetch(url, { headers: { Accept: 'application/json' } })
        .then(function (r) { if (!r.ok) throw new Error('status ' + r.status); return r.json(); })
        .then(function (data) { interval = ms; busy = false; onData(data); schedule(); })
        .catch(function () { interval = Math.min(interval * 2, 60000); busy = false; schedule(); });
    }
    document.addEventListener('visibilitychange', function () {
      if (document.hidden || busy) return;
      clearTimeout(timer);
      tick();
    });
    schedule();
  }
  window.startPolling = startPolling;

  // The same words as UiFormat.duration on the server, so a ticking time does not jump on its first tick.
  function formatDuration(seconds) {
    var safe = Math.max(0, seconds);
    if (safe < 60) return safe + ' s';
    var minutes = Math.floor(safe / 60);
    if (minutes < 60) return minutes + ' min';
    var remainingMinutes = minutes % 60;
    return Math.floor(minutes / 60) + ' h' + (remainingMinutes ? ' ' + remainingMinutes + ' min' : '');
  }
  window.formatDuration = formatDuration;

  function resolve(obj, path) {
    return path.split('.').reduce(function (acc, key) {
      return (acc === null || acc === undefined) ? undefined : acc[key];
    }, obj);
  }

  document.addEventListener('DOMContentLoaded', function () {
    restoreView();
    initTaskSelection();
    initModelChoice();
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

  // A reload must not drop what the developer is doing: a selection, a PR comment left out, a picked model or
  // effort, a half-typed key or text typed for the agent.
  function applyUpdate() {
    var notice = document.querySelector('[data-poll-notice]');
    var typedKey = document.querySelector('input[name="key"]');
    var typedText = Array.prototype.some.call(document.querySelectorAll('textarea'),
      function (textarea) { return textarea.value.trim(); });
    var busy = document.querySelector('input[name="taskId"]:checked') || document.querySelector('[data-effort-picked]')
      || document.querySelector('input[name="selectedIds"]:not(:checked)')
      || document.querySelector('[data-model-picked]') || (typedKey && typedKey.value.trim()) || typedText;
    if (notice && busy) {
      notice.hidden = false;
      return;
    }
    saveView();
    location.reload();
  }

  // A self-reload keeps the developer's place too: the scroll position, the open <details> and the Activity rows
  // shown with "Show N earlier". They are saved for this URL just before the reload and read once after it, so
  // a later visit to the page starts from the top as usual.
  var VIEW_KEY = 'factory.view:';
  var VIEW_MAX_AGE_MS = 10000;

  function viewKey() {
    return VIEW_KEY + location.pathname + location.search;
  }

  // A <details> is known by its id or its summary text, with a count for repeated texts.
  function namedDetails() {
    var seen = {};
    return Array.prototype.map.call(document.querySelectorAll('details'), function (details) {
      var summary = details.querySelector('summary');
      var name = details.id || (summary ? summary.textContent.replace(/\s+/g, ' ').trim() : '');
      seen[name] = (seen[name] || 0) + 1;
      return { element: details, name: name + '#' + seen[name] };
    });
  }

  function saveView() {
    var open = {};
    namedDetails().forEach(function (entry) { open[entry.name] = entry.element.open; });
    var earlier = [];
    Array.prototype.forEach.call(document.querySelectorAll('.run-activity'), function (list, index) {
      if (list.querySelector('[data-earlier]') && !list.querySelector('[data-show-earlier]')) earlier.push(index);
    });
    try {
      sessionStorage.setItem(viewKey(), JSON.stringify({ at: Date.now(), scrollY: window.scrollY || 0,
        open: open, earlier: earlier }));
    } catch (e) { /* Storage is off: the page reloads at the top. */ }
  }

  function restoreView() {
    var saved;
    try {
      saved = JSON.parse(sessionStorage.getItem(viewKey()));
      sessionStorage.removeItem(viewKey());
    } catch (e) { return; }
    if (!saved || !(Date.now() - saved.at < VIEW_MAX_AGE_MS)) return;
    namedDetails().forEach(function (entry) {
      if (Object.prototype.hasOwnProperty.call(saved.open, entry.name)) entry.element.open = saved.open[entry.name];
    });
    var lists = document.querySelectorAll('.run-activity');
    saved.earlier.forEach(function (index) {
      var button = lists[index] && lists[index].querySelector('[data-show-earlier]');
      if (button) button.click();
    });
    window.scrollTo(0, saved.scrollY);
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
        select.setAttribute('data-model-picked', 'true');
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
