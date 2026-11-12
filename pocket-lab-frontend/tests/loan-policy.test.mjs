import test from 'node:test';import assert from 'node:assert/strict';import {nextRenewMonths,renewLabel} from '../src/services/loan-policy.mjs';
test('renewal labels use two months first, one month second and preserve server-specific terms',()=>{
 assert.equal(renewLabel({renewCount:0}),'续借2个月');assert.equal(renewLabel({renewCount:1}),'续借1个月');assert.equal(renewLabel({renewCount:2}),'已续借2次');assert.equal(nextRenewMonths({renewCount:0,nextRenewMonths:1}),1);
});
