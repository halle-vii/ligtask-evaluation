const chooser = document.getElementById('dataset-choice');
if (chooser) chooser.addEventListener('change', () => window.location.assign(chooser.value));

const focusFilters = document.querySelector('.filter-focus');
if (focusFilters) focusFilters.addEventListener('click', () => document.querySelector('#filters input')?.focus());

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
