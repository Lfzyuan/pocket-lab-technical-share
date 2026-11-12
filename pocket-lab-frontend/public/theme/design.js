// Presentation-only enhancements. No API calls, account state, or business data changes.
(() => {
  const original = new URLSearchParams(location.search).get('theme') === 'original';
  document.documentElement.dataset.design = original ? 'original' : 'new';
  if (original) return;
  const css = document.createElement('link'); css.rel = 'stylesheet'; css.href = '/theme/redesign.css?v=requests-20261104-1'; document.head.append(css); const feedback=document.createElement('link');feedback.rel='stylesheet';feedback.href='/theme/feedback.css?v=requests-20261104-1';document.head.append(feedback);
  function mount() {
    const app = document.getElementById('app');
    if (!app) return;
    const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
    const compactViewport = matchMedia('(max-width: 760px)');
    const motionDisabled = new URLSearchParams(location.search).get('motion') === 'off';
    const revealed = new WeakSet();
    const observedArtwork = new Set();
    const activeAnimations = new Set();
    let submissionEntry = null;
    function enhanceSubmissionEntry(main) {
      if (!main) return;
      const title = main.querySelector('.page-title')?.textContent.trim();
      const personal = [...app.querySelectorAll('.sidebar .nav-item')].find(node => node.textContent.trim() === '个人中心');
      const search = title === '作品展示' && personal ? main.querySelector('.module-overview > .search-box') : null;
      if (search && !search.querySelector('.works-submit-entry')) {
        search.classList.add('works-search');
        const button = document.createElement('button');
        button.type = 'button'; button.className = 'btn works-submit-entry'; button.textContent = '提交作品';
        button.addEventListener('click', () => {
          submissionEntry = { step: 'personal', expires: Date.now() + 5000 };
          personal.click();
        });
        search.append(button);
      }
      if (!submissionEntry) return;
      if (Date.now() > submissionEntry.expires) { submissionEntry = null; return; }
      // Use the existing navigation and form handlers so validation stays shared.
      if (submissionEntry.step === 'personal') {
        const service = [...main.querySelectorAll('.service-item')].find(node => node.textContent.trim() === '作品投稿');
        if (service) { submissionEntry.step = 'form'; service.click(); }
      } else if (title === '作品投稿') {
        const create = [...main.querySelectorAll('.section-top .btn')].find(node => node.textContent.trim() === '提交作品');
        if (create && !create.hasAttribute('disabled')) { submissionEntry = null; create.click(); }
      }
    }
    // Page sections may be recreated after background refreshes. Fading them in
    // again makes the whole screen flash, so only animate newly opened modals.
    const entrySelector = '.modal';
    function syncMotion() {
      const motionOff = motionDisabled || reducedMotion.matches || compactViewport.matches;
      document.documentElement.dataset.motion = motionOff ? 'off' : 'on';
      document.documentElement.classList.toggle('lab-motion-paused', document.hidden);
      if (motionOff || document.hidden) {
        activeAnimations.forEach(animation => animation.cancel());
        activeAnimations.clear();
      }
    }
    // Only the decorative illustration loops, and only while visible on screen.
    const artworkObserver = typeof IntersectionObserver === 'function' ? new IntersectionObserver(entries => {
      entries.forEach(({ target, isIntersecting }) => target.classList.toggle('lab-in-view', isIntersecting));
    }) : null;
    function introduceNewContent() {
      const fresh = [...app.querySelectorAll(entrySelector)].filter(element => {
        if (revealed.has(element)) return false;
        revealed.add(element);
        return element.getClientRects().length > 0;
      });
      if (document.documentElement.dataset.motion === 'off' || document.hidden) return;
      // Match the visual order, including the homepage's reordered CSS grid.
      const ordered = fresh.map(element => ({ element, rect: element.getBoundingClientRect() }))
        .filter(({ rect }) => rect.bottom > 0 && rect.top < innerHeight)
        .sort((a, b) => a.rect.top - b.rect.top || a.rect.left - b.rect.left);
      ordered.forEach(({ element }, index) => {
        if (typeof element.animate !== 'function') return;
        const modal = element.classList.contains('modal');
        const animation = element.animate([
          { opacity: 0, transform: modal ? 'translateY(8px) scale(.975)' : 'translateY(12px)' },
          { opacity: 1, transform: 'none' }
        ], {
          duration: modal ? 250 : 440,
          delay: modal ? 0 : Math.min(index * 38, 190),
          easing: 'cubic-bezier(.22, 1, .36, 1)',
          fill: 'backwards'
        });
        activeAnimations.add(animation);
        const cleanup = () => { activeAnimations.delete(animation); animation.cancel(); };
        animation.finished.then(cleanup, () => activeAnimations.delete(animation));
      });
    }
    syncMotion();
    reducedMotion.addEventListener('change', syncMotion);
    compactViewport.addEventListener('change', syncMotion);
    document.addEventListener('visibilitychange', syncMotion);
    function enhance() {
      const main = app.querySelector('.main');
      enhanceSubmissionEntry(main);
      if (main) main.classList.toggle('design-home', !!main.querySelector('.home-stats'));
      if (main) main.classList.toggle('catalog-tools', !!main.querySelector(':scope > .filters > .search-box'));
      const management = main?.querySelector('.management-grid');
      if (management && main.querySelector('.page-title')?.textContent.trim() === '管理中心' && !management.classList.contains('management-categorized')) {
        const groups = [
          ['器材与借用', '设备、借还与库存', ['器材维护', '借用记录', '采购入库', '库存日志', '异常与维修']],
          ['场地与值班', '空间和每周时间安排', ['实验室管理', '值班维护']],
          ['账号与权限', '人员账号与管理授权', ['账号管理', '批量导入']],
          ['内容与通知', '资源、审核与信息发布', ['资源维护', '作品维护', '投稿审核', '心愿审批', '通知管理']],
          ['数据概览', '查看使用情况与统计', ['数据统计']]
        ];
        const buttons = new Map([...management.querySelectorAll('.service-item')].map(button => [button.textContent.trim(), button]));
        const known = new Set(groups.flatMap(group => group[2]));
        const extra = [...buttons.keys()].filter(name => !known.has(name));
        if (extra.length) groups.push(['其他管理', '更多管理功能', extra]);
        management.classList.add('management-categorized');
        groups.forEach(([title, description, names], row) => {
          const heading = document.createElement('div'); heading.className = 'management-category';
          const h2 = document.createElement('h2'); h2.textContent = title;
          const hint = document.createElement('p'); hint.textContent = description;
          heading.append(h2, hint); heading.style.setProperty('--management-row', row + 1);
          management.append(heading);
          names.forEach((name, column) => {
            const button = buttons.get(name); if (!button) return;
            button.style.setProperty('--management-row', row + 1); button.style.setProperty('--management-col', column + 2);
            management.append(button);
          });
        });
      }
      const intro = app.querySelector('.auth-intro-copy');
      if (intro && !intro.querySelector('.lab-art')) {
        const art = document.createElement('img'); art.className = 'lab-art'; art.src = '/theme/lab-circuit.svg'; art.alt = ''; intro.append(art);
      }
      app.querySelectorAll('.field-group').forEach(group => {
        const text = group.querySelector('uni-text');
        group.querySelectorAll('input,textarea,select').forEach(input => { if (text && !input.hasAttribute('aria-label')) input.setAttribute('aria-label', text.textContent); });
      });
      app.querySelectorAll('.wish-card[data-wish-id]').forEach(card => {
        const status = card.querySelector('.status')?.textContent.trim();
        card.dataset.wishState = ['已批准采购','已列入计划'].includes(status) ? 'approved' : status === '已采购并上架' ? 'stocked' : status === '未通过' ? 'rejected' : 'pending';
      });
      app.querySelectorAll('.wishlist-page').forEach(wishlist => {
        wishlist.querySelectorAll('.wish-tabs .chip, .wish-filter .chip, :scope > .chips:not(.wish-tabs) .chip').forEach(button => {
          button.setAttribute('aria-pressed', String(button.classList.contains('selected')));
        });
        wishlist.querySelector('.wish-filter input')?.setAttribute('aria-label', '搜索心愿器材名称或型号');
        wishlist.querySelectorAll('.wish-review textarea').forEach(input => input.setAttribute('aria-label', '审批不通过的原因'));
      });
      observedArtwork.forEach(element => {
        if (element.isConnected) return;
        artworkObserver?.unobserve(element);
        observedArtwork.delete(element);
      });
      app.querySelectorAll('.home-hero, .lab-art').forEach(element => {
        if (observedArtwork.has(element)) return;
        observedArtwork.add(element);
        artworkObserver?.observe(element);
      });
      introduceNewContent();
    }
    let scheduled = false;
    new MutationObserver(() => { if (scheduled) return; scheduled = true; requestAnimationFrame(() => { scheduled = false; enhance(); }); }).observe(app, { childList: true, subtree: true });
    enhance();
  }
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', mount) : mount();
})();
