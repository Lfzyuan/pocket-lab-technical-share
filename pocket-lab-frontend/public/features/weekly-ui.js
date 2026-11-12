(() => {
  const isGuest = () => !!document.querySelector('[data-guest="true"]');
  let host, module, dialog, rules = [], editing, busy = false;
  const names = ['一','二','三','四','五','六','日'];
  async function api(path = '', method = 'GET', body) {
    if (isGuest()) throw new Error('当前为游客模式，请登录后操作');
    const session = JSON.parse(localStorage.getItem('pocket-lab-live-session-v1') || 'null')?.data;
    const res = await fetch('/api/duty-rules' + path, { method, headers: { Authorization: 'Bearer ' + session?.accessToken, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const result = await res.json(); if (!res.ok) throw new Error(result.message || '操作失败，请重试。'); return result.data;
  }
  function status(message) { if (host) host.querySelector('.weekly-status').textContent = message; }
  async function load() { try { const data = await api(); if (!host?.isConnected) return; rules = data.items; paint(); } catch (error) { status(error.message); } }
  function paint() {
    const list = host.querySelector('.weekly-list'); list.replaceChildren();
    host.querySelector('.weekly-empty').hidden = !!rules.length;
    for (const rule of rules) {
      const card = document.createElement('article'); card.className = 'weekly-card'; card.dataset.enabled = rule.enabled;
      card.innerHTML = '<div class="weekly-card-top"><button type="button" class="weekly-time" aria-label="编辑值班时段"></button><button type="button" class="weekly-toggle" role="switch"><i></i></button></div><p class="weekly-repeat"></p><p class="weekly-place"></p><div class="weekly-card-bottom"><span></span><div><button type="button" class="weekly-edit">编辑</button><button type="button" class="weekly-delete">删除</button></div></div>';
      card.querySelector('.weekly-time').textContent = rule.startTime + ' – ' + rule.endTime;
      card.querySelector('.weekly-repeat').textContent = rule.weekdays.length === 7 ? '每天重复' : '每周' + rule.weekdays.map(day => names[day-1]).join('、');
      card.querySelector('.weekly-place').textContent = rule.location;
      card.querySelector('.weekly-card-bottom span').textContent = rule.enabled ? '已启用 · 每周重复' : '已停用';
      const toggle = card.querySelector('.weekly-toggle'); toggle.setAttribute('aria-checked', String(rule.enabled)); toggle.setAttribute('aria-label', (rule.enabled ? '停用' : '启用') + ' ' + rule.startTime + ' 值班');
      toggle.onclick = async () => { toggle.disabled = true; try { await api('/' + rule.id, 'PATCH', { enabled: !rule.enabled, version: rule.version }); status(rule.enabled ? '已停用，学生端将不再展示该重复时段。' : '已启用，每周自动重复。'); await load(); } catch (error) { status(error.message); } finally { toggle.disabled = false; } };
      card.querySelector('.weekly-time').onclick = card.querySelector('.weekly-edit').onclick = () => open(rule);
      card.querySelector('.weekly-delete').onclick = async () => { if (!confirm('删除这条每周值班安排？')) return; try { await api('/' + rule.id, 'DELETE', { version: rule.version }); status('已删除重复安排。'); await load(); } catch (error) { status(error.message); } };
      list.append(card);
    }
  }
  function open(rule) {
    editing = rule;
    if (!dialog) {
      dialog = document.createElement('dialog'); dialog.className = 'weekly-dialog'; dialog.setAttribute('aria-labelledby','weekly-dialog-title');
      dialog.innerHTML = '<form><div class="weekly-dialog-heading"><h2 id="weekly-dialog-title"></h2><button type="button" class="weekly-close" aria-label="关闭">×</button></div><div class="weekly-times"><label>开始时间<input name="startTime" type="time" required></label><span>—</span><label>结束时间<input name="endTime" type="time" required></label></div><fieldset><legend>每周重复</legend><div class="weekly-days"></div><div class="weekly-presets"><button type="button" data-days="1,2,3,4,5">工作日</button><button type="button" data-days="1,2,3,4,5,6,7">每天</button><button type="button" data-days="">清空</button></div></fieldset><label class="weekly-location">值班地点<input name="location" required maxlength="100" placeholder="例如：电子信息实验中心 A301"></label><label class="weekly-enabled"><input name="enabled" type="checkbox">保存后启用</label><p class="weekly-dialog-note">按北京时间每周重复，学生端展示未来 8 周的具体值班日期。</p><p class="weekly-error" role="alert"></p><div class="weekly-actions"><button type="button" class="weekly-cancel">取消</button><button type="submit" class="weekly-primary">保存安排</button></div></form>';
      names.forEach((name,i) => { const label = document.createElement('label'); label.innerHTML = '<input type="checkbox" name="weekday" value="'+(i+1)+'"><span>'+name+'</span>'; label.querySelector('input').setAttribute('aria-label','周'+name); dialog.querySelector('.weekly-days').append(label); });
      dialog.querySelectorAll('[data-days]').forEach(button => button.onclick = () => { const days = button.dataset.days.split(','); dialog.querySelectorAll('[name=weekday]').forEach(input => input.checked = days.includes(input.value)); });
      dialog.querySelectorAll('.weekly-close,.weekly-cancel').forEach(button => button.onclick = () => { if (!busy) dialog.close(); });
      dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
      dialog.querySelector('form').onsubmit = async event => {
        event.preventDefault(); if (busy) return;
        const form = new FormData(event.target), weekdays = form.getAll('weekday').map(Number), error = dialog.querySelector('.weekly-error');
        const body = { weekdays, startTime: form.get('startTime'), endTime: form.get('endTime'), location: form.get('location'), enabled: form.has('enabled'), ...(editing ? { version: editing.version } : {}) };
        if (!weekdays.length) { error.textContent = '请至少选择一个重复日。'; return; }
        if (body.startTime >= body.endTime) { error.textContent = '结束时间须晚于开始时间，跨夜时段请分成两条。'; return; }
        busy = true; dialog.querySelectorAll('button,input').forEach(el => el.disabled = true); error.textContent = '';
        try { await api(editing ? '/' + editing.id : '', editing ? 'PATCH' : 'POST', body); dialog.close(); status('已保存，每周自动重复。'); await load(); }
        catch (err) { error.textContent = err.message; }
        finally { busy = false; dialog.querySelectorAll('button,input').forEach(el => el.disabled = false); }
      };
      document.body.append(dialog);
    }
    const form = dialog.querySelector('form'); form.reset();
    dialog.querySelector('h2').textContent = rule ? '编辑重复值班' : '添加重复值班';
    form.elements.startTime.value = rule?.startTime || '09:00'; form.elements.endTime.value = rule?.endTime || '12:00';
    form.elements.location.value = rule?.location || ''; form.elements.enabled.checked = rule?.enabled ?? true;
    dialog.querySelectorAll('[name=weekday]').forEach(input => input.checked = (rule?.weekdays || [1,2,3,4,5]).includes(Number(input.value)));
    dialog.querySelector('.weekly-error').textContent = ''; dialog.showModal();
  }
  function mount() {
    const next = isGuest() ? null : [...document.querySelectorAll('.main > .module-panel')].find(el => el.textContent.startsWith('值班维护'));
    if (next === module && host?.isConnected) return;
    host?.remove(); module?.classList.remove('weekly-legacy-hidden');
    if (dialog?.open) dialog.close(); module = next; host = null;
    if (!module) return;
    host = document.createElement('section'); host.className = 'weekly-schedule';
    host.innerHTML = '<div class="weekly-heading"><div><h1>值班安排</h1><p>像设置闹钟一样，安排每周的值班时间。</p></div></div><div class="weekly-tabs" role="tablist" aria-label="值班安排类型"><button type="button" role="tab" aria-selected="true">每周重复</button><button type="button" role="tab" aria-selected="false">临时安排</button></div><div class="weekly-content"><div class="weekly-toolbar"><span>按北京时间重复 · 展示未来 8 周</span><button type="button" class="weekly-primary weekly-add">＋ 添加时段</button></div><p class="weekly-status" role="status"></p><div class="weekly-list"></div><div class="weekly-empty" hidden>还没有重复值班安排，点击“添加时段”开始设置。</div></div>';
    module.before(host); module.classList.add('weekly-legacy-hidden');
    host.querySelectorAll('[role=tab]').forEach((button,index) => button.onclick = () => { host.querySelectorAll('[role=tab]').forEach((item,i) => item.setAttribute('aria-selected', String(i === index))); host.querySelector('.weekly-content').hidden = index === 1; module.classList.toggle('weekly-legacy-hidden', index === 0); });
    host.querySelector('.weekly-add').onclick = () => open(); load();
  }
  let scheduled = false;
  new MutationObserver(() => { if (scheduled) return; scheduled = true; requestAnimationFrame(() => { scheduled = false; mount(); }); }).observe(document.getElementById('app'), { childList: true, subtree: true, attributes: true, attributeFilter: ['data-guest'] });
  mount();
})();
