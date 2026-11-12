import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFile} from 'node:fs/promises';

class Node {
 constructor(tag='div',className=''){this.tagName=tag.toUpperCase();this.className=className;this.children=[];this.textContent='';this.isConnected=true;this.listeners={};this.classList={add:()=>{},remove:()=>{},toggle:()=>{}};}
 append(...children){for(const child of children){child.parentNode=this;this.children.push(child);}}
 after(node){this.afterNode=node;}
 replaceChildren(...children){this.children=[];this.append(...children);}
 setAttribute(key,value){this[key]=value;}
 removeAttribute(key){delete this[key];}
 addEventListener(key,handler){this.listeners[key]=handler;}
 remove(){this.isConnected=false;}
 querySelector(selector){return this.querySelectorAll(selector)[0]||null;}
 querySelectorAll(selector){const matches=node=>selector.startsWith('.')?node.className.split(' ').includes(selector.slice(1)):node.tagName===selector.toUpperCase();return this.children.flatMap(child=>[...(matches(child)?[child]:[]),...child.querySelectorAll(selector)]);}
}
function fixture(works=false){
 let reads=0;const requests=[],toasts=[],observers=[],app=new Node(),marker=new Node(),main=new Node('main'),module=new Node('div','module-panel'),title=new Node('h1','page-title'),profile=new Node('div','profile-panel');title.textContent=works?'作品展示':'值班维护';module.append(title);main.append(module);main.querySelector=selector=>selector===':scope > .module-panel'?module:Node.prototype.querySelector.call(main,selector);
 const document={readyState:'complete',getElementById:()=>app,createElement:tag=>new Node(tag),querySelector:selector=>selector==='[data-guest="true"]'?marker:selector==='.main'?main:selector==='.main:has(> .user-search)'?main:selector==='.profile-panel'?profile:null,querySelectorAll:selector=>selector==='.main > .module-panel'?[module]:[],addEventListener:()=>{}};
 const context={document,console,URL,URLSearchParams,AbortController,setTimeout:()=>1,clearTimeout:()=>{},setInterval:()=>1,clearInterval:()=>{},requestAnimationFrame:fn=>fn(),location:{search:'',href:'http://guest.invalid/'},localStorage:{getItem(){reads++;throw Error('Guest extension must not inspect a stored real session');},setItem(){throw Error('Guest must not write session');}},fetch:async(url,options={})=>{requests.push({url,options});assert.match(url,/^\/api\/public\/works/);return {ok:true,json:async()=>({data:{items:[],total:0}})};},MutationObserver:class{constructor(callback){observers.push(callback);}observe(){}},uni:{showToast:value=>toasts.push(value.title)}};
 context.window=context;context.pocketLabSecurityConfig={enabled:true};context.addEventListener=()=>{};context.dispatchEvent=()=>{};
 return {context,requests,toasts,observers,profile,module,reads:()=>reads};
}
for(const path of ['public/features/management.js','public/features/weekly-ui.js','public/features/cohort-ui.js','public/features/excel-import.js','public/theme/account-security.js'])test(path+' ignores a residual real session and never mounts private controls for guests',async()=>{
 const f=fixture(),source=(await readFile(new URL('../'+path,import.meta.url),'utf8')).replace(/^import[^\n]+\n/,'');
 vm.runInNewContext(source,f.context,{filename:path});for(const callback of f.observers)callback();await new Promise(resolve=>setImmediate(resolve));
 assert.equal(f.reads(),0);assert.deepEqual(f.requests,[]);assert.equal(f.profile.children.length,0);assert.equal(f.module.afterNode,undefined);
});
test('guest showcase keeps public browsing and clickable submission prompts without auth fallback',async()=>{
 const f=fixture(true);vm.runInNewContext(await readFile(new URL('../public/features/showcase.js',import.meta.url),'utf8'),f.context,{filename:'showcase.js'});await new Promise(resolve=>setImmediate(resolve));
 assert.equal(f.reads(),0);assert.equal(f.requests.length,1);assert.equal(f.requests[0].url,'/api/public/works?page=1&pageSize=100');assert.equal(f.requests[0].options.credentials,'omit');assert.equal(f.requests[0].options.headers.Authorization,undefined);
 const host=f.module.afterNode,buttons=host.querySelectorAll('button');for(const text of ['我的投稿','＋ 提交作品']){const button=buttons.find(node=>node.textContent===text);assert.ok(button,text);assert.notEqual(button.disabled,true);button.onclick();}
 assert.deepEqual(f.toasts,['当前为游客模式，请登录后操作','当前为游客模式，请登录后操作']);assert.equal(f.reads(),0);assert.equal(f.requests.length,1);assert.equal(host.querySelector('form'),null);
});

for(const withGlobalUni of [true,false])test('guest showcase prioritizes the Vue toast bridge with global uni '+withGlobalUni,async()=>{
 const f=fixture(true),events=[];if(!withGlobalUni)delete f.context.uni;f.context.CustomEvent=class{constructor(type){this.type=type;}};f.context.dispatchEvent=event=>events.push(event.type);
 vm.runInNewContext(await readFile(new URL('../public/features/showcase.js',import.meta.url),'utf8'),f.context,{filename:'showcase.js'});await new Promise(resolve=>setImmediate(resolve));
 const host=f.module.afterNode,buttons=host.querySelectorAll('button');for(const text of ['我的投稿','＋ 提交作品'])buttons.find(node=>node.textContent===text).onclick();
 assert.deepEqual(events,['pocket-lab-guest-action','pocket-lab-guest-action']);assert.deepEqual(f.toasts,[]);assert.equal(f.reads(),0);assert.equal(f.requests.length,1);assert.equal(host.querySelector('form'),null);
});
