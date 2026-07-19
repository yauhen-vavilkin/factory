(function () {
  document.addEventListener('DOMContentLoaded', function () {
    var editor = document.querySelector('[data-prompt-editor]');
    if (!editor) return;
    var saveForm = editor.closest('form');
    var revertForm = document.querySelector('[data-revert-form]');

    // Dirty tracking: warn before leaving with unsaved edits; clear on submit.
    var baseline = editor.value;
    var dirty = false;
    editor.addEventListener('input', function () { dirty = editor.value !== baseline; });
    if (saveForm) saveForm.addEventListener('submit', function () { dirty = false; });
    if (revertForm) revertForm.addEventListener('submit', function () { dirty = false; });
    window.addEventListener('beforeunload', function (e) {
      if (!dirty) return;
      e.preventDefault();
      e.returnValue = '';
    });

    // Ctrl/Cmd+S saves the current editor content.
    document.addEventListener('keydown', function (e) {
      if ((e.metaKey || e.ctrlKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        if (!saveForm) return;
        if (typeof saveForm.requestSubmit === 'function') saveForm.requestSubmit();
        else saveForm.submit();
      }
    });

    // Keep the revert form's hidden author in sync with the visible editor author,
    // so a revert is attributed without a second author field.
    var author = document.querySelector('[data-author-source]');
    var revertAuthor = document.querySelector('[data-author-target]');
    if (author && revertAuthor) {
      var sync = function () { revertAuthor.value = author.value; };
      author.addEventListener('input', sync);
      sync();
    }
  });
})();
