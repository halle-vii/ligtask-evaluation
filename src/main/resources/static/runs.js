(() => {
  const workspace = document.querySelector('.runs-main');
  if (!workspace) return;
  const tabs = [...workspace.querySelectorAll('[data-run-tab]')];
  function openTab(name, focus = false) {
    if (!tabs.some(tab => tab.dataset.runTab === name)) name = 'setup';
    for (const tab of tabs) {
      const selected = tab.dataset.runTab === name;
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
      document.getElementById(tab.getAttribute('aria-controls')).hidden = !selected;
      if (selected && focus) tab.focus();
    }
    history.replaceState(null, '', `#${name}`);
  }
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => openTab(tab.dataset.runTab));
    tab.addEventListener('keydown', event => {
      const offset = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
      if (offset || event.key === 'Home' || event.key === 'End') {
        event.preventDefault();
        const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + offset + tabs.length) % tabs.length;
        openTab(tabs[next].dataset.runTab, true);
      }
    });
  });
  workspace.querySelectorAll('[data-open-run-tab]').forEach(button => button.addEventListener('click', () => openTab(button.dataset.openRunTab, true)));
  window.addEventListener('hashchange', () => openTab(location.hash.slice(1)));
  openTab(location.hash.slice(1));

  const models = [...workspace.querySelectorAll('input[name="run-model"]')];
  const selectAll = document.getElementById('select-all-models');
  function updatePlan() {
    const count = models.filter(input => input.checked).length;
    workspace.querySelectorAll('[data-selected-model-count]').forEach(value => { value.textContent = count; });
    workspace.querySelectorAll('[data-planned-requests]').forEach(value => { value.textContent = (count * Number(workspace.dataset.promptCount)).toLocaleString(); });
    selectAll.textContent = count === models.length ? 'Clear selection' : 'Select all';
  }
  models.forEach(input => input.addEventListener('change', updatePlan));
  selectAll.addEventListener('click', () => {
    const select = models.some(input => !input.checked);
    models.forEach(input => { input.checked = select; });
    updatePlan();
  });
  updatePlan();
  const choice = document.getElementById('run-input-choice');
  choice.addEventListener('change', () => workspace.querySelectorAll('[data-run-input]').forEach(preview => {
    preview.hidden = preview.dataset.runInput !== choice.value;
  }));
})();
