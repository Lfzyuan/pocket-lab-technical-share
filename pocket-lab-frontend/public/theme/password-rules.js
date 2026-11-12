// Requested policy: at least six Unicode characters, no composition rules.
// Never trim, truncate or normalize passwords before comparing or hashing them.
export function passwordErrors({ currentPassword = '', newPassword = '', confirmPassword = '' }, confirm = true) {
  const errors = {};
  if (typeof currentPassword !== 'string' || !currentPassword) errors.currentPassword = '请输入当前密码。';
  if (typeof newPassword !== 'string' || !newPassword) errors.newPassword = '请输入新密码。';
  else if ([...newPassword].length < 6) errors.newPassword = '新密码至少需要 6 位字符。';
  if (confirm && !confirmPassword) errors.confirmPassword = '请再输入一次新密码。';
  else if (confirm && confirmPassword !== newPassword) errors.confirmPassword = '两次输入的新密码不一致。';
  return errors;
}
