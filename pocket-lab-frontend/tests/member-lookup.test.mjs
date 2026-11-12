import test from 'node:test';
import assert from 'node:assert/strict';
import {createMemberLookup} from '../src/services/member-lookup.mjs';
function clock(){const tasks=new Set();return {schedule:fn=>{tasks.add(fn);return fn;},cancel:fn=>tasks.delete(fn),run:()=>{const calls=[...tasks];tasks.clear();return Promise.all(calls.map(fn=>fn()));}};}
test('one character starts a lookup and later input replaces its debounce',async()=>{
 const c=clock(),calls=[],states=[];const lookup=createMemberLookup({...c,search:async q=>{calls.push(q);return [];},onState:s=>states.push(s)});
 lookup.lookup('张');await c.run();assert.deepEqual(calls,['张']);lookup.lookup('张三');lookup.lookup('李四');await c.run();assert.deepEqual(calls,['张','李四']);assert.equal(states.at(-1).phase,'ready');lookup.dispose();
});
test('late directory responses cannot replace the current suggestions',async()=>{
 const c=clock(),states=[],pending={};const lookup=createMemberLookup({...c,search:q=>new Promise(resolve=>pending[q]=resolve),onState:s=>states.push(s)});
 lookup.lookup('张三');const old=c.run();lookup.lookup('李四');const latest=c.run();pending['李四']([{id:'new'}]);await latest;pending['张三']([{id:'old'}]);await old;assert.deepEqual(states.at(-1).items,[{id:'new'}]);assert.equal(states.at(-1).query,'李四');lookup.dispose();
});
test('selecting or closing clears suggestions and ignores an in-flight reply',async()=>{
 const c=clock(),states=[];let finish;const lookup=createMemberLookup({...c,search:()=>new Promise(resolve=>finish=resolve),onState:s=>states.push(s)});
 lookup.lookup('2026');const waiting=c.run();lookup.clear();finish([{id:'late'}]);await waiting;assert.equal(states.at(-1).phase,'idle');assert.deepEqual(states.at(-1).items,[]);lookup.dispose();
});
test('directory errors remain distinct from a successful empty search',async()=>{
 const c=clock(),states=[];const lookup=createMemberLookup({...c,search:async()=>{throw Error('offline');},onState:s=>states.push(s)});
 lookup.lookup('张三');await c.run();assert.equal(states.at(-1).phase,'error');lookup.lookup('李四');lookup.dispose();await c.run();assert.equal(states.at(-1).phase,'idle');
});

test('empty input makes no request and teacher exact metadata survives the shared lookup',async()=>{
 const c=clock(),calls=[],states=[];
 const result={items:[{id:'first-page'}],total:21,exactTotal:2,exactItems:[{id:'same-name-one'},{id:'same-name-two'}]};
 const lookup=createMemberLookup({...c,search:async q=>{calls.push(q);return result;},onState:s=>states.push(s)});
 lookup.lookup('  ');await c.run();assert.deepEqual(calls,[]);
 lookup.lookup('张');await c.run();assert.equal(states.at(-1).data.exactTotal,2);
 assert.equal(states.at(-1).data.total,21);assert.deepEqual(states.at(-1).items,result.items);
 lookup.dispose();
});
