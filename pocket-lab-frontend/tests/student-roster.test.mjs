import test from 'node:test';
import assert from 'node:assert/strict';
import {parseRosterText,detectHeader,mappedRows} from '../public/features/xlsx-reader.js';

test('manual roster preserves identity strings, optional grade/phone and empty positions',()=>{
 const rows=parseRosterText('0123456789,张三,,2026级,13800000000\n\n2026000002 李四\n2026000003\t王五\t通信2601\t2025级\t+86 13800000000\n,缺学号\n2026000005,,测试班\n2026000006,额外,班级,级,13900000000,多余列');
 assert.equal(rows.length,6);assert.equal(rows[0].account,'0123456789');assert.equal(rows[0].className,'');assert.equal(rows[0].grade,'2026级');assert.equal(rows[0].phone,'13800000000');assert.equal(rows[1].row,3);assert.equal(rows[1].phone,'');assert.equal(rows[2].phone,'+86 13800000000');assert.equal(rows[3].account,'');assert.equal(rows[4].name,'');assert.ok(rows[5].formatError);
 assert.equal(parseRosterText('0123456789,张 三,,,')[0].name,'张 三');
});
test('Excel identity headers are required on row one while grade, class and phone remain optional',()=>{
 const sheet={rows:[{row:1,values:['学号','姓名','当前所在级','手机号'].map(value=>({value}))},{row:2,values:[{value:'0123456789'},{value:'张三'},{value:'2026级'},{value:'13800000000'}]},{row:4,values:[{value:'2026000003'},{value:'公式'},{value:'=A1',formula:true}]}]};
 const header=detectHeader(sheet);assert.equal(header.mapping.className,-1);assert.equal(header.mapping.grade,2);assert.equal(header.mapping.phone,3);const rows=mappedRows(sheet,header.row,header.mapping);assert.equal(rows[0].account,'0123456789');assert.equal(rows[0].className,'');assert.equal(rows[0].grade,'2026级');assert.equal(rows[0].phone,'13800000000');assert.equal(rows[1].formula,true);assert.equal(detectHeader({rows:[{...sheet.rows[0],row:2}]}),null);
});


test('teacher manual roster has four columns and preserves leading zeroes and optional positions',()=>{
 const rows=parseRosterText('001234,张老师,,13800000000\n00123456 李老师\n00001234,王老师,电子系,\n001235,额外,部门,13800000000,不允许的年级','teacher');
 assert.equal(rows[0].account,'001234');assert.equal(rows[0].className,'');assert.equal(rows[0].phone,'13800000000');assert.equal(rows[0].grade,'');
 assert.equal(rows[1].account,'00123456');assert.equal(rows[1].phone,'');assert.equal(rows[2].className,'电子系');assert.equal(rows[2].grade,'');assert.equal(rows[3].formatError,'多于四列');
});

test('teacher Excel headers and mapping stay separate from student grade columns',()=>{
 const sheet={rows:[{row:1,values:['教师工号','教师姓名','部门（选填）','手机号（选填）','当前所在级'].map(value=>({value}))},{row:2,values:['00123456','张老师','电子系','13800000000','不应导入'].map(value=>({value}))},{row:3,values:[{value:'001234'},{value:'公式老师'},{value:'部门',formula:true}]}]};
 assert.equal(detectHeader(sheet),null);const header=detectHeader(sheet,'teacher');assert.equal(header.mapping.account,0);assert.equal(header.mapping.className,2);assert.equal(header.mapping.phone,3);assert.equal(header.mapping.grade,undefined);
 const rows=mappedRows(sheet,header.row,header.mapping,'teacher');assert.equal(rows[0].account,'00123456');assert.equal(rows[0].className,'电子系');assert.equal(rows[0].phone,'13800000000');assert.equal(rows[0].grade,'');assert.equal(rows[1].formula,true);
 assert.equal(detectHeader({rows:[{row:1,values:['学号','姓名'].map(value=>({value}))}]},'teacher'),null);
});

test('teacher required headers work without optional department or phone and unknown types are rejected',()=>{
 const sheet={rows:[{row:1,values:['工号','姓名'].map(value=>({value}))},{row:2,values:['001234','张老师'].map(value=>({value}))}]};
 const header=detectHeader(sheet,'teacher');assert.equal(header.mapping.className,-1);assert.equal(header.mapping.phone,-1);assert.equal(mappedRows(sheet,1,header.mapping,'teacher')[0].phone,'');
 assert.throws(()=>parseRosterText('001234 张老师','admin'),/学生或教师/);
});
