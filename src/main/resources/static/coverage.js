document.querySelectorAll('.coverage-support').forEach(support => {
  const toggles = [...support.querySelectorAll('.coverage-toggle')];
  toggles.forEach(toggle => toggle.addEventListener('click', () => {
    const open = toggle.getAttribute('aria-expanded') !== 'true';
    toggles.forEach(item => {
      const expanded = item === toggle && open;
      item.setAttribute('aria-expanded', String(expanded));
      document.getElementById(item.getAttribute('aria-controls')).hidden = !expanded;
    });
  }));
});
