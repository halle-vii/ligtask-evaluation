(() => {
  const file = document.getElementById('file');
  const drop = document.getElementById('drop');
  const filename = document.getElementById('filename');
  const removeFile = document.getElementById('remove-file');
  const form = file.form;
  const submit = form.querySelector('button[type="submit"]');
  const dialog = document.getElementById('upload-error');
  const errors = document.getElementById('upload-error-list');
  const defaultSubmitLabel = submit.textContent;
  let busy = false;

  function showErrors(messages, title = 'Import blocked') {
    document.getElementById('upload-error-title').textContent = title;
    errors.replaceChildren(...messages.map(message => {
      const item = document.createElement('li');
      item.textContent = message;
      return item;
    }));
    if (!dialog.open) dialog.showModal();
  }
  function updateSelection() {
    filename.textContent = file.files[0]?.name || 'None';
    removeFile.hidden = !file.files.length;
  }
  function setBusy(value) {
    busy = value;
    submit.disabled = value;
    file.disabled = value;
    removeFile.disabled = value;
    if (value) form.setAttribute('aria-busy', 'true');
    else {
      form.removeAttribute('aria-busy');
      submit.textContent = defaultSubmitLabel;
    }
  }
  document.getElementById('dismiss-upload-error').addEventListener('click', () => dialog.close());
  document.getElementById('acknowledge-upload-error').addEventListener('click', () => dialog.close());
  if (dialog.dataset.open === 'true') dialog.showModal();

  file.addEventListener('change', updateSelection);
  updateSelection();
  window.addEventListener('pageshow', () => { setBusy(false); updateSelection(); });
  removeFile.addEventListener('click', async () => {
    if (busy) return;
    file.value = '';
    drop.classList.remove('over');
    updateSelection();
    const body = new FormData(form);
    setBusy(true);
    try {
      const response = await fetch('/preview/clear', { method: 'POST', body });
      if (!response.ok) throw new Error('Preview was not cleared');
      file.focus();
    } catch {
      showErrors(['The file was removed from this form, but its pending preview could not be cleared. Reload the page and try again.'], 'Unable to clear preview');
    } finally {
      setBusy(false);
      if (!dialog.open) file.focus();
    }
  });
  for (const event of ['dragover', 'dragleave', 'drop']) {
    drop.addEventListener(event, e => {
      e.preventDefault();
      if (busy) return;
      drop.classList.toggle('over', event === 'dragover');
      if (event === 'drop' && e.dataTransfer.files.length) {
        file.files = e.dataTransfer.files;
        file.dispatchEvent(new Event('change'));
      }
    });
  }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (busy) return;
    const body = new FormData(form);
    let navigating = false;
    setBusy(true);
    submit.textContent = 'Validating…';
    try {
      const response = await fetch(form.action, {
        method: 'POST', body, headers: { Accept: 'application/json', 'X-Requested-With': 'fetch' }
      });
      const result = await response.json();
      if (response.ok && result.valid && result.previewUrl === '/preview') {
        window.location.assign(result.previewUrl);
        navigating = true;
      } else {
        showErrors(result.errors?.length ? result.errors : ['Unable to validate this file. Please try again.']);
      }
    } catch {
      showErrors(['Unable to validate this file. Check your connection and try again.']);
    } finally {
      if (!navigating) setBusy(false);
    }
  });

  const removalDialog = document.getElementById('remove-dataset-dialog');
  const confirmRemoval = document.getElementById('confirm-remove-dataset');
  let removalForm = null;
  document.querySelectorAll('.remove-dataset-form').forEach(target => {
    target.addEventListener('submit', event => {
      event.preventDefault();
      removalForm = target;
      document.getElementById('remove-dataset-name').textContent = target.dataset.datasetName;
      document.getElementById('remove-dataset-source').textContent = `${target.dataset.sourceFile} · ${target.dataset.recordCount} prompts`;
      confirmRemoval.disabled = false;
      removalDialog.showModal();
    });
  });
  removalDialog.querySelectorAll('[data-cancel-remove]').forEach(button => {
    button.addEventListener('click', () => removalDialog.close());
  });
  removalDialog.addEventListener('close', () => { removalForm = null; });
  confirmRemoval.addEventListener('click', () => {
    if (!removalForm || confirmRemoval.disabled) return;
    confirmRemoval.disabled = true;
    removalForm.submit();
  });
})();
