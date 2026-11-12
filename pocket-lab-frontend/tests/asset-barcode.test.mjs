import assert from 'node:assert/strict';
import test from 'node:test';
import {normalizeAssetBarcode} from '../src/services/asset-barcode.mjs';

test('preserves numeric leading zeroes and letter identifiers exactly as decoded',()=>{
  for(const value of ['KDJ26001001','kdj26000001','00001234','26001001','DZ26001001','dz00123','Dz-A_01/2','LAB 001']){
    assert.equal(normalizeAssetBarcode(value),value);
  }
  assert.equal(normalizeAssetBarcode(' 00001234\r\n'),'00001234');
  assert.equal(normalizeAssetBarcode(' dz00123 '),'dz00123');
  assert.equal(normalizeAssetBarcode('https://example.com/asset/001'),'https://example.com/asset/001');
});

test('accepts the API asset-number length boundary without truncating payloads',()=>{
  const longest='A'.repeat(100);
  assert.equal(normalizeAssetBarcode(longest),longest);
  assert.equal(normalizeAssetBarcode('A'.repeat(101)),'');
});

test('rejects empty, non-text, and embedded control payloads',()=>{
  for(const value of ['', ' \r\n', 'DZ\n001', 'DZ\t001', 'DZ\u0000001', 'DZ\u007f001', 'DZ\u0085001', null, 1234, {}]){
    assert.equal(normalizeAssetBarcode(value),'');
  }
});
