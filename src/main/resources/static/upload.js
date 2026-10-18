(() => {
  const file = document.getElementById('file');
  const drop = document.getElementById('drop');
  const filename = document.getElementById('filename');
  const form = file.form;
  const submit = form.querySelector('button[type="submit"]');
  const dialog = document.getElementById('upload-error');
  const errors = document.getElementById('upload-error-list');

  function showErrors(messages) {
    errors.replaceChildren(...messages.map(message => {
      const item = document.createElement('li');
      item.textContent = message;
      return item;
    }));
    if (!dialog.open) dialog.showModal();
  }
  document.getElementById('dismiss-upload-error').addEventListener('click', () => dialog.close());
  document.getElementById('acknowledge-upload-error').addEventListener('click', () => dialog.close());
  if (dialog.dataset.open === 'true') dialog.showModal();

  file.addEventListener('change', () => { filename.textContent = file.files[0]?.name || 'None'; });
  for (const event of ['dragover', 'dragleave', 'drop']) {
    drop.addEventListener(event, e => {
      e.preventDefault();
      drop.classList.toggle('over', event === 'dragover');
      if (event === 'drop' && e.dataTransfer.files.length) {
        file.files = e.dataTransfer.files;
        file.dispatchEvent(new Event('change'));
      }
    });
  }
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (submit.disabled) return;
    const label = submit.textContent;
    submit.disabled = true;
    submit.textContent = 'Validating…';
    form.setAttribute('aria-busy', 'true');
    try {
      const response = await fetch(form.action, {
        method: 'POST', body: new FormData(form), headers: { Accept: 'application/json', 'X-Requested-With': 'fetch' }
      });
      const result = await response.json();
      if (response.ok && result.valid && result.previewUrl === '/preview') {
        window.location.assign(result.previewUrl);
      } else {
        showErrors(result.errors?.length ? result.errors : ['Unable to validate this file. Please try again.']);
      }
    } catch {
      showErrors(['Unable to validate this file. Check your connection and try again.']);
    } finally {
      submit.disabled = false;
      submit.textContent = label;
      form.removeAttribute('aria-busy');
    }
  });
})();
