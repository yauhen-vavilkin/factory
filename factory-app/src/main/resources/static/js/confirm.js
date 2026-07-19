(function () {
  document.addEventListener('DOMContentLoaded', function () {
    var dialog = document.getElementById('confirm-dialog');
    var supported = !!dialog && typeof dialog.showModal === 'function';
    var pending = null;

    function confirmThen(message, action) {
      var msg = dialog.querySelector('[data-confirm-message]');
      if (msg) msg.textContent = message;
      pending = action;
      dialog.showModal();
    }

    // Form-level confirm: any submission of the form is guarded.
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
      form.addEventListener('submit', function (e) {
        var message = form.getAttribute('data-confirm');
        if (!supported) {
          if (!window.confirm(message)) e.preventDefault();
          return;
        }
        e.preventDefault();
        confirmThen(message, function () { form.submit(); });
      });
    });

    // Control-level confirm: guards only this submitter, preserving its name/value
    // (so a multi-button form still posts the clicked button's decision).
    document.querySelectorAll('button[data-confirm], input[type="submit"][data-confirm]')
      .forEach(function (btn) {
        btn.addEventListener('click', function (e) {
          var form = btn.form || btn.closest('form');
          if (!form) return;
          var message = btn.getAttribute('data-confirm');
          if (!supported) {
            if (!window.confirm(message)) e.preventDefault();
            return;
          }
          e.preventDefault();
          confirmThen(message, function () {
            if (typeof form.requestSubmit === 'function') {
              form.requestSubmit(btn);
            } else {
              // Fallback (e.g. Safari 15.4-15.6: showModal but no requestSubmit):
              // form.submit() drops the submitter, so carry its name/value manually.
              if (btn.name) {
                var hidden = document.createElement('input');
                hidden.type = 'hidden';
                hidden.name = btn.name;
                hidden.value = btn.value;
                form.appendChild(hidden);
              }
              form.submit();
            }
          });
        });
      });

    if (!supported) return;
    var ok = dialog.querySelector('[data-confirm-ok]');
    var cancel = dialog.querySelector('[data-confirm-cancel]');
    if (ok) ok.addEventListener('click', function () {
      dialog.close();
      var action = pending;
      pending = null;
      if (action) action();
    });
    if (cancel) cancel.addEventListener('click', function () { dialog.close(); pending = null; });
    dialog.addEventListener('cancel', function () { pending = null; });
  });
})();
