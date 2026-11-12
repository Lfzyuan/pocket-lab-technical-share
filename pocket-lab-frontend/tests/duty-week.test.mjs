import test from 'node:test';import assert from 'node:assert/strict';import {chinaToday,chinaWeekStart,shiftDay,dutyWeek,availableDutySlots,advanceDutyWindow} from '../src/services/duty-week.mjs';
test('week has seven Beijing dates across month boundaries and retains every actual slot',()=>{assert.equal(chinaToday(new Date('2026-11-01T18:00:00Z')),'2026-11-02');assert.equal(shiftDay('2026-11-30',2),'2026-12-02');const slots=[{id:'a',date:'2026-11-30',startTime:'14:00'},{id:'b',date:'2026-11-30',startTime:'08:00'},{id:'c',date:'2026-12-01',enabled:false,startTime:'08:00'}];const days=dutyWeek('2026-11-30',slots);assert.equal(days.length,7);assert.deepEqual(days[0].slots.map(s=>s.id),['b','a']);assert.equal(days[1].slots.length,0);assert.equal(days[6].date,'2026-12-06');});

test('live duty availability expires exactly at the Beijing end time',()=>{const slots=[{id:'ending',date:'2026-01-01',startTime:'08:00',endTime:'10:00:00'},{id:'later',date:'2026-01-01',startTime:'10:00',endTime:'12:00'},{id:'disabled',date:'2026-01-02',endTime:'12:00',enabled:false}];assert.deepEqual(availableDutySlots(slots,new Date('2026-01-01T09:59:59+08:00')).map(slot=>slot.id),['ending','later']);assert.deepEqual(availableDutySlots(slots,new Date('2026-01-01T10:00:00+08:00')).map(slot=>slot.id),['later']);assert.equal(availableDutySlots(slots,new Date('2026-01-02T00:00:00+08:00')).length,0);});
test('Shanghai calendar weeks begin on Monday across month and year boundaries',()=>{
 assert.equal(chinaWeekStart(new Date('2026-11-01T23:59:59+08:00')),'2026-10-26');
 assert.equal(chinaWeekStart(new Date('2026-11-02T00:00:00+08:00')),'2026-11-02');
 assert.equal(chinaWeekStart(new Date('2026-12-01T10:00:00+08:00')),'2026-11-30');
 assert.equal(chinaWeekStart(new Date('2027-01-01T10:00:00+08:00')),'2026-12-28');
 assert.equal(chinaWeekStart(new Date('2027-01-04T00:00:00+08:00')),'2027-01-04');
});
test('a weekday midnight updates today and selection without moving the displayed week',()=>{
 assert.deepEqual(advanceDutyWindow({start:'2026-11-02',selected:'2026-11-04',followCurrentWeek:true},new Date('2026-11-05T00:00:00+08:00')),{today:'2026-11-05',start:'2026-11-02',selected:'2026-11-05'});
});
test('the default week advances only at Shanghai Monday midnight including the year boundary',()=>{
 assert.deepEqual(advanceDutyWindow({start:'2026-11-02',selected:'2026-11-08'},new Date('2026-11-09T00:00:00+08:00')),{today:'2026-11-09',start:'2026-11-09',selected:'2026-11-09'});
 assert.deepEqual(advanceDutyWindow({start:'2026-12-28',selected:'2027-01-03'},new Date('2027-01-04T00:00:00+08:00')),{today:'2027-01-04',start:'2027-01-04',selected:'2027-01-04'});
});
test('manual weeks stay anchored, constrain selection, and still accept the old followToday option',()=>{
 const now=new Date('2026-11-09T00:00:00+08:00');
 assert.deepEqual(advanceDutyWindow({start:'2026-11-16',selected:'2026-11-17',followCurrentWeek:false},now),{today:'2026-11-09',start:'2026-11-16',selected:'2026-11-17'});
 assert.deepEqual(advanceDutyWindow({start:'2026-11-02',selected:'2026-11-02',followCurrentWeek:false},now),{today:'2026-11-09',start:'2026-11-02',selected:''});
 assert.equal(advanceDutyWindow({start:'2026-11-16',selected:'2026-12-01',followCurrentWeek:false},now).selected,'2026-11-16');
 assert.equal(advanceDutyWindow({start:'2026-11-16',selected:'2026-11-17',followToday:false},now).start,'2026-11-16');
 assert.equal(advanceDutyWindow({start:'2026-11-16',selected:'2026-11-17',followCurrentWeek:true,followToday:false},now).start,'2026-11-09');
});
