export const ASSET_IMPORT_LIMIT=5000;
const headers=['器材ID','资产编号'];
const cellText=cell=>String(cell?.value??'');
const populated=cell=>!!cellText(cell).trim()||!!cell?.formula;
const heading=value=>value.replace(/\s/g,'').toUpperCase();

export function hasAssetHeader(sheet){
  const row=sheet?.rows.find(entry=>entry.row===1);
  return !!row&&headers.every((label,index)=>!row.values[index]?.formula&&heading(cellText(row.values[index]))===label)&&!row.values.slice(2).some(populated);
}

export function assetImportRows(sheet){
  if(!hasAssetHeader(sheet))throw Error('请在第一行填写“器材ID”和“资产编号”两列表头');
  const result=[];
  for(const row of sheet.rows){
    if(row.row===1||!row.values.some(populated))continue;
    if(row.values.some(cell=>cell?.formula))throw Error(`第 ${row.row} 行包含公式，请将编号改为文本`);
    if(row.values.some(cell=>cell?.unsafeIdentifierNumber))throw Error(`第 ${row.row} 行有无法准确识别的数字编号，请使用原始编号文本`);
    if(row.values.slice(2).some(populated))throw Error(`第 ${row.row} 行多于两列，请检查表格`);
    result.push({row:row.row,equipmentId:cellText(row.values[0]),assetNumber:cellText(row.values[1])});
    if(result.length>ASSET_IMPORT_LIMIT)throw Error(`每次最多导入 ${ASSET_IMPORT_LIMIT} 行`);
  }
  if(!result.length)throw Error('表格中没有资产编号');
  return result;
}

export function pastedAssetImportRows(value){
  const lines=String(value||'').trim().split(/\r?\n/);
  const rows=lines.map((line,index)=>({row:index+1,values:line.split('\t').map(value=>({value,formula:value.trimStart().startsWith('=')}))}));
  return assetImportRows({rows});
}
