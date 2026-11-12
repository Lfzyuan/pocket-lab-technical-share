// Local preview adapter. Account type and management permission are distinct.
(() => {
  const isGuest = () => !!document.querySelector('[data-guest="true"]');
  const key = 'pocket-lab-live-session-v1';
  let identity, users = [], loading = false, switcher, message = '';
  const read = () => { if (isGuest()) return null; try { return JSON.parse(localStorage.getItem(key))?.data; } catch { return null; } };
  const save = user => { const current = read(); if (current) localStorage.setItem(key, JSON.stringify({ type: 'object', data: { ...current, user } })); };
  async function api(path, body) {
    const current = read(); if (!current) throw new Error('请先登录。');
    const res = await fetch('/api' + path, { method: body ? 'POST' : 'GET', headers: { Authorization: 'Bearer ' + current.accessToken, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
    if (isGuest()) throw new Error('当前为游客模式，请登录后操作');
    const result = await res.json(); if (!res.ok) throw new Error(result.message || '操作失败，请重试。'); return result.data;
  }
  function setText(element, value) { if (element.textContent !== value) element.textContent = value; }
  async function refresh() {
    if (loading || !read() || !document.querySelector('.sidebar')) return;
    loading = true;
    try {
      const next = await api('/me');
      const previous = read()?.user;
      identity = next; save(next);
      if (previous && previous.role !== next.role) { location.reload(); return; }
      if (next.accountRole === 'admin') users = (await api('/users')).items;
      render();
    } catch (error) { message = error.message; }
    finally { loading = false; }
  }
  function render() {
    if (isGuest()) { identity = null; users = []; switcher?.remove(); switcher = null; document.querySelectorAll('.management-grant').forEach(node => node.remove()); return; }
    const sidebar = document.querySelector('.sidebar');
    identity = identity || read()?.user;
    if (!sidebar || !identity) { switcher?.remove(); switcher = null; return; }
    // Rename visible role labels without changing the original Vue role adapter.
    const walker = document.createTreeWalker(document.getElementById('app'), NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      if (node.parentElement?.closest('[data-account-identity]')) continue;
      if (node.nodeValue === '学生') node.nodeValue = '用户';
      else if (node.nodeValue === '老师') node.nodeValue = '管理视角';
      else if (node.nodeValue === '老师工作台') node.nodeValue = '管理工作台';
      else if (node.nodeValue === '老师已完成完好验收，器材库存已恢复。') node.nodeValue = '管理人员已确认归还，器材库存已恢复。';
      else if (node.nodeValue.includes('结束使用后请申请归还并交回实物。')) node.nodeValue = node.nodeValue.replace('结束使用后请申请归还并交回实物。', '结束使用后请交回实物，由管理人员确认归还。');
    }
    // Account creation now offers only user/admin. Management is granted separately.
    document.querySelectorAll('.modal .chip').forEach(chip => {
      if (['老师', '管理视角'].includes(chip.textContent.trim())) chip.hidden = true;
    });
    document.querySelectorAll('.modal select[aria-label="角色"] option[value="reviewer"]').forEach(option => { option.hidden = true; option.disabled = true; });
    if (!identity.canManage || identity.accountRole === 'admin' || identity.role === 'admin') { switcher?.remove(); switcher = null; }
    else {
      if (!switcher?.isConnected) {
        switcher = document.createElement('details'); switcher.className = 'account-view-switch';
        switcher.innerHTML = '<summary><span class="account-view-avatar">林</span><span><strong></strong><small></small></span><span class="account-view-chevron">⌃</span></summary><div class="account-view-menu"><p></p><button type="button"></button><span role="status"></span></div>';
        sidebar.append(switcher);
        switcher.querySelector('button').onclick = async () => {
          const button = switcher.querySelector('button'); button.disabled = true;
          try {
            const next = await api('/me/view', { view: identity.role === 'reviewer' ? 'user' : 'management' });
            save(next); const target = new URL(location); target.searchParams.set('role', 'student'); target.searchParams.delete('view'); location.replace(target);
          } catch (error) { setText(switcher.querySelector('[role=status]'), error.message); button.disabled = false; }
        };
      }
      setText(switcher.querySelector('strong'), identity.name);
      setText(switcher.querySelector('.account-view-avatar'), identity.name.slice(0, 1));
      setText(switcher.querySelector('small'), identity.role === 'reviewer' ? '管理视角' : '用户视角');
      setText(switcher.querySelector('.account-view-menu p'), identity.canManage ? '已开通审批、发放归还与库存查询权限' : '管理权限由管理员开通');
      const button = switcher.querySelector('button'); button.hidden = !identity.canManage;
      setText(button, identity.role === 'reviewer' ? '切换到用户视角' : '进入管理视角');
    }
    document.querySelectorAll('.user-row').forEach(row => {
      const account = row.querySelector('.muted')?.textContent.split(' · ')[0].trim();
      const user = users.find(item => item.account === account);
      if (!user || user.role === 'admin' || identity.accountRole !== 'admin') return;
      let control = row.querySelector('.management-grant');
      if (!control) {
        control = document.createElement('div'); control.className = 'management-grant';
        control.innerHTML = '<span></span><button type="button"></button><small role="status"></small>';
        row.append(control);
        control.querySelector('button').onclick = async () => {
          const latest = users.find(item => item.id === user.id), button = control.querySelector('button'); button.disabled = true;
          try { const updated = await api('/users/' + user.id + '/management', { enabled: !latest.canManage }); users = users.map(item => item.id === user.id ? updated : item); render(); setText(control.querySelector('small'), updated.canManage ? '已开通，用户可从左下角切换' : '已撤销，用户将返回普通视角'); }
          catch (error) { setText(control.querySelector('small'), error.message); }
          finally { button.disabled = false; }
        };
      }
      setText(control.querySelector('span'), user.canManage ? '管理权限已开通' : '普通用户');
      setText(control.querySelector('button'), user.canManage ? '撤销管理权限' : '开通管理权限');
    });
  }
  let scheduled = false;
  new MutationObserver(() => { if (!scheduled) { scheduled = true; requestAnimationFrame(() => { scheduled = false; render(); }); } }).observe(document.getElementById('app'), { subtree: true, childList: true, attributes: true, attributeFilter: ['data-guest'] });
  refresh(); setInterval(refresh, 2000); window.addEventListener('focus', refresh);
})();
