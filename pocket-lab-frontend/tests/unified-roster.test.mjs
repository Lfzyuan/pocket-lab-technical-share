import test from 'node:test';
import assert from 'node:assert/strict';
import {parseRosterText,detectHeader,mappedRows} from '../public/features/xlsx-reader.js';
import {parseImportText,missingRequiredRows as nativeMissing} from '../src/services/roster-input.mjs';
import {missingRequiredRows as browserMissing} from '../public/features/xlsx-reader.js';

for(const [name,parse]of [['browser',parseRosterText],['native',parseImportText]]){
 test(name+' unified student keeps raw enrollment year separate from current grade and preserves optional empty columns',()=>{
  const rows=parse('0123240001,张同学,,2024级,13800000000\n0123240002,李同学\n0123240003,王同学,测试班,2024,','student','unified-v1');
  assert.equal(rows[0].account,'0123240001');assert.equal(rows[0].className,'');assert.equal(rows[0].enrollmentYear,'2024级');assert.equal(rows[0].grade,'');assert.equal(rows[0].phone,'13800000000');
  assert.equal(rows[1].className,'');assert.equal(rows[1].enrollmentYear,'');assert.equal(rows[1].phone,'');assert.equal(rows[2].enrollmentYear,'2024');assert.equal(Object.hasOwn(rows[0],'confirmEnrollmentYear'),false);
 });
 test(name+' unified teacher retains empty or explicit marker and reads phone from fifth column without inferring identity',()=>{
  const rows=parse('001234,张老师,,老师,13800000000\n00001234,李老师,,,\n001235,待校验,,2024级,','teacher','unified-v1');
  assert.equal(rows[0].account,'001234');assert.equal(rows[0].teacherMarker,'老师');assert.equal(rows[0].grade,'');assert.equal(rows[0].phone,'13800000000');assert.equal(rows[1].account,'00001234');assert.equal(rows[1].teacherMarker,'');assert.equal(rows[1].className,'');assert.equal(rows[2].teacherMarker,'2024级');assert.equal(Object.hasOwn(rows[0],'enrollmentYear'),false);
 });
 test(name+' unified partial rows are retained for required-field server validation',()=>{
  const rows=parse('0123240001,有效同学\n0123240002,,,2024,\n,缺账号同学,,,','student','unified-v1');assert.equal(rows.length,3);assert.equal(rows[1].account,'0123240002');assert.equal(rows[1].name,'');assert.equal(rows[2].account,'');assert.equal(rows[2].name,'缺账号同学');
 });
 test(name+' explicit legacy formats retain current grade and four-column teacher phone positions',()=>{
  const student=parse('0123240001,张同学,,2026级,13800000000','student','legacy')[0],teacher=parse('001234,张老师,,13800000000','teacher','legacy')[0];
  assert.equal(student.grade,'2026级');assert.equal(Object.hasOwn(student,'enrollmentYear'),false);assert.equal(teacher.phone,'13800000000');assert.equal(Object.hasOwn(teacher,'teacherMarker'),false);
  assert.equal(parse('001234,老师,,老师,13800000000','teacher','legacy')[0].formatError,'多于四列');assert.equal(parse('001234,老师,,老师,13800000000,额外','teacher','unified-v1')[0].formatError,'多于五列');
  assert.throws(()=>parse('001234 老师','teacher','guess'),/格式/);
 });
}
const sheet=(headers,values)=>({rows:[{row:1,values:headers.map(value=>({value}))},{row:2,values:values.map(value=>({value}))}]});
test('unified header selection is explicit and maps teacher marker and student year independently',()=>{
 const workbook=sheet(['学号/工号','姓名','班级/院系','年级/老师','手机号（选填）'],['001234','张老师','','老师','13800000000']);
 assert.equal(detectHeader(workbook,'teacher','legacy'),null);const teacher=detectHeader(workbook,'teacher','unified-v1');assert.equal(teacher.mapping.teacherMarker,3);assert.equal(teacher.mapping.grade,undefined);const row=mappedRows(workbook,1,teacher.mapping,'teacher','unified-v1')[0];assert.equal(row.teacherMarker,'老师');assert.equal(row.phone,'13800000000');assert.equal(row.grade,'');
 const student=detectHeader(workbook,'student','unified-v1');assert.equal(student.mapping.enrollmentYear,3);assert.equal(student.mapping.teacherMarker,undefined);
 assert.equal(detectHeader(sheet(['工号','姓名','部门','手机号'],['001234','老师','','13800000000']),'teacher','unified-v1'),null);
});
test('unified optional headers may be absent while mapped formula flags and leading zeroes are preserved',()=>{
 const minimal=sheet(['学号/工号','姓名'],['0123240001','张同学']),header=detectHeader(minimal,'student','unified-v1');assert.equal(header.mapping.className,-1);assert.equal(header.mapping.enrollmentYear,-1);assert.equal(mappedRows(minimal,1,header.mapping,'student','unified-v1')[0].enrollmentYear,'');
 const workbook=sheet(['学号/工号','姓名','班级/院系','年级/老师','手机号'],['00001234','老师','','老师','']);workbook.rows[1].values[3].formula=true;const mapped=detectHeader(workbook,'teacher','unified-v1');assert.equal(mappedRows(workbook,1,mapped.mapping,'teacher','unified-v1')[0].formula,true);assert.equal(mappedRows(workbook,1,mapped.mapping,'teacher','unified-v1')[0].account,'00001234');
});


test('new-format required-field precheck blocks partial rows but accepts empty optional columns and keeps legacy behavior',()=>{
 const rows=parseRosterText('0123240001,有效同学\n0123240002,,,2024,\n,缺账号,,,\n,,院系,,\n,,,,','student','unified-v1');
 for(const missing of [browserMissing,nativeMissing]){assert.deepEqual(missing(rows,'unified-v1'),[2,3,4]);assert.deepEqual(missing(rows,'legacy'),[]);assert.deepEqual(missing([{row:1,account:'0123240001',name:'同学',className:'',enrollmentYear:'',phone:''}],'unified-v1'),[]);}
});
