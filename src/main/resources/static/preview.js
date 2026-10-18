(() => {
  const button = document.getElementById('back-to-top');
  const heading = document.getElementById('preview-title');
  const update = () => { button.hidden = window.scrollY < 320; };
  window.addEventListener('scroll', update, { passive: true });
  button.addEventListener('click', () => {
    heading.focus({ preventScroll: true });
    window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  });
  update();
})();
