(function () {
  document.addEventListener('DOMContentLoaded', function () {
    var dialog = document.getElementById('confirm-dialog');
    var supported = !!dialog && typeof dialog.showModal === 'function';
    var pending = null;

    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        var message = form.getAttribute('data-confirm');
        if (!supported) {
          if (!window.confirm(message)) e.preventDefault();
          return;
        }
        e.preventDefault();
        pending = form;
        var msg = dialog.querySelector('[data-confirm-message]');
        if (msg) msg.textContent = message;
        dialog.showModal();
      });
    });

    if (!supported) return;
    var ok = dialog.querySelector('[data-confirm-ok]');
    var cancel = dialog.querySelector('[data-confirm-cancel]');
    if (ok) ok.addEventListener('click', function () {
      dialog.close();
      if (pending) { var f = pending; pending = null; f.submit(); }
    });
    if (cancel) cancel.addEventListener('click', function () { dialog.close(); pending = null; });
    dialog.addEventListener('cancel', function () { pending = null; });
  });
})();
