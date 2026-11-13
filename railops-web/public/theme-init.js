// Applies the saved light/dark choice before Angular starts, so dark-mode users don't see a white flash.
// A separate file (not inline) because the Content-Security-Policy only allows scripts from this site.
// Same storage key and values as RailLens; ThemeService takes over once the app has loaded.
(function () {
  try {
    var p = localStorage.getItem('raillens-theme');
    var dark = p === 'dark' || ((p === null || p === 'system') && matchMedia('(prefers-color-scheme: dark)').matches);
    if (dark) document.documentElement.classList.add('dark');
  } catch (e) {}
})();
