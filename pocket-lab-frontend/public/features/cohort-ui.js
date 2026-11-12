(() => {
  const isGuest = () => !!document.querySelector('[data-guest="true"]');
  let host, main, rows = [], selected = 'all', selectedClass = '', selectedDepartment = null, accountFilter = 'all', managementFilter = 'all', query = '', accountTypeFilter = '', serverTotal = 0, loadRevision = 0, searchTimer, loading = false, loadError = '', bulkFetching = false, dialog, pending, busy = false, verifying = false, blocked = false, creating = false;
  const typeOf = user => user.role === 'admin' ? 'admin' : user.accountType === 'teacher' ? 'teacher' : 'student';
  const departmentOf = user => (user.className || '').trim() || (/^(?:19|20)\d{2}(?:年|级|年级)?$/.test((user.grade || '').trim()) ? '' : (user.grade || '').trim());
  async function api(path, body, method = body ? 'POST' : 'GET') {
    if (isGuest()) throw new Error('当前为游客模式，请登录后操作');
    const session = JSON.parse(localStorage.getItem('pocket-lab-live-session-v1') || 'null')?.data;
    const res = await fetch('/api' + path, { method, headers: { Authorization: 'Bearer ' + session?.accessToken, 'Content-Type': 'application/json' }, ...(body ? { body: JSON.stringify(body) } : {}) });
    const result = await res.json(); if (!res.ok) throw Object.assign(new Error(result.message || '操作失败'), { blockers: result.blockers }); return result.data;
  }
  function message(text) { if (host) host.querySelector('.cohort-message').textContent = text; }
  async function readUsers(filters = {}, stillCurrent = () => true) {
    const all = []; let page = 1, total = 0;
    do {
      const params = new URLSearchParams({ pageSize: '100', page: String(page++), ...filters });
      const data = await api('/users?' + params); if (!stillCurrent()) return null;
      total = data.total; all.push(...data.items);
      if (!data.items.length && all.length < total) throw new Error('账号列表已变化，请重新查询。');
    } while (all.length < total && page <= 1000);
    if (all.length !== total) throw new Error('账号列表未完整载入，请缩小筛选范围后重试。');
    return { items: all, total };
  }
  async function load() {
    const target = host, revision = ++loadRevision;
    const current = () => revision === loadRevision && target === host && host?.isConnected;
    rows = []; serverTotal = 0; loading = true; loadError = ''; paint();
    try {
      const result = await readUsers({ ...(accountTypeFilter ? { accountType: accountTypeFilter } : {}), ...(query.trim() ? { keyword: query.trim() } : {}), ...(['disabled', 'enabled'].includes(accountFilter) ? { enabled: String(accountFilter === 'enabled') } : {}) }, current);
      if (!result || !current()) return;
      rows = result.items; serverTotal = result.total;
    } catch (error) { if (current()) { rows = []; serverTotal = 0; loadError = error.message; } }
    finally { if (current()) { loading = false; paint(); } }
  }
  async function completeCohort(action, group) {
    if (bulkFetching) return; bulkFetching = true; const target = host, revision = loadRevision;
    const current = () => target === host && host?.isConnected && revision === loadRevision;
    try {
      const result = await readUsers({ accountType: 'student' }, current); if (!result || !current()) return;
      const users = result.items.filter(user => user.accountType !== 'teacher' && user.role !== 'admin' && (group.kind === 'class' ? user.officialClassName === group.value : user.classCohort === group.value));
      if (!users.length) { message('该分组暂无学生账号。'); return; }
      if (action === 'disable') confirmDisable(users, group); else await confirmDelete(users, group);
    } catch (error) { if (current()) message(error.message); }
    finally { bulkFetching = false; }
  }
  function paint() {
    const buttons = (container, options, value, change) => {
      container.replaceChildren();
      options.forEach(([key, label]) => {
        const button = document.createElement('button'); button.type = 'button'; button.textContent = label;
        button.setAttribute('aria-pressed', String(value === key)); button.onclick = () => change(key); container.append(button);
      });
    };
    const student = user => typeOf(user) === 'student';
    const tabs = host.querySelector('[aria-label="年级筛选"]');
    tabs.parentElement.hidden = ['teacher', 'admin'].includes(accountTypeFilter);
    const grades = [...new Set(rows.filter(student).map(row => row.classCohort).filter(Boolean))].sort().reverse();
    const options = [['all', '全部年级'], ...grades.map(grade => [grade, grade + '级']), ['unknown','未识别学生年级']];
    // Retain the selected cohort while loading or after its last account was removed.
    if (/^\d{2}$/.test(selected) && !grades.includes(selected)) options.splice(1,0,[selected,selected+'级']);
    buttons(tabs, options, selected, value => { selected = value; selectedClass = ''; paint(); });
    buttons(host.querySelector('.account-type-tabs'), [['', '全部类型'], ['student', '学生账号'], ['teacher', '老师账号'], ['admin', '管理员账号']], accountTypeFilter, value => {
      accountTypeFilter = value; selected = 'all'; selectedClass = ''; selectedDepartment = null; message(''); load();
    });
    buttons(host.querySelector('.account-filter-tabs'), [['all', '全部状态'], ['enabled', '已启用'], ['disabled', '已停用']], accountFilter, value => {
      accountFilter = value; message(''); load();
    });
    buttons(host.querySelector('.management-filter-tabs'), [['all', '全部权限'], ['management', '已开通管理权限'], ['ordinary', '普通用户']], managementFilter, value => { managementFilter = value; paint(); });
    const departments = host.querySelector('[aria-label="教师部门筛选"]'); departments.parentElement.hidden = accountTypeFilter !== 'teacher';
    const departmentNames = [...new Set(rows.filter(user => typeOf(user) === 'teacher').map(departmentOf).filter(Boolean))].sort((a,b) => a.localeCompare(b,'zh-CN'));
    if (selectedDepartment && !departmentNames.includes(selectedDepartment)) departmentNames.push(selectedDepartment);
    buttons(departments, [[null, '全部部门'], ...departmentNames.map(name => [name, name]), ['', '未填写部门']], selectedDepartment, value => { selectedDepartment = value; paint(); });
    const classSelect=host.querySelector('.cohort-class-select');
    classSelect.parentElement.hidden=['teacher','admin'].includes(accountTypeFilter);
    const groups=[...new Set(rows.filter(student).map(row=>row.officialClassName).filter(Boolean))].sort((a,b)=>a.localeCompare(b,'zh-CN'));
    if(selectedClass&&!groups.includes(selectedClass))groups.push(selectedClass);
    classSelect.replaceChildren(new Option('全部班级',''),...groups.map(name=>new Option(name,name)));
    classSelect.value=selectedClass;
    classSelect.onchange=()=>{selectedClass=classSelect.value;selected='all';paint();};
    const group=selectedClass?{kind:'class',value:selectedClass}:/^\d{2}$/.test(selected)?{kind:'year',value:selected}:null;
    const cohortRows=rows.filter(row=>selectedClass?student(row)&&row.officialClassName===selectedClass:
      selected==='all'||(student(row)&&(selected==='unknown'?!row.classCohort:row.classCohort===selected)));
    const filtered=cohortRows.filter(row=>!accountTypeFilter||typeOf(row)===accountTypeFilter)
      .filter(row=>accountTypeFilter!=='teacher'||selectedDepartment===null||departmentOf(row)===selectedDepartment)
      .filter(row=>accountFilter==='all'||(accountFilter==='disabled'?!row.enabled:row.enabled))
      .filter(row=>managementFilter==='all'||(managementFilter==='management'?row.role==='admin'||row.canManage:row.role!=='admin'&&!row.canManage));
    const bulkAllowed=!loading&&!loadError&&accountFilter==='all'&&managementFilter==='all'&&group&&['','student'].includes(accountTypeFilter);
    host.querySelector('.cohort-bulk-actions').hidden=!bulkAllowed;
    const bulk=host.querySelector('.cohort-bulk');
    const disable=host.querySelector('.cohort-bulk-disable');
    bulk.hidden=disable.hidden=!bulkAllowed;
    bulk.disabled=!cohortRows.length;
    disable.disabled=!cohortRows.some(user=>user.enabled);
    if(group){
      bulk.textContent='删除 '+groupLabel(group)+' 全部账号';
      disable.textContent='停用 '+groupLabel(group)+' 全部账号';
      bulk.onclick=()=>completeCohort('delete',group);
      disable.onclick=()=>completeCohort('disable',group);
    }
    host.setAttribute('aria-busy', String(loading));
    host.querySelector('.cohort-count').textContent = loading ? '正在加载账号…' : loadError ? '账号加载失败' : '当前显示 ' + filtered.length + ' 个账号 · 筛选匹配 ' + filtered.length + ' 个账号';
    const list = host.querySelector('.cohort-list'); list.replaceChildren();
    if (loading || loadError || !filtered.length) {
      const empty = document.createElement('p'); empty.className = 'cohort-empty';
      empty.textContent = loading ? '正在加载账号…' : loadError ? '加载失败：' + loadError : query ? '没有匹配的账号。' : '该分类暂无账号。'; list.append(empty);
      if (loadError) { const retry = document.createElement('button'); retry.type = 'button'; retry.className = 'cohort-retry'; retry.textContent = '重新加载'; retry.onclick = load; list.append(retry); }
    }
    filtered.forEach(user => {
      const row = document.createElement('article'); row.className = 'cohort-row'; row.dataset.userId = user.id;
      row.innerHTML = '<div class="cohort-person"><div><strong></strong><span class="cohort-badge"></span></div><p></p><small></small></div><div class="cohort-row-actions"></div>';
      row.querySelector('strong').textContent = user.name;
      row.querySelector('.cohort-badge').textContent = user.accountType === 'teacher' ? '教师' : user.role === 'admin' ? '管理员' : user.classCohort ? user.classCohort + '级' : '年级未识别';
      row.querySelector('p').textContent = user.accountType === 'teacher' ? '工号 ' + user.account : (user.studentNumber ? '学号 ' + user.studentNumber + ' · ' : '') + user.account;
      row.querySelector('small').textContent = (user.enabled ? '已启用 · ' : '已停用 · ') + (user.accountType === 'teacher' ? '部门：' + (departmentOf(user) || '未填写') : user.officialClassName || '未登记官方班级') + (user.role !== 'admin' ? ' · ' + (user.canManage ? '已开通管理权限' : '普通用户') : '');
      const actions = row.querySelector('.cohort-row-actions');
      if (user.role === 'admin') { const label = document.createElement('span'); label.textContent = '受保护账号'; actions.append(label); }
      else {
        const grant = document.createElement('button'); grant.type = 'button'; grant.textContent = user.canManage ? '撤销管理权限' : '开通管理权限';
        grant.onclick = async () => { grant.disabled = true; try { await api('/users/' + user.id + '/management', { enabled: !user.canManage }); message('管理权限已更新。'); await load(); } catch (error) { message(error.message); grant.disabled = false; } };
        const remove = document.createElement('button'); remove.type = 'button'; remove.className = 'cohort-delete'; remove.textContent = '删除账号'; remove.onclick = () => confirmDelete([user]);
        const toggle = document.createElement('button'); toggle.type = 'button'; toggle.textContent = user.enabled ? '停用' : '启用';
        toggle.onclick = async () => { toggle.disabled = true; try { await api('/users/' + user.id + '/enabled', { enabled: !user.enabled }); message(user.enabled ? '账号已停用，登录已撤销。' : '账号已启用。'); await load(); } catch (error) { message(error.message); toggle.disabled = false; } };
        const limit = document.createElement('button'); limit.type = 'button'; limit.textContent = '未归还上限 ' + (user.borrowLimit ?? 3) + ' 件'; limit.onclick = () => window.dispatchEvent(new CustomEvent('pocketlab:borrow-limit', { detail: { userRecord: { id: user.id, name: user.name, account: user.account, borrowLimit: user.borrowLimit ?? 3 } } }));
        const edit=document.createElement('button'); edit.type='button'; edit.textContent='编辑资料'; edit.onclick=()=>editProfile(user);
        actions.append(edit, grant, toggle, limit, remove);
      }
      list.append(row);
    });
  }
  const groupLabel=group=>group.kind==='year'?group.value+'级':group.value;
  function editProfile(user){
    const modal=document.createElement('dialog');
    modal.className='cohort-dialog cohort-edit-profile';
    modal.setAttribute('aria-label','编辑账号资料');
    modal.innerHTML='<form><h2>编辑账号资料</h2><label>姓名<input name="name" required maxlength="80"></label><label><span class="profile-group-label"></span><input name="className" maxlength="100"></label><label>联系电话<input name="phone" type="tel" maxlength="32"></label><label>邮箱<input name="email" type="email" maxlength="200"></label><p class="cohort-delete-error" role="alert"></p><div><button type="button">取消</button><button type="submit">保存资料</button></div></form>';
    const form=modal.querySelector('form');
    const teacher=typeOf(user)==='teacher';
    modal.querySelector('.profile-group-label').textContent=teacher?'部门':'官方班级';
    for(const [name,value] of Object.entries({name:user.name,className:teacher?user.className:user.officialClassName,phone:user.phone,email:user.email})){
      form.elements.namedItem(name).value=value||'';
    }
    let saving=false;
    modal.addEventListener('cancel',event=>{if(saving)event.preventDefault();});
    modal.addEventListener('close',()=>modal.remove());
    form.querySelector('[type=button]').onclick=()=>modal.close();
    form.onsubmit=async event=>{
      event.preventDefault();
      if(saving)return;
      saving=true;
      const controls=form.querySelectorAll('button');
      controls.forEach(button=>button.disabled=true);
      try{
        const body=Object.fromEntries(new FormData(form));
        await api('/users/'+encodeURIComponent(user.id)+'/profile',body,'PATCH');
        modal.close();message('资料已更新。');await load();
      }catch(error){form.querySelector('[role=alert]').textContent=error.message;}
      finally{saving=false;controls.forEach(button=>button.disabled=false);}
    };
    document.body.append(modal);modal.showModal();
  }
  function confirmDisable(users, group) {
    const enabled = users.filter(user => user.enabled);
    if (!enabled.length) return;
    const modal = document.createElement('dialog'); modal.className = 'cohort-dialog'; modal.setAttribute('aria-label','批量停用账号');
    modal.innerHTML = '<form><h2></h2><p class="disable-summary"></p><ul></ul><p>停用后会立即退出登录，之后可以重新启用。原有作品和借用记录保留。</p><p class="cohort-delete-error" role="alert"></p><div><button type="button">取消</button><button type="submit" class="cohort-disable-confirm">确认停用</button></div></form>';
    modal.querySelector('h2').textContent = '停用 '+groupLabel(group)+' 全部账号？';
    modal.querySelector('.disable-summary').textContent = '该分组共 ' + users.length + ' 人，本次将停用以下 ' + enabled.length + ' 个已启用账号，不受搜索条件限制。';
    enabled.forEach(user => { const li = document.createElement('li'); li.textContent = user.name + ' · ' + (user.studentNumber || user.account); modal.querySelector('ul').append(li); });
    let saving = false;
    modal.querySelector('[type=button]').onclick = () => { if (!saving) modal.close(); };
    modal.addEventListener('cancel',event => { if (saving) event.preventDefault(); });
    modal.addEventListener('close',() => modal.remove());
    modal.querySelector('form').onsubmit = async event => {
      event.preventDefault(); if (saving) return; saving = true; modal.querySelectorAll('button').forEach(button => button.disabled = true);
      try { const result = await api('/users/disable-cohort', {classGroup:group,ids:users.map(user=>user.id)}); modal.close(); message('已停用 ' + result.disabledCount + ' 个账号，可按需重新启用。'); await load(); }
      catch (error) { modal.querySelector('.cohort-delete-error').textContent = error.message; }
      finally { saving = false; modal.querySelectorAll('button').forEach(button => button.disabled = false); }
    };
    document.body.append(modal); modal.showModal();
  }
  function showBlockers(blockers) {
    blocked = true;
    dialog.querySelector('.cohort-delete-error').textContent = '有 ' + blockers.length + ' 个账号存在未归还器材，暂不能删除。本次不会删除任何账号。';
    const box = dialog.querySelector('.cohort-blockers'); box.replaceChildren();
    blockers.forEach(user => {
      const section = document.createElement('section'), title = document.createElement('strong');
      title.textContent = user.name + ' · ' + (user.studentNumber || user.account); section.append(title);
      user.loans.forEach(loan => { const line = document.createElement('p'); line.textContent = loan.equipmentName + ' × ' + loan.quantity + ' · ' + (loan.status === 'returning' ? '归还待验收' : '借用中'); section.append(line); });
      box.append(section);
    });
  }
  async function confirmDelete(users, group) {
    pending = { ids: users.map(user => user.id), ...(group ? {classGroup:group} : {}) };
    if (!dialog) {
      dialog = document.createElement('dialog'); dialog.className = 'cohort-dialog'; dialog.setAttribute('aria-labelledby','cohort-delete-title');
      dialog.innerHTML = '<form><h2 id="cohort-delete-title"></h2><p class="cohort-delete-description"></p><ul></ul><p class="cohort-delete-note">删除后这些账号将退出登录，无法再次登录；已有借用记录会保留。</p><p class="cohort-retention-note" hidden>请确认无留级学生</p><label class="cohort-confirm-label">输入分组名称或年级确认<input autocomplete="off" maxlength="100" aria-label="输入分组确认"></label><p class="cohort-delete-error" role="alert"></p><div class="cohort-blockers"></div><div><button type="button" class="cohort-cancel">取消</button><button type="submit" class="cohort-confirm">确认删除</button></div></form>';
      dialog.querySelector('.cohort-cancel').onclick = () => { if (!busy) dialog.close(); };
      dialog.addEventListener('cancel',event => { if (busy) event.preventDefault(); });
      dialog.querySelector('form').onsubmit = async event => {
        event.preventDefault(); if (busy || verifying || blocked) return;
        const error = dialog.querySelector('.cohort-delete-error');
        if (pending.classGroup && dialog.querySelector('input').value.trim() !== pending.classGroup.value) { error.textContent = '请输入 '+pending.classGroup.value+' 确认删除该分组全部账号。'; return; }
        busy = true; dialog.querySelectorAll('button,input').forEach(el => el.disabled = true);
        try { const result = await api('/users/delete',pending); dialog.close(); message('已删除 ' + result.deletedCount + ' 个账号，历史借用记录已保留。'); await load(); }
        catch (err) { error.textContent = err.message; if (err.blockers?.length) showBlockers(err.blockers); }
        finally { busy = false; dialog.querySelectorAll('button,input').forEach(el => el.disabled = false); dialog.querySelector('.cohort-confirm').disabled = blocked; }
      };
      document.body.append(dialog);
    }
    dialog.querySelector('h2').textContent = group ? '删除 '+groupLabel(group)+' 全部账号？' : '删除这个账号？';
    dialog.querySelector('.cohort-delete-description').textContent = '本次将删除以下 ' + users.length + ' 个账号' + (group ? '，包含该分组全部学生账号。' : '。');
    const list = dialog.querySelector('ul'); list.replaceChildren();
    users.forEach(user => { const li = document.createElement('li'); li.textContent = user.name + ' · ' + (user.studentNumber || user.account); list.append(li); });
    dialog.querySelector('.cohort-confirm-label').hidden = !group;
    dialog.querySelector('.cohort-retention-note').hidden = !group;
    dialog.querySelector('input').value = ''; dialog.querySelector('input').placeholder = group?.value || '';
    dialog.querySelector('.cohort-delete-error').textContent = '正在检查未归还器材…';
    dialog.querySelector('.cohort-blockers').replaceChildren(); blocked = false; verifying = true;
    dialog.querySelector('.cohort-confirm').disabled = true; dialog.showModal();
    const snapshot = pending;
    try {
      const result = await api('/users/delete-preview', snapshot);
      if (pending !== snapshot || !dialog.open) return;
      dialog.querySelector('.cohort-delete-error').textContent = '';
      if (result.blockers.length) showBlockers(result.blockers);
    } catch (error) { if (pending === snapshot && dialog.open) { blocked = true; dialog.querySelector('.cohort-delete-error').textContent = '无法完成归还检查，请关闭后重试：' + error.message; } }
    finally { if (pending === snapshot) { verifying = false; dialog.querySelector('.cohort-confirm').disabled = blocked; } }

  }
  function mount() {
    const next = isGuest() ? null : document.querySelector('.main:has(> .user-search)');
    if (next === main && host?.isConnected) {
      const opened = !!document.querySelector('.modal[aria-label="新增账号"]');
      const closed = creating && !opened; creating = opened;
      if (closed) load();
      return;
    }
    host?.remove(); main?.classList.remove('cohort-active'); if (dialog?.open) dialog.close();
    host = null; main = next; rows = []; selected = 'all'; selectedClass = ''; selectedDepartment = null; accountFilter = 'all'; managementFilter = 'all'; accountTypeFilter = ''; serverTotal = 0; query = ''; loading = false; loadError = ''; loadRevision++; clearTimeout(searchTimer);
    if (!main) return;
    main.classList.add('cohort-active'); host = document.createElement('section'); host.className = 'cohort-management';
    host.innerHTML = '<div class="cohort-toolbar"><div><h2>账号分类管理</h2><p>先选择账号类型，再按状态、权限和学生年级或教师部门筛选。</p></div><div class="cohort-bulk-actions" hidden><button type="button" class="cohort-bulk-disable">停用该年级全部账号</button><button type="button" class="cohort-bulk" hidden></button></div></div><div class="cohort-filter-group"><h3>账号类型</h3><div class="cohort-tabs account-type-tabs" aria-label="账号类型筛选"></div></div><div class="cohort-filter-group"><h3>账号状态</h3><div class="cohort-tabs account-filter-tabs" aria-label="账号快捷筛选"></div></div><div class="cohort-filter-group"><h3>管理权限</h3><div class="cohort-tabs management-filter-tabs" aria-label="管理权限筛选"></div></div><div class="cohort-filter-group"><h3>学生当前年级</h3><div class="cohort-tabs" aria-label="年级筛选"></div></div><div class="cohort-filter-group"><h3>官方班级</h3><select class="cohort-class-select" aria-label="官方班级筛选"></select></div><div class="cohort-filter-group" hidden><h3>教师部门</h3><div class="cohort-tabs" aria-label="教师部门筛选"></div></div><div class="cohort-search"><input type="search" aria-label="搜索账号、学号、工号或姓名" placeholder="搜索账号、学号、工号或姓名"><span class="cohort-count" aria-live="polite"></span></div><p class="cohort-message" role="status"></p><div class="cohort-list"></div>';
    main.querySelector('.user-search').before(host);
    host.querySelector('input').oninput = event => { query = event.target.value; loadRevision++; rows = []; serverTotal = 0; loading = true; loadError = ''; message(''); paint(); clearTimeout(searchTimer); searchTimer = setTimeout(load, 250); };
    load();
  }
  window.addEventListener('pocketlab:borrow-limit-updated', () => { if (host?.isConnected) load(); });
  let scheduled = false;
  new MutationObserver(() => { if (scheduled) return; scheduled = true; requestAnimationFrame(() => { scheduled = false; mount(); }); }).observe(document.getElementById('app'), { childList:true, subtree:true, attributes:true, attributeFilter:['data-guest'] });
  mount();
})();
