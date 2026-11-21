(function () {
  var btn = document.getElementById('menu-toggle');
  if (!btn) return;
  var root = document.documentElement;

  function isCollapsed() {
    return root.dataset.menu === 'collapsed';
  }

  function render() {
    var collapsed = isCollapsed();
    btn.setAttribute('aria-expanded', String(!collapsed));
    btn.setAttribute('aria-label', collapsed ? 'Open menu' : 'Close menu');
  }

  render();

  btn.addEventListener('click', function () {
    if (isCollapsed()) delete root.dataset.menu;
    else root.dataset.menu = 'collapsed';
    try { localStorage.setItem('menu', isCollapsed() ? 'collapsed' : 'open'); } catch (e) { /* storage unavailable */ }
    render();
  });
})();
