(function () {
  var btn = document.getElementById('theme-toggle');
  if (!btn) return;

  function isDark() {
    return document.documentElement.dataset.theme === 'dark';
  }

  function render() {
    var dark = isDark();
    btn.setAttribute('aria-pressed', String(dark));
    var label = btn.querySelector('[data-theme-label]');
    if (label) label.textContent = dark ? 'Light' : 'Dark';
  }

  render();

  btn.addEventListener('click', function () {
    var next = isDark() ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('theme', next); } catch (e) { /* storage unavailable */ }
    render();
    window.dispatchEvent(new CustomEvent('themechange'));
  });
})();
