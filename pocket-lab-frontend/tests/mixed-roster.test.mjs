import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {parseRosterText,detectHeader,mappedRows,missingRequiredRows as browserMissing} from '../public/features/xlsx-reader.js';
import {parseImportText,missingRequiredRows as nativeMissing} from '../src/services/roster-input.mjs';
import {validateTemplateZip,downloadAccountTemplate,ACCOUNT_TEMPLATE_NAME} from '../src/services/account-template.mjs';
const template=await readFile(new URL('../public/static/templates/account-import.xlsx',import.meta.url));
for(const [name,parse]of [['browser',parseRosterText],['native',parseImportText]]){
 test(name+' mixed v2 preserves raw D and every identity as strings without choosing row roles',()=>{
  const rows=parse('0123240001,学生甲,,2024级,13800000000\n001234,教师甲,电子系,老师,\n00001234,教师乙,,,\n0123240002,学生乙','mixed','unified-v2');
  assert.deepEqual(rows.map(row=>[row.account,row.yearOrTeacher,row.phone]),[['0123240001','2024级','13800000000'],['001234','老师',''],['00001234','',''],['0123240002','','']]);for(const row of rows)for(const key of ['accountType','role','grade','enrollmentYear','teacherMarker','confirmEnrollmentYear'])assert.equal(Object.hasOwn(row,key),false);
  const conflict=parse('0123240003,矛盾学生,,老师,\n001235,矛盾教师,,2024,','mixed','unified-v2');assert.equal(conflict[0].yearOrTeacher,'老师');assert.equal(conflict[1].yearOrTeacher,'2024');
 });
 test(name+' mixed v2 retains incomplete rows for server feedback and detects extra columns',()=>{
  const rows=parse('001234,\n,缺编号\n,,院系,老师,\n0123240001,有效\n,,,,\n001235,过多,,,13800000000,额外','mixed','unified-v2');for(const missing of [browserMissing,nativeMissing])assert.deepEqual(missing(rows,'unified-v2'),[1,2,3]);assert.equal(rows[3].yearOrTeacher,'');assert.equal(rows[4].account,'');assert.equal(rows[5].formatError,'多于五列');
 });
}
test('mixed headers ignore sheet identity and preserve numeric values, textual zeroes and empty-cache formulas',()=>{
 const sheet={name:'学生名单',rows:[{row:1,values:['学号/工号','姓名','班级/院系','年级/老师','手机号(选填)'].map(value=>({value}))},{row:2,values:[{value:123456},{value:'数字工号'},{value:''},{value:'老师'},{value:13800000000}]},{row:3,values:['00123456','文本工号','','',''].map(value=>({value}))},{row:4,values:[{value:'',formula:true}]}]},header=detectHeader(sheet,'mixed','unified-v2');assert.deepEqual(header.mapping,{account:0,name:1,className:2,yearOrTeacher:3,phone:4});const rows=mappedRows(sheet,1,header.mapping,'mixed','unified-v2');assert.equal(rows[0].account,'123456');assert.equal(rows[0].phone,'13800000000');assert.equal(rows[0].yearOrTeacher,'老师');assert.equal(rows[1].account,'00123456');assert.equal(rows[2].formula,true);assert.equal(rows[2].account,'');
});
test('template ZIP validation requires real nonempty workbook structure and rejects truncated or HTML downloads',()=>{
 assert.equal(validateTemplateZip(template).bytes,5271);for(const bytes of [new Uint8Array(),new TextEncoder().encode('<html>error</html>'),template.subarray(0,template.length-30)])assert.throws(()=>validateTemplateZip(bytes),/模版/);const bad=Buffer.from(template);bad.writeUInt32LE(0,0);assert.throws(()=>validateTemplateZip(bad),/模版/);
});
function nativeFixture(bytes=template){const calls=[],platform={downloadFile(options){calls.push({step:'download',url:options.url,header:options.header});options.success({statusCode:200,tempFilePath:'temporary-template'});return {abort(){}};},getFileSystemManager:()=>({readFile(options){calls.push({step:'read'});options.success({data:bytes});},saveFile(options){calls.push({step:'save',path:options.tempFilePath});options.success({savedFilePath:'saved-template.xlsx'});}}),openDocument(options){calls.push({step:'open',path:options.filePath,type:options.fileType});options.success();}};return {platform,calls};}
test('native template download validates actual bytes before saving and opening without credentials',async()=>{
 const {platform,calls}=nativeFixture(),result=await downloadAccountTemplate({platform,origin:'https://isolated-template.invalid'});assert.deepEqual(calls.map(x=>x.step),['download','read','save','open']);assert.equal(calls[0].header,undefined);assert.match(calls[0].url,/\/static\/templates\/account-import\.xlsx\?v=/);assert.equal(result.name,ACCOUNT_TEMPLATE_NAME);assert.equal(calls.at(-1).type,'xlsx');
});
test('empty or cancelled native template downloads never save an invalid or late file',async()=>{
 const empty=nativeFixture(new Uint8Array());await assert.rejects(downloadAccountTemplate({platform:empty.platform}),/模版/);assert.equal(empty.calls.some(x=>x.step==='save'),false);const stale=nativeFixture();assert.equal(await downloadAccountTemplate({platform:stale.platform,isCurrent:()=>false}),null);assert.deepEqual(stale.calls.map(x=>x.step),['download']);
});
