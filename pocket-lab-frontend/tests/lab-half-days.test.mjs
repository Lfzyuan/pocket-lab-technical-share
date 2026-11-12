import test from 'node:test';
import assert from 'node:assert/strict';
import {bookingCellReason, bookingDates, bookingGroupState, bookingPages, longestBookingRun,
  canAddBookingCell, halfDays, slotKey, toggleBookingGroup} from '../src/services/lab-half-days.mjs';

const now=Date.parse('2026-11-08T09:00:00+08:00');
const dates=bookingDates(now);
const cell=(day,slotId='B1')=>({date:dates[day],slotId});
const opened=(day,slotId='B1',available=4)=>({...cell(day,slotId),enabled:true,available});

test('the fourteen-day window includes today and uses two seven-day pages',()=>{
 const pages=bookingPages(dates);
 assert.deepEqual(pages.map(page=>page.length),[7,7]);
 assert.equal(pages[0][0],'2026-11-08');
 assert.equal(pages[1].at(-1),'2026-11-21');
 assert.deepEqual(pages.flat(),dates);
});

test('eligibility uses Shanghai start times and the complete participant count',()=>{
 assert.equal(bookingCellReason(opened(0,'B1'),1,now),'started');
 assert.equal(bookingCellReason(opened(0,'B2',2),3,now),'capacity');
 assert.equal(bookingCellReason({...opened(1),enabled:false},1,now),'closed');
 assert.equal(bookingCellReason(opened(0,'B2',3),3,now),'');
 assert.equal(bookingCellReason(opened(0,'B2'),1,Date.parse('2026-11-08T13:00:00+08:00')),'started');
});

test('a day batch skips closed, started and insufficient-capacity cells',()=>{
 const candidates=[cell(0,'B1'),cell(0,'B2'),cell(0,'B3'),cell(1,'B1')];
 const rows=[opened(0,'B1'),opened(0,'B2',2),{...opened(0,'B3'),enabled:false},opened(1,'B1')];
 const result=toggleBookingGroup([],candidates,rows,{participants:3,now});
 assert.deepEqual(result.cells,[cell(1,'B1')]);
 assert.equal(result.skipped,3);
 assert.deepEqual(result.reasons,{closed:1,started:1,capacity:1});
 assert.equal(result.rejected,false);
});

test('cross-page additions exceeding seven days reject the entire batch',()=>{
 const selected=dates.slice(1,7).map((date)=>({date,slotId:'B1'}));
 const candidates=[cell(7),cell(8),cell(13)];
 const rows=dates.map((_,index)=>opened(index));
 const result=toggleBookingGroup(selected,candidates,rows,{now});
 assert.equal(result.rejected,true);
 assert.deepEqual(result.cells,selected);
 assert.equal(canAddBookingCell([...selected,cell(7)],dates[8]),false);
 assert.equal(canAddBookingCell([...selected,cell(7)],dates[13]),true);
 assert.equal(longestBookingRun([cell(6),cell(6,'B3'),cell(12)]),1);
 assert.equal(toggleBookingGroup([cell(1)],[cell(13)],rows,{now}).rejected,false);
});

test('a skipped distant date does not expand the consecutive run',()=>{
 const result=toggleBookingGroup([cell(1)],[cell(2),cell(13)],
  [opened(1),opened(2),{...opened(13),enabled:false}],{now});
 assert.equal(result.rejected,false);
 assert.equal(result.skipped,1);
 assert.deepEqual(result.cells,[cell(1),cell(2)]);
});

test('a fully selected column toggles off only that group across pages',()=>{
 const selected=[cell(1),cell(2),cell(6,'B3')],targets=[cell(1),cell(2)];
 const rows=[opened(1),opened(2),opened(6,'B3')];
 assert.equal(bookingGroupState(selected,targets,rows,1,now),'true');
 const result=toggleBookingGroup(selected,targets,rows,{now});
 assert.equal(result.action,'remove');
 assert.deepEqual(result.cells,[cell(6,'B3')]);
 assert.equal(bookingGroupState([cell(1)],targets,rows,1,now),'mixed');
});

test('unavailable selected cells can still be removed as a whole group',()=>{
 const selected=[cell(0,'B1')],rows=[opened(0,'B1')];
 const result=toggleBookingGroup(selected,[cell(0,'B1'),cell(0,'B2')],rows,{now});
 assert.equal(result.action,'remove');
 assert.deepEqual(result.cells,[]);
});

test('column and page selections stay unique and chronological',()=>{
 const candidates=[cell(2,'B3'),cell(1,'B2'),cell(2,'B3')];
 const rows=[opened(2,'B3'),opened(1,'B2')];
 const result=toggleBookingGroup([],candidates,rows,{now});
 assert.deepEqual(result.cells.map(slotKey),[dates[1]+'|B2',dates[2]+'|B3']);
 assert.equal(halfDays.length,3);
});
