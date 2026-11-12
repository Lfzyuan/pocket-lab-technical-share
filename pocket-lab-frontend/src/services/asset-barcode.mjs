export function normalizeAssetBarcode(value) {
  if (typeof value !== 'string') return '';
  const code = value.trim();
  // Match the existing API/asset columns without changing identifier case or leading zeroes.
  return code.length > 0 && code.length <= 100 && !/[\u0000-\u001f\u007f-\u009f]/.test(code) ? code : '';
}
