import test from 'node:test';
import assert from 'node:assert/strict';
import {assetImportRows,pastedAssetImportRows} from '../src/services/asset-import.mjs';
const cells=(...values)=>values.map(value=>({value}));
const header={row:1,values:cells('器材 ID','资产编号')};

test('asset spreadsheet retains literal values and original row numbers for server preview',()=>{
 const rows=[header,{row:3,values:cells('CAT01','00001234')},{row:6,values:cells('CAT02','KdJ-001')},{row:7,values:cells('CAT02','')}];
 assert.deepEqual(assetImportRows({rows}),[{row:3,equipmentId:'CAT01',assetNumber:'00001234'},{row:6,equipmentId:'CAT02',assetNumber:'KdJ-001'},{row:7,equipmentId:'CAT02',assetNumber:''}]);
 assert.equal(pastedAssetImportRows('器材ID\t资产编号\nCAT01\t001')[0].assetNumber,'001');
});

test('a formula, extra column or imprecise numeric label cannot enter a frozen import plan',()=>{
 assert.throws(()=>assetImportRows({rows:[header,{row:8,values:[{value:'CAT01'},{value:'001',formula:true}]}]}),/第 8 行包含公式/);
 assert.throws(()=>assetImportRows({rows:[header,{row:9,values:[{value:'CAT01'},{value:'1234567890123450',unsafeIdentifierNumber:true}]}]}),/第 9 行有无法准确识别/);
 assert.throws(()=>assetImportRows({rows:[header,{row:4,values:cells('CAT01','001','extra')}]}),/第 4 行多于两列/);
});
