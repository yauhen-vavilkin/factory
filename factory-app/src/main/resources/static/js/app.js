(function () {
  function initDialogs() {
    document.querySelectorAll('[data-dialog-open]').forEach(function (trigger) {
      trigger.addEventListener('click', function () {
        var dialog = document.getElementById(trigger.getAttribute('data-dialog-open'));
        if (dialog && typeof dialog.showModal === 'function') dialog.showModal();
      });
    });
    document.querySelectorAll('dialog [data-dialog-close]').forEach(function (closer) {
      closer.addEventListener('click', function () {
        var dialog = closer.closest('dialog');
        if (dialog) dialog.close();
      });
    });
  }

  function dismissAlerts() {
    document.querySelectorAll('.alert[data-autodismiss]').forEach(function (alert) {
      setTimeout(function () {
        alert.classList.add('alert-hiding');
        setTimeout(function () { alert.remove(); }, 300);
      }, 5000);
    });
  }

  function initSampleSelect() {
    document.querySelectorAll('select[data-sample-target]').forEach(function (select) {
      select.addEventListener('change', function () {
        var target = document.getElementById(select.getAttribute('data-sample-target'));
        var source = document.querySelector(
          'script[type="application/json"][data-sample="' + select.value + '"]');
        if (target && source) target.value = source.textContent.trim();
      });
    });
  }

  function initVersionTabs() {
    document.querySelectorAll('[data-version-tabs]').forEach(function (group) {
      var tabs = Array.prototype.slice.call(group.querySelectorAll('[data-version-target]'));
      function select(tab, focus) {
        tabs.forEach(function (other) {
          var selected = other === tab;
          other.setAttribute('aria-selected', String(selected));
          other.tabIndex = selected ? 0 : -1;
          var panel = document.getElementById(other.getAttribute('data-version-target'));
          if (panel) panel.hidden = !selected;
        });
        if (focus) tab.focus();
      }
      // Roving tabindex: only the selected tab is in the tab order at rest.
      tabs.forEach(function (tab) {
        tab.tabIndex = tab.getAttribute('aria-selected') === 'true' ? 0 : -1;
      });
      tabs.forEach(function (tab, index) {
        tab.addEventListener('click', function () { select(tab, false); });
        tab.addEventListener('keydown', function (e) {
          var next = null;
          if (e.key === 'ArrowLeft') next = tabs[(index - 1 + tabs.length) % tabs.length];
          else if (e.key === 'ArrowRight') next = tabs[(index + 1) % tabs.length];
          else if (e.key === 'Home') next = tabs[0];
          else if (e.key === 'End') next = tabs[tabs.length - 1];
          if (next) { e.preventDefault(); select(next, true); }
        });
      });
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    initDialogs();
    dismissAlerts();
    initSampleSelect();
    initVersionTabs();
  });
})();
