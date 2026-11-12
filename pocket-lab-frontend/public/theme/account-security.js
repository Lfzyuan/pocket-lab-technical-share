import { passwordErrors } from './password-rules.js';

// A self-contained extension to the existing profile view. The original Vue bundle
// and profile-save event handlers remain untouched. Only configured same-origin APIs run.
if (new URLSearchParams(location.search).get('theme') !== 'original') {
  const config = window.pocketLabSecurityConfig || { enabled: false };
  const isGuest = () => !!document.querySelector('[data-guest="true"]');
  const sessionKey = 'pocket-lab-live-session-v1';
  let profile, dialog, opener, controller, busy = false, changed = false, role = 'student';
  let touched = new Set();
  const fields = {};
  const lockIcon = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="5" y="10" width="14" height="11" rx="3"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/></svg>';
  function session() {
    if (isGuest()) return null;
    try { return JSON.parse(localStorage.getItem(sessionKey) || 'null')?.data; } catch { return null; }
  }
  function loginPage() {
    const target = new URL(location.href);
    target.searchParams.delete('view'); target.searchParams.set('screen', 'login');
    target.searchParams.set('role', role); target.searchParams.set('passwordChanged', '1');
    location.replace(target.href);
  }
  function fieldError(name, message = '') {
    const input = fields[name];
    input.setAttribute('aria-invalid', String(!!message));
    const label = dialog.querySelector('#' + input.id + '-error');
    label.textContent = message; label.hidden = !message;
  }
  function showStatus(message = '') {
    const status = dialog.querySelector('.security-status');
    status.textContent = message; status.hidden = !message;
  }
  function values() { return Object.fromEntries(Object.entries(fields).map(([name, input]) => [name, input.value])); }
  function validate() {
    const errors = passwordErrors(values());
    touched.forEach(name => fieldError(name, errors[name]));
    return errors;
  }
  function setBusy(value) {
    busy = value;
    dialog.setAttribute('aria-busy', String(value));
    dialog.querySelectorAll('input,button').forEach(element => element.disabled = value);
    dialog.querySelector('.security-submit').textContent = value ? '正在修改…' : '确认修改';
  }
  function reset() {
    controller?.abort(); controller = null; touched = new Set(); busy = false;
    dialog.querySelector('form').reset();
    for (const [name, input] of Object.entries(fields)) { input.type = 'password'; fieldError(name); }
    dialog.querySelectorAll('.security-eye').forEach(button => {
      button.setAttribute('aria-pressed', 'false'); button.setAttribute('aria-label', '显示' + button.dataset.label);
      button.querySelector('img').src = '/static/icons/eye-closed.png';
    });
    showStatus(); setBusy(false);
  }
  function buildDialog() {
    dialog = document.createElement('dialog'); dialog.className = 'security-dialog';
    dialog.setAttribute('aria-labelledby', 'security-title'); dialog.setAttribute('aria-describedby', 'security-description');
    dialog.innerHTML = `
      <button type="button" class="security-close" aria-label="关闭修改密码">×</button>
      <div class="security-heading"><span class="security-lock" aria-hidden="true">${lockIcon}</span><div><h2 id="security-title">修改密码</h2><p id="security-description">先验证当前密码，再设置新的登录密码。</p></div></div>
      <form class="security-form" novalidate>
        <div class="security-field"><label for="security-current">当前密码</label><div class="security-input-wrap"><input id="security-current" name="currentPassword" type="password" autocomplete="current-password" placeholder="请输入当前登录密码" aria-describedby="security-current-error"><button type="button" class="security-eye" data-field="currentPassword" data-label="当前密码" aria-label="显示当前密码" aria-pressed="false"><img src="/static/icons/eye-closed.png" alt=""></button></div><p class="security-field-error" id="security-current-error" hidden></p></div>
        <div class="security-field"><label for="security-new">新密码</label><div class="security-input-wrap"><input id="security-new" name="newPassword" type="password" autocomplete="new-password" placeholder="请输入至少 6 位的新密码" aria-describedby="security-new-help security-new-error"><button type="button" class="security-eye" data-field="newPassword" data-label="新密码" aria-label="显示新密码" aria-pressed="false"><img src="/static/icons/eye-closed.png" alt=""></button></div><p class="security-help" id="security-new-help">至少 6 位，不限制字符组合。</p><p class="security-field-error" id="security-new-error" hidden></p></div>
        <div class="security-field"><label for="security-confirm">确认新密码</label><div class="security-input-wrap"><input id="security-confirm" name="confirmPassword" type="password" autocomplete="new-password" placeholder="请再次输入新密码" aria-describedby="security-confirm-error"><button type="button" class="security-eye" data-field="confirmPassword" data-label="确认密码" aria-label="显示确认密码" aria-pressed="false"><img src="/static/icons/eye-closed.png" alt=""></button></div><p class="security-field-error" id="security-confirm-error" hidden></p></div>
        <p class="security-session-note">修改后需要重新登录，其他设备上的登录也会退出。</p>
        <div class="security-demo-note" hidden><strong>本地演示</strong><span>初始密码：<code>PocketLab@2026</code>。修改只对本地演示账号生效，重启预览后恢复初始密码。</span></div>
        <p class="security-status" role="alert" hidden></p>
        <div class="security-actions"><button type="button" class="security-button security-cancel">取消</button><button type="submit" class="security-button security-button--primary security-submit">确认修改</button></div>
      </form>
      <div class="security-complete" hidden role="status"><span class="security-complete-icon" aria-hidden="true">✓</span><h3>密码修改成功</h3><p>请使用新密码重新登录。</p><button type="button" class="security-button security-button--primary security-login">重新登录</button></div>`;
    for (const name of ['currentPassword', 'newPassword', 'confirmPassword']) {
      fields[name] = dialog.querySelector(`[name="${name}"]`);
      fields[name].addEventListener('blur', () => { touched.add(name); validate(); });
      fields[name].addEventListener('input', () => { showStatus(); validate(); });
    }
    dialog.querySelector('.security-demo-note').hidden = !config.demo;
    dialog.querySelectorAll('.security-eye').forEach(button => button.addEventListener('click', () => {
      const input = fields[button.dataset.field], visible = input.type === 'password';
      input.type = visible ? 'text' : 'password'; button.setAttribute('aria-pressed', String(visible));
      button.setAttribute('aria-label', (visible ? '隐藏' : '显示') + button.dataset.label);
      button.querySelector('img').src = '/static/icons/' + (visible ? 'eye-open' : 'eye-closed') + '.png';
    }));
    dialog.querySelectorAll('.security-close,.security-cancel,.security-login').forEach(button => button.addEventListener('click', () => { if (!busy) dialog.close(); }));
    dialog.addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    dialog.addEventListener('close', () => {
      reset();
      if (changed) loginPage();
      else if (opener?.isConnected) opener.focus();
    });
    dialog.querySelector('form').addEventListener('submit', submit);
    document.body.append(dialog);
  }
  async function submit(event) {
    event.preventDefault(); if (busy) return;
    touched = new Set(Object.keys(fields)); const errors = validate();
    if (Object.keys(errors).length) { fields[Object.keys(errors)[0]].focus(); return; }
    const identity = session();
    if (!identity?.accessToken) { showStatus('登录已失效，请重新登录后重试。'); return; }
    if (!config.enabled || !config.endpoint?.startsWith('/api/')) { showStatus('密码修改服务尚未开通，请联系管理员。'); return; }
    role = identity.user?.role || 'student';
    setBusy(true); showStatus(); controller = new AbortController();
    let invalidField;
    const timeout = setTimeout(() => controller?.abort(), 15000);
    try {
      const response = await fetch(config.endpoint, {
        method: 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + identity.accessToken },
        // Never use the legacy mutation queue: it persists request bodies for retries.
        body: JSON.stringify({ currentPassword: fields.currentPassword.value, newPassword: fields.newPassword.value })
      });
      const result = await response.json();
      if (!response.ok || result.code !== 'OK') {
        if (result.field && fields[result.field]) { fieldError(result.field, result.message); invalidField = result.field; }
        else showStatus(result.message || '暂时无法修改密码，请稍后再试。');
        return;
      }
      if (result.data?.passwordChanged !== true || result.data?.reauthenticationRequired !== true) throw new Error('Unconfirmed response');
      changed = true;
      localStorage.removeItem(sessionKey);
      for (const input of Object.values(fields)) input.value = '';
      dialog.querySelector('form').hidden = true;
      dialog.querySelector('.security-complete').hidden = false;
      dialog.querySelector('.security-complete p').textContent = config.demo ? '本地演示密码已更新，请使用新密码重新登录。' : '请使用新密码重新登录。';
    } catch {
      if (dialog.open) showStatus('暂时无法确认修改结果，请先尝试用新密码登录；若失败，再使用原密码。');
    } finally {
      clearTimeout(timeout); controller = null; setBusy(false);
      if (changed) dialog.querySelector('.security-login').focus();
      else if (invalidField && dialog.open) fields[invalidField].focus();
    }
  }
  function open(button) {
    if (isGuest()) { if (typeof uni !== 'undefined') uni.showToast({title:'当前为游客模式，请登录后操作',icon:'none'}); return; }
    opener = button;
    if (!dialog) buildDialog();
    changed = false; reset();
    dialog.querySelector('form').hidden = false;
    dialog.querySelector('.security-complete').hidden = true;
    dialog.showModal(); fields.currentPassword.focus();
  }
  function enhance() {
    if (isGuest()) { controller?.abort(); changed = false; if (dialog?.open) dialog.close(); document.querySelectorAll('.account-security-entry').forEach(node => node.remove()); profile = null; return; }
    if (config.enabled) document.querySelector('.auth-card .password-field input')?.removeAttribute('maxlength');
    const next = document.querySelector('.profile-panel');
    if (next === profile) return;
    if (dialog?.open) { controller?.abort(); changed = false; dialog.close(); }
    profile = next;
    if (!profile) return;
    const entry = document.createElement('section'); entry.className = 'account-security-entry'; entry.setAttribute('aria-label', '账号安全');
    entry.innerHTML = '<div class="account-security-icon" aria-hidden="true">' + lockIcon + '</div><div class="account-security-copy"><h3>登录密码</h3><p></p></div><button type="button" class="btn secondary security-open">修改密码</button>';
    entry.querySelector('p').textContent = config.enabled ? '验证当前密码后，设置新的登录密码。' : '密码修改服务尚未开通，请联系管理员。';
    const button = entry.querySelector('button'); button.disabled = !config.enabled;
    button.addEventListener('click', () => open(button));
    profile.append(entry);
  }
  function mount() {
    const app = document.getElementById('app'); if (!app) return;
    let scheduled = false;
    new MutationObserver(() => { if (scheduled) return; scheduled = true; requestAnimationFrame(() => { scheduled = false; enhance(); }); }).observe(app, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-guest'] });
    enhance();
  }
  document.readyState === 'loading' ? document.addEventListener('DOMContentLoaded', mount) : mount();
}
