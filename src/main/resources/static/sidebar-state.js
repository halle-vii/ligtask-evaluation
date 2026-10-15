// Restore only a local display preference before painting the shared sidebar.
try {
    document.documentElement.dataset.sidebar = localStorage.getItem('ligtask.sidebar') === 'collapsed' ? 'collapsed' : 'expanded';
} catch (_) {
    document.documentElement.dataset.sidebar = 'expanded';
}
