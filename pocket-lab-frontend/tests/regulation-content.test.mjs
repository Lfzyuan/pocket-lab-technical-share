import test from 'node:test';
import assert from 'node:assert/strict';
import {regulationKind,regulationContentPath,validateRegulationBody,regulationWriteBody,readableRegulationBody,regulationParagraphs,sameRegulationText} from '../src/services/regulation-content.mjs';
import {createGuestClient} from '../src/services/guest-client.mjs';

test('content routes accept only the two document kinds without traversal or query injection',()=>{
 for(const kind of ['management','usage']){assert.equal(regulationKind(kind),kind);assert.equal(regulationContentPath(kind),'/regulations/'+kind+'/content');assert.equal(regulationContentPath(kind,{publicRead:true}),'/public/regulations/'+kind+'/content');}
 for(const kind of [undefined,null,'Management','../management','usage?mine=true','management/content']){assert.equal(regulationKind(kind),null);assert.throws(()=>regulationContentPath(kind),/不存在/);}
});
test('plain document validation counts UTF16 exactly and requires nonblank content',()=>{
 for(const value of [null,undefined,4,'',' \t\r\n'])assert.ok(validateRegulationBody(value));
 for(const value of ['正文','字'.repeat(100000),'😀'.repeat(50000)])assert.equal(validateRegulationBody(value),'');
 assert.match(validateRegulationBody('字'.repeat(100001)),/100000/);assert.match(validateRegulationBody('😀'.repeat(50001)),/100000/);
});
test('saving preserves literal whitespace and markup with the exact loaded version',()=>{
 const body='  第一段\r\n\r\n<script>alert(1)</script>\n**普通文本**  ';assert.deepEqual(regulationWriteBody(body,3),{body,version:3});assert.deepEqual(regulationWriteBody('新正文',0),{body:'新正文',version:0});
 for(const version of [null,undefined,'3',-1,1.5])assert.throws(()=>regulationWriteBody(body,version),/最新版本/);
 assert.throws(()=>regulationWriteBody('  ',1),/请输入/);
});
test('only published nonblank plain content is readable and an absent body stays absent',()=>{
 const valid={format:'plain',published:true,body:' 第一行\n第二行 '};assert.equal(readableRegulationBody(valid),valid.body);
 for(const row of [null,{...valid,published:false},{...valid,format:'html'},{...valid,body:null},{...valid,body:' \n'}])assert.equal(readableRegulationBody(row),'');
});
test('paragraph display normalizes line endings while preserving literal markup and line breaks',()=>{
 assert.deepEqual(regulationParagraphs('第一行\r\n同段第二行\r\n \r\n<script>不是脚本</script>\r末行'),['第一行\n同段第二行','<script>不是脚本</script>\n末行']);assert.deepEqual(regulationParagraphs(null),[]);
});
test('anonymous content reads stay exact GET projections without accepting writes or private options',async()=>{
 const calls=[],client=createGuestClient({transport:async request=>{calls.push(request);return {statusCode:200,data:{code:'OK',data:{kind:'management',format:'plain',body:'公开正文',published:true}}};}});
 for(const kind of ['management','usage'])await client.request('/public/regulations/'+kind+'/content');
 assert.equal(calls.length,2);for(const request of calls){assert.equal(request.method,'GET');assert.equal(request.withCredentials,false);assert.deepEqual(request.header,{Accept:'application/json'});assert.equal(Object.hasOwn(request,'data'),false);}
 for(const path of ['/regulations/management/content','/public/regulations/private/content','/public/regulations/management/content?mine=true','/public/regulations/management/content?version=1','/public/regulations/management/content/extra'])await assert.rejects(client.request(path));
 for(const options of [{method:'PUT'},{headers:{Authorization:'Bearer private'}},{data:{body:'forbidden'}}])await assert.rejects(client.request('/public/regulations/management/content',options));assert.equal(calls.length,2);
});

test('confirmed published content may reconcile versions only when draft text differs by line endings alone',()=>{
 assert.equal(sameRegulationText('第一行\r\n第二行\r末行','第一行\n第二行\n末行'),true);assert.equal(sameRegulationText(' 正文','正文'),false);assert.equal(sameRegulationText('正文\n','正文'),false);assert.equal(sameRegulationText('正文A','正文B'),false);assert.equal(sameRegulationText(undefined,undefined),false);
});
