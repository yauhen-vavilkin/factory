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
        if (current[i] !== baseline[i]) { location.reload(); return; }
      }
    });
  });
})();
