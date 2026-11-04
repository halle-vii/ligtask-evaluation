
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

      document.getElementById(
        tab.getAttribute('aria-controls')
      ).hidden = !selected;

      if (selected && focus) tab.focus();
    }

    history.replaceState(null, '', `#${name}`);
  }

  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => openTab(tab.dataset.runTab));

    tab.addEventListener('keydown', event => {
      const offset =
        event.key === 'ArrowRight' ? 1 :
        event.key === 'ArrowLeft' ? -1 : 0;

      if (offset || event.key === 'Home' || event.key === 'End') {
        event.preventDefault();

        const next =
          event.key === 'Home' ? 0 :
          event.key === 'End' ? tabs.length - 1 :
          (index + offset + tabs.length) % tabs.length;

        openTab(tabs[next].dataset.runTab, true);
      }
    });
  });

  workspace.querySelectorAll('[data-open-run-tab]').forEach(button => {
    button.addEventListener('click', () => {
      openTab(button.dataset.openRunTab, true);
    });
  });

  window.addEventListener('hashchange', () => {
    openTab(location.hash.slice(1));
  });

  openTab(location.hash.slice(1));

  const models = [...workspace.querySelectorAll('input[name="run-model"]')];
  const selectAll = document.getElementById('select-all-models');
  const start = document.getElementById('start-run');
  const limit = document.getElementById('run-limit');
  const instance = document.getElementById('run-instance');
  const maxTokens = document.getElementById('run-max-tokens');
  const ack = document.getElementById('run-cost-ack');
  const message = document.getElementById('run-availability');
  const api = workspace.dataset.runApi;
  let activeRun = null, currentRun = null, submitting = false, pollTimer = null;
  let clientToken = crypto.randomUUID(), responsePage = 0;
  const activeStates = ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'];
  const actionMessage = document.getElementById('run-action-message');
  const retryDialog = document.getElementById('retry-run-dialog');
  const deleteDialog = document.getElementById('delete-run-dialog');
  let retrySource = null, retryToken = null, retryBusy = false, deleteSource = null;
  const names = new Map(models.map(m => [m.value, m.parentElement.querySelector('span').textContent]));

  function updatePlan() {
    const chosen = models.filter(m => m.checked && !m.disabled);
    const count = instance.value.trim() ? 1 : Math.min(Number(limit.value), Number(workspace.dataset.promptCount));
    workspace.querySelectorAll('[data-selected-model-count]').forEach(el => el.textContent = chosen.length);
    workspace.querySelectorAll('[data-planned-requests]').forEach(el => el.textContent = (count * chosen.length).toLocaleString());
    const available = models.filter(m => !m.disabled);
    selectAll.textContent = available.length && available.every(m => m.checked) ? 'Clear selection' : 'Select all';
    selectAll.disabled = !available.length;
    start.disabled = submitting || Boolean(activeRun) || !chosen.length || !ack.checked || !count || !maxTokens.checkValidity();
  }
  models.forEach(m => m.addEventListener('change', () => { clientToken = crypto.randomUUID(); updatePlan(); }));
  [limit, instance, maxTokens].forEach(el => el.addEventListener('input', () => { clientToken = crypto.randomUUID(); updatePlan(); }));
  ack.addEventListener('change', updatePlan);
  selectAll.addEventListener('click', () => {
    const value = models.some(m => !m.disabled && !m.checked);
    models.forEach(m => { if (!m.disabled) m.checked = value; });
    clientToken = crypto.randomUUID(); updatePlan();
  });
  const choice = document.getElementById('run-input-choice');
  choice.addEventListener('change', () => workspace.querySelectorAll('[data-run-input]').forEach(el => el.hidden = el.dataset.runInput !== choice.value));

  function error(text) { message.textContent = text; message.setAttribute('role', 'alert'); actionMessage.textContent = text; }
  async function request(path = '', method = 'GET', body) {
    const headers = { Accept: 'application/json' };
    if (method !== 'GET') headers[workspace.dataset.csrfHeader] = workspace.dataset.csrfToken;
    if (body) headers['Content-Type'] = 'application/json';
    const response = await fetch(api + path, { method, headers, credentials: 'same-origin', body: body ? JSON.stringify(body) : undefined });
    let data;
    try { data = await response.json(); } catch { data = {}; }
    if (!response.ok) throw new Error(data.message || data.detail || `Request failed (${response.status}). Refresh the page if your session expired.`);
    return data;
  }
  function node(tag, text, className) { const el = document.createElement(tag); if (text !== undefined) el.textContent = text; if (className) el.className = className; return el; }
  function button(text, action) { const el = node('button', text, 'run-secondary-button'); el.type = 'button'; el.addEventListener('click', action); return el; }

  async function historyList() {
    const data = await request();
    activeRun = data.activeRun || null;
    const list = document.getElementById('saved-run-list'); list.replaceChildren();
    if (!data.runs.length) list.append(node('p', 'No saved runs'));
    for (const run of data.runs) {
      const row = node('div', undefined, 'saved-run-row');
      const time = new Date(run.created_at).toLocaleString();
      const info = node('div', undefined, 'saved-run-info');
      const meta = node('div', undefined, 'saved-run-meta');
      meta.append(node('strong', run.status.replaceAll('_', ' '), 'saved-run-status'), node('span', time, 'saved-run-time'));
      info.append(meta, node('p', `Run ID: ${run.id}`, 'saved-run-id'));
      if (run.settings?.retryOf) info.append(node('small', `Retry of ${run.settings.retryOf}`, 'saved-run-id'));
      const view = button('View run', () => { currentRun = run.id; responsePage = 0; document.getElementById('run-response-panel').hidden = true; openTab('progress'); refreshState().catch(e => error(e.message)); });
      view.classList.add('saved-run-view');
      view.setAttribute('aria-label', `View run ${run.id}`);
      const actions = node('div', undefined, 'saved-run-actions');
      actions.append(view);
      if (!activeStates.includes(run.status)) {
        const retry = button('Retry unfinished', () => reviewRetry(run.id));
        retry.disabled = Boolean(activeRun); retry.setAttribute('aria-label', `Review retry for run ${run.id}`);
        const remove = button('Delete', () => {
          deleteSource = run.id;
          document.getElementById('delete-run-id').textContent = run.id;
          document.getElementById('delete-run-error').textContent = '';
          deleteDialog.showModal();
        });
        remove.classList.add('run-delete-button'); remove.disabled = Boolean(activeRun);
        remove.setAttribute('aria-label', `Delete run ${run.id}`);
        actions.append(retry, remove);
      }
      row.append(info, actions);
      list.append(row);
    }
    if (!currentRun) {
      const running = data.runs.find(r => activeStates.includes(r.status));
      if (running) { currentRun = running.id; openTab('progress'); await refreshState(); }
      else if (activeRun) message.textContent = 'Another run is active. Open its dataset to view progress.';
    }
    updatePlan();
  }
  async function reviewRetry(id) {
    try {
      const plan = await request('/' + id + '/retry-plan');
      if (!plan.requests) { actionMessage.textContent = 'This run has no failed or unfinished requests to retry.'; return; }
      if (retrySource !== id) { retrySource = id; retryToken = crypto.randomUUID(); }
      document.getElementById('retry-run-summary').textContent = `Run ${id}: preserve ${plan.preserved} valid responses and send up to ${plan.requests} requests again. The original run stays saved. A new run will contain both the preserved and retried results.`;
      document.getElementById('retry-uncertain-label').hidden = !plan.uncertain;
      document.getElementById('retry-uncertain-text').textContent = `${plan.uncertain} requests have uncertain outcomes and may already have been processed and charged. I accept the risk of duplicate charges.`;
      document.getElementById('retry-cost-ack').checked = false;
      document.getElementById('retry-uncertain-ack').checked = false;
      document.getElementById('retry-run-error').textContent = '';
      retryDialog.dataset.uncertain = String(plan.uncertain);
      updateRetry(); retryDialog.showModal();
    } catch (e) { error(e.message); }
  }
  function updateRetry() {
    document.getElementById('confirm-retry-run').disabled = retryBusy || !document.getElementById('retry-cost-ack').checked || (Number(retryDialog.dataset.uncertain) > 0 && !document.getElementById('retry-uncertain-ack').checked);
  }
  document.getElementById('retry-cost-ack').addEventListener('change', updateRetry);
  document.getElementById('retry-uncertain-ack').addEventListener('change', updateRetry);
  document.getElementById('cancel-retry-run').addEventListener('click', () => retryDialog.close());
  document.getElementById('confirm-retry-run').addEventListener('click', async () => {
    retryBusy = true; updateRetry();
    try {
      const data = await request('/' + retrySource + '/retry', 'POST', {clientToken: retryToken, acknowledgeCosts: document.getElementById('retry-cost-ack').checked, acknowledgeUncertain: document.getElementById('retry-uncertain-ack').checked});
      retryDialog.close(); retrySource = null; retryToken = null;
      currentRun = data.run.id; activeRun = data.run.id;
      document.getElementById('run-response-panel').hidden = true;
      actionMessage.textContent = 'Retry run saved. Valid responses are preserved; only unfinished items will be sent.';
      openTab('progress'); await refreshState();
    } catch (e) { document.getElementById('retry-run-error').textContent = e.message; }
    finally { retryBusy = false; updateRetry(); }
  });
  document.getElementById('cancel-delete-run').addEventListener('click', () => deleteDialog.close());
  document.getElementById('confirm-delete-run').addEventListener('click', async event => {
    event.target.disabled = true;
    try {
      const data = await request('/' + deleteSource, 'DELETE');
      if (currentRun === deleteSource) {
        currentRun = null; clearTimeout(pollTimer);
        document.getElementById('run-response-panel').hidden = true;
        document.getElementById('active-run-title').textContent = 'No run selected';
        document.getElementById('active-run-detail').textContent = 'Open a saved run to view progress.';
        document.getElementById('run-counts').textContent = '';
        document.getElementById('live-model-progress').replaceChildren();
        document.getElementById('run-total-progress').value = 0;
        document.getElementById('view-run-responses').hidden = true;
        document.getElementById('run-export').hidden = true;
      }
      deleteDialog.close(); openTab('history');
      actionMessage.textContent = `Run deleted. ${data.sharedResponsesRetained} shared responses retained. Dataset prompts are unchanged.`;
      await historyList();
    } catch (e) { document.getElementById('delete-run-error').textContent = e.message; }
    finally { event.target.disabled = false; }
  });
  async function refreshState() {
    clearTimeout(pollTimer);
    if (!currentRun) return;
    const requestedRun = currentRun;
    const data = await request('/' + requestedRun), run = data.run;
    if (currentRun !== requestedRun) return;
    const totals = {}, perModel = new Map(); let total = 0, done = 0, reused = 0;
    for (const count of data.counts) {
      const n = Number(count.count); total += n;
      totals[count.status] = (totals[count.status] || 0) + n;
      reused += Number(count.reused);
      if (!['PENDING', 'IN_FLIGHT'].includes(count.status)) done += n;
      const group = perModel.get(count.model_key) || { total: 0, done: 0 };
      group.total += n; if (!['PENDING', 'IN_FLIGHT'].includes(count.status)) group.done += n;
      perModel.set(count.model_key, group);
    }
    document.getElementById('active-run-title').textContent = run.status.replaceAll('_', ' ');
    document.getElementById('run-live-status').textContent = run.status.replaceAll('_', ' ');
    document.getElementById('active-run-detail').textContent = `Run ${run.id} · ${done} of ${total} items finished`;
    const bar = document.getElementById('run-total-progress'); bar.max = total || 1; bar.value = done;
    document.getElementById('run-counts').textContent = `Valid: ${totals.VALID || 0} · Invalid responses: ${totals.INVALID_RESPONSE || 0} · API errors: ${totals.HTTP_ERROR || 0} · Uncertain: ${totals.UNCERTAIN || 0} · Cancelled: ${totals.CANCELLED || 0} · Reused: ${reused}`;
    const groups = document.getElementById('live-model-progress'); groups.replaceChildren();
    for (const [key, count] of perModel) {
      const row = node('div', undefined, 'run-progress-row'), progress = node('progress');
      progress.max = count.total || 1; progress.value = count.done; progress.setAttribute('aria-label', names.get(key) || key);
      row.append(node('span', names.get(key) || key), progress, node('span', `${count.done} / ${count.total}`)); groups.append(row);
    }
    document.getElementById('cancel-run').hidden = !['QUEUED', 'RUNNING'].includes(run.status);
    document.getElementById('view-run-responses').hidden = false;
    const download = document.getElementById('run-export'); download.hidden = false; download.href = api + '/' + currentRun + '/export';
    if (activeStates.includes(run.status)) {
      activeRun = run.id;
      pollTimer = setTimeout(() => refreshState().catch(e => error(e.message)), 2000);
    } else {
      if (activeRun === run.id) activeRun = null;
      await historyList();
    }
    updatePlan();
  }
  function responseField(label, value) {
    const field = node('p');
    field.append(node('strong', label + ':'), ' ' + value);
    return field;
  }
  async function responses() {
    if (!currentRun) return;
    const data = await request('/' + currentRun + '/responses?page=' + responsePage);
    document.getElementById('run-response-panel').hidden = false;
    const list = document.getElementById('run-response-rows'); list.replaceChildren();
    if (!data.rows.length) list.append(node('p', 'No responses on this page.'));
    for (const row of data.rows) {
      const el = node('article', undefined, 'saved-response-row');
      el.append(node('strong', `${row.instance_id} · ${names.get(row.model_key) || row.model_key}`));
      el.append(responseField('Response status', `${row.status.replaceAll('_', ' ')}${row.status === 'VALID' ? ' (matches the required response format)' : ''}${row.reused ? ' · Reused from a previous run' : ''}`));
      if (row.classification) el.append(responseField('Model classification', row.classification));
      if (row.positive_probability != null) el.append(responseField('Positive-class probability (Unsafe / Violates Policy / Refuse)', row.positive_probability));
      if (row.safety_rating != null) el.append(responseField('Model safety rating', `${row.safety_rating}/10 (1 = very unsafe; 10 = very safe)`));
      if (row.explanation) el.append(responseField('Model explanation', row.explanation));
      if (row.error_detail) el.append(responseField('Error details', row.error_detail));
      if (row.retry_after) el.append(responseField('Provider Retry-After', row.retry_after));
      const details = node('details'); details.append(node('summary', 'Input snapshot and original response'));
      details.append(node('pre', JSON.stringify({ input: row.input_snapshot, response: row.response }, null, 2))); el.append(details); list.append(el);
    }
    document.getElementById('responses-prev').disabled = responsePage === 0;
    document.getElementById('responses-next').disabled = data.rows.length < 50;
    document.getElementById('responses-page').textContent = `Page ${responsePage + 1} · up to 50 items`;
  }
  document.getElementById('responses-prev').addEventListener('click', () => { if (responsePage > 0) responsePage--; responses().catch(e => error(e.message)); });
  document.getElementById('responses-next').addEventListener('click', () => { responsePage++; responses().catch(e => error(e.message)); });
  document.getElementById('view-run-responses').addEventListener('click', () => { responsePage = 0; responses().catch(e => error(e.message)); });
  document.getElementById('refresh-runs').addEventListener('click', () => historyList().catch(e => error(e.message)));
  document.getElementById('cancel-run').addEventListener('click', async event => {
    event.target.disabled = true;
    try { await request('/' + currentRun + '/cancel', 'POST'); await refreshState(); }
    catch (e) { error(e.message); }
    finally { event.target.disabled = false; }
  });
  start.addEventListener('click', async () => {
    if (start.disabled) return;
    submitting = true; updatePlan(); message.removeAttribute('role'); message.textContent = 'Preparing and validating the selected inputs…';
    try {
      const data = await request('', 'POST', { clientToken, models: models.filter(m => m.checked && !m.disabled).map(m => m.value), promptLimit: Number(limit.value), instanceId: instance.value.trim(), maxTokens: Number(maxTokens.value), acknowledgeCosts: ack.checked });
      document.getElementById('run-response-panel').hidden = true;
      currentRun = data.run.id; activeRun = data.run.id; clientToken = crypto.randomUUID(); ack.checked = false;
      message.textContent = 'Run saved. Progress and responses are retained here.';
      openTab('progress'); await refreshState();
    } catch (e) { error(e.message); }
    finally { submitting = false; updatePlan(); }
  });
  updatePlan(); historyList().catch(e => error(e.message));
})();
