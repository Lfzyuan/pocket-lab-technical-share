import test from 'node:test';import assert from 'node:assert/strict';import {primaryNavigation,mobileNavigation,approvalNavigation,managementNavigation,activePrimary} from '../src/services/navigation.mjs';
test('student services are grouped without management privileges',()=>{const ids=primaryNavigation('student').map(x=>x.id);assert.ok(ids.includes('personal'));for(const id of ['management','approvals','favorites','purchases','submissions'])assert.ok(!ids.includes(id));assert.deepEqual(approvalNavigation('student'),[]);assert.deepEqual(managementNavigation('student'),[]);});
test('reviewer and admin retain distinct approval scopes',()=>{assert.deepEqual(approvalNavigation('reviewer').map(x=>x.id),['review','bookings','purchases','otherBorrows']);assert.deepEqual(approvalNavigation('admin').map(x=>x.id),['submissions']);assert.ok(managementNavigation('admin').some(x=>x.id==='users'));assert.ok(managementNavigation('admin').some(x=>x.id==='purchases'));assert.ok(managementNavigation('admin').some(x=>x.id==='records'));assert.ok(!managementNavigation('reviewer').some(x=>x.id==='users'));});
test('mobile destinations exist and related services activate their parent',()=>{for(const role of ['student','reviewer','admin'])assert.equal(mobileNavigation(role).filter(Boolean).length,role==='admin'?3:5);assert.equal(activePrimary('favorites','student'),'personal');assert.equal(activePrimary('bookings','student'),'labs');assert.equal(activePrimary('otherBorrows','student'),'equipment');assert.equal(activePrimary('submissions','admin'),'management');});

test('staff navigation follows their duties and keeps all maintenance destinations grouped',()=>{
 assert.deepEqual(primaryNavigation('reviewer').map(n=>n.id),['home','approvals','handover','equipment','personal']);
 assert.deepEqual(primaryNavigation('admin').map(n=>n.id),['home','management','personal']);
 for(const id of ['resources','works','submissions','labs','inventory','users','announcements','otherBorrows']){
  assert.ok(managementNavigation('admin').some(n=>n.id===id));assert.equal(activePrimary(id,'admin'),'management');
 }
 for(const id of ['review','bookings','purchases','otherBorrows'])assert.equal(activePrimary(id,'reviewer'),'approvals');
 for(const id of ['dutyManage','announcements','incidents','labs'])assert.equal(activePrimary(id,'reviewer'),'personal');
});
