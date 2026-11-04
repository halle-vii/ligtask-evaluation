const chooser = document.getElementById('dataset-choice');
if (chooser) chooser.addEventListener('change', () => window.location.assign(chooser.value));

const filterToggle = document.getElementById('toggle-prompt-filters');
const filterPanel = document.getElementById('prompt-filter-panel');
const resultsLayout = document.querySelector('.results-layout');
if (filterToggle && filterPanel && resultsLayout) {
    const activeCount = Number(filterToggle.dataset.activeCount || 0);
    function setFiltersExpanded(expanded, remember = false) {
        filterPanel.hidden = !expanded;
        resultsLayout.dataset.filtersCollapsed = String(!expanded);
        filterToggle.setAttribute('aria-expanded', String(expanded));
        const label = `${expanded ? 'Hide' : 'Show'} filters${activeCount ? `, ${activeCount} active` : ''}`;
        filterToggle.setAttribute('aria-label', label);
        filterToggle.title = label;
        if (!expanded && filterPanel.contains(document.activeElement)) filterToggle.focus();
        if (remember) {
            try { localStorage.setItem('ligtask.promptFilters', expanded ? 'expanded' : 'collapsed'); }
            catch (_) { /* Filtering still works when storage is unavailable. */ }
        }
    }
    let preference = null;
    try { preference = localStorage.getItem('ligtask.promptFilters'); } catch (_) { /* Use the responsive default. */ }
    const defaultExpanded = activeCount > 0 || !window.matchMedia('(max-width: 1200px)').matches;
    setFiltersExpanded(preference === 'expanded' || (preference !== 'collapsed' && defaultExpanded));
    filterToggle.hidden = false;
    filterToggle.addEventListener('click', () => setFiltersExpanded(filterPanel.hidden, true));
    filterPanel.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            event.preventDefault();
            setFiltersExpanded(false, true);
        }
    });
}

const sidebarToggle = document.querySelector('.sidebar-toggle');
function reflectSidebarState() {
    if (!sidebarToggle) return;
    const expanded = document.documentElement.dataset.sidebar !== 'collapsed';
    const label = expanded ? 'Collapse sidebar' : 'Expand sidebar';
    sidebarToggle.setAttribute('aria-expanded', String(expanded));
    sidebarToggle.setAttribute('aria-label', label);
    sidebarToggle.title = label;
}
if (sidebarToggle) {
    reflectSidebarState();
    sidebarToggle.addEventListener('click', () => {
        const next = document.documentElement.dataset.sidebar === 'collapsed' ? 'expanded' : 'collapsed';
        document.documentElement.dataset.sidebar = next;
        try { localStorage.setItem('ligtask.sidebar', next); } catch (_) { /* Navigation still works without storage. */ }
        reflectSidebarState();
    });
    window.addEventListener('pageshow', reflectSidebarState);
    window.addEventListener('storage', event => {
        if (event.key === 'ligtask.sidebar') {
            document.documentElement.dataset.sidebar = event.newValue === 'collapsed' ? 'collapsed' : 'expanded';
            reflectSidebarState();
        }
    });
}

// Keep the footer control inside the visible workspace when the header wraps.
const workspaceHeader = document.querySelector('.eval-top');
if (workspaceHeader) {
    const updateHeaderHeight = () => document.documentElement.style.setProperty('--workspace-header-height', `${Math.ceil(workspaceHeader.getBoundingClientRect().height)}px`);
    updateHeaderHeight();
    new ResizeObserver(updateHeaderHeight).observe(workspaceHeader);
}

// Each table pager has its own GET form, retaining the current filters and search.
document.querySelectorAll('.page-select').forEach(select => {
    select.addEventListener('change', () => select.form.requestSubmit());
});

const metricExportDialog = document.getElementById('metric-export-dialog');
document.querySelectorAll('[data-open-metric-export]').forEach(link => {
    link.addEventListener('click', event => {
        if (!metricExportDialog?.showModal) return;
        event.preventDefault();
        metricExportDialog.showModal();
    });
});
document.getElementById('close-metric-export')?.addEventListener('click', () => metricExportDialog.close());
metricExportDialog?.addEventListener('click', event => {
    if (event.target === metricExportDialog) {
        const box = metricExportDialog.getBoundingClientRect();
        if (event.clientX < box.left || event.clientX > box.right || event.clientY < box.top || event.clientY > box.bottom) metricExportDialog.close();
    }
});

const resultsRun = document.getElementById('results-run');
resultsRun?.addEventListener('change', () => resultsRun.form.requestSubmit());
