// Consistent parent-page navigation, using the existing application's handlers.
(() => {
  if (new URLSearchParams(location.search).get('theme') === 'original') return;
  function mount() {
    const app = document.getElementById('app');
    let scheduled = false;
    function render() {
      const main = app.querySelector('.main');
      if (!main) return;
      let back = main.querySelector(':scope > .unified-page-back');
      const existing = [...main.querySelectorAll('.detail-back,.page-back')].find(button => button.getClientRects().length);
      const home = [...app.querySelectorAll('.sidebar .nav-item')].find(button => ['首页', '工作台'].includes(button.textContent.trim()));
      const isHome = !!main.querySelector('.home-stats') && home?.classList.contains('active');
      if (existing || isHome || !home) { back?.remove(); main.classList.remove('has-unified-back'); return; }
      const grouped = main.querySelector('.grouped-back .text-btn');
      const personal = [...app.querySelectorAll('.sidebar .nav-item')].find(button => ['个人中心', '我的'].includes(button.textContent.trim()));
      const target = grouped || (main.querySelector('.profile-panel') && personal) || home;
      const label = grouped ? '返回' + grouped.textContent.replace(/^[‹←\s]+/, '').replace(/^返回/, '') : target === personal ? '返回个人中心' : '返回' + home.textContent.trim();
      if (!back) {
        back = document.createElement('button'); back.type = 'button'; back.className = 'unified-page-back';
        main.prepend(back);
      }
      if (back.textContent !== '‹ ' + label) back.textContent = '‹ ' + label;
      back.onclick = () => { if (target.isConnected) { target.click(); window.scrollTo({ top: 0, behavior: 'instant' }); } };
      main.classList.add('has-unified-back');
    }
    new MutationObserver(() => { if (scheduled) return; scheduled = true; requestAnimationFrame(() => { scheduled = false; render(); }); }).observe(app, { childList: true, subtree: true });
    render();
  }
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', mount) : mount();
})();
