import test from 'node:test';import assert from 'node:assert/strict';
import {regulationRequest,fetchRegulationPdf,pdfFilename,openReadingWindow,showPdfInWindow,releasePdfUrl,openNativeRegulation} from '../src/services/regulation-pdf.mjs';
const pdf='%PDF-1.4\nfixture',identity=()=>({accessToken:'isolated-token',user:{id:'isolated-user'}}),ok=()=>new Response(pdf,{headers:{'Content-Type':'application/pdf'}});
test('guest document requests are limited to two published kinds and never inspect a stored session',()=>{
 const request=regulationRequest('management',{guest:true,getSession:()=>{throw Error('must not read identity');}});assert.equal(request.url,'/api/public/regulations/management/download');assert.deepEqual(request.headers,{Accept:'application/pdf'});assert.equal(request.credentials,'omit');
 for(const kind of ['../files/private','private-id','management/download'])assert.throws(()=>regulationRequest(kind,{guest:true}),/不存在/);
});
test('student teacher reviewer and admin document reads preserve bearer authorization without cookies',async()=>{
 for(const role of ['student','teacher','reviewer','admin']){let options;const user={accessToken:'token-'+role,user:{id:role,role:role==='teacher'?'student':role,accountType:role}};const blob=await fetchRegulationPdf('usage',{getSession:()=>user,fetchImpl:async(url,value)=>{assert.equal(url,'/api/regulations/usage/download');options=value;return ok();}});assert.equal(blob.size,Buffer.byteLength(pdf));assert.equal(options.headers.Authorization,'Bearer token-'+role);assert.equal(options.credentials,'omit');}
});
test('document validation rejects wrong MIME type, invalid PDF bytes and empty content',async()=>{
 for(const response of [new Response('%PDF-1.4',{headers:{'Content-Type':'text/html'}}),new Response('not pdf',{headers:{'Content-Type':'application/pdf'}}),new Response('',{headers:{'Content-Type':'application/pdf'}})])await assert.rejects(fetchRegulationPdf('management',{guest:true,fetchImpl:async()=>response}),error=>error.code==='INVALID_PDF');
});
test('a 401 clears only the same captured token and keeps request metadata out of user text',async()=>{
 let current=identity(),cleared=0;const unauthorized=()=>new Response(JSON.stringify({message:'invalid',code:'UNAUTHORIZED',requestId:'TRACE_ONLY_METADATA'}),{status:401,headers:{'Content-Type':'application/json'}});
 await assert.rejects(fetchRegulationPdf('management',{getSession:()=>current,clearSession:()=>cleared++,fetchImpl:async()=>unauthorized()}),error=>error.status===401&&error.requestId==='TRACE_ONLY_METADATA'&&!error.message.includes('TRACE_ONLY_METADATA'));assert.equal(cleared,1);
 await assert.rejects(fetchRegulationPdf('management',{getSession:()=>current,clearSession:()=>cleared++,fetchImpl:async()=>{current={accessToken:'new-token',user:{id:'new-user'}};return unauthorized();}}));assert.equal(cleared,1);
});
test('successful late PDF responses cannot carry an old account into the new session',async()=>{
 let current=identity();await assert.rejects(fetchRegulationPdf('management',{getSession:()=>current,fetchImpl:async()=>{current={accessToken:'new-token',user:{id:'new-user'}};return ok();}}),error=>error.code==='DOCUMENT_CONTEXT_CHANGED');
});
test('network timeout and explicit cancellation are visible failures without retries',async()=>{
 const pending=async(url,options)=>new Promise((resolve,reject)=>{const abort=()=>reject(new DOMException('Aborted','AbortError'));if(options.signal.aborted)abort();else options.signal.addEventListener('abort',abort,{once:true});});
 await assert.rejects(fetchRegulationPdf('management',{guest:true,fetchImpl:pending,timeoutMs:5}),error=>error.code==='DOCUMENT_TIMEOUT');const controller=new AbortController();controller.abort();await assert.rejects(fetchRegulationPdf('management',{guest:true,signal:controller.signal,fetchImpl:pending}),error=>error.code==='DOCUMENT_CANCELLED');
});
test('reading opens synchronously with a retained handle and removes opener before navigation',()=>{
 const calls=[],popup={opener:{},closed:false,document:{title:'',body:{textContent:''}},location:{replace:url=>calls.push(url)}};const opened=openReadingWindow({open:(...args)=>{assert.deepEqual(args,['about:blank','_blank']);return popup;}});assert.equal(opened,popup);assert.equal(popup.opener,null);assert.equal(showPdfInWindow(popup,'blob:fixture'),true);assert.deepEqual(calls,['blob:fixture']);assert.equal(openReadingWindow({open:()=>null}),null);
});
test('PDF reader URLs survive component cleanup until all reader windows close',()=>{
 const windows=[{closed:false},{closed:false}],revoked=[],cancelled=[];let tick;
 releasePdfUrl('blob:reader',windows,{revoke:url=>revoked.push(url),schedule:fn=>{tick=fn;return 7;},cancel:id=>cancelled.push(id)});windows[0].closed=true;tick();assert.deepEqual(revoked,[]);windows[1].closed=true;tick();assert.deepEqual(revoked,['blob:reader']);assert.deepEqual(cancelled,[7]);
 let delayed;releasePdfUrl('blob:save',[],{revoke:url=>revoked.push(url),delay:fn=>{delayed=fn;return 1;}});assert.equal(revoked.includes('blob:save'),false);delayed();assert.equal(revoked.includes('blob:save'),true);
});
test('PDF filenames remain safe and preserve an explicit PDF extension',()=>{assert.equal(pdfFilename({fileName:'../policy.pdf'}),'.._policy.pdf');assert.equal(pdfFilename({title:'管理办法'}),'管理办法.pdf');});
test('native reading stays temporary, native download saves first, and stale pages never open documents',async()=>{
 const calls=[],platform={downloadFile:options=>{calls.push({headers:options.header});options.success({statusCode:200,tempFilePath:'temporary-pdf'});return {abort(){}};},getFileSystemManager:()=>({saveFile:options=>{calls.push('save');options.success({savedFilePath:'saved-pdf'});}}),openDocument:options=>{calls.push({path:options.filePath,type:options.fileType,menu:options.showMenu});options.success();}};
 await openNativeRegulation('management',{guest:true,platform});assert.deepEqual(calls.at(-1),{path:'temporary-pdf',type:'pdf',menu:true});assert.equal(calls.includes('save'),false);await openNativeRegulation('management',{guest:true,platform,save:true});assert.equal(calls.includes('save'),true);assert.equal(calls.at(-1).path,'saved-pdf');const count=calls.length;await assert.rejects(openNativeRegulation('management',{guest:true,platform,isCurrent:()=>false}),error=>error.code==='DOCUMENT_CANCELLED');assert.equal(calls.length,count+1);
});
