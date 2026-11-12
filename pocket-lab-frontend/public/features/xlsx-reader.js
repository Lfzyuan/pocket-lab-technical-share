// Browser-only XLSX extraction; no macros, links or formulas are executed.
const decoder = new TextDecoder();
const nodes = (element, name) => [...element.getElementsByTagNameNS('*', name)];
function xml(text) { const doc = new DOMParser().parseFromString(text, 'application/xml'); if (nodes(doc,'parsererror').length) throw new Error('Excel 文件结构损坏。'); return doc; }
function normalizePath(path) { const parts = []; for (const part of path.split('/')) { if (part === '..') parts.pop(); else if (part && part !== '.') parts.push(part); } return parts.join('/'); }
export async function readXlsx(file,{checkIdentifierPrecision=false}={}) {
  if (!/\.xlsx$/i.test(file.name)) throw new Error('请选择 .xlsx 文件，旧版 .xls 请另存为 .xlsx。');
  if (file.size > 10485760) throw new Error('文件不能超过 10 MB。');
  const buffer = await file.arrayBuffer(), data = new DataView(buffer), bytes = new Uint8Array(buffer);
  let end = -1;
  for (let i = buffer.byteLength - 22; i >= Math.max(0, buffer.byteLength - 65557); i--) if (data.getUint32(i,true) === 0x06054b50) { end = i; break; }
  if (end < 0) throw new Error('无法读取 Excel 压缩结构。');
  const count = data.getUint16(end+10,true); let offset = data.getUint32(end+16,true), total = 0;
  if (count > 2000) throw new Error('工作簿包含过多内容。');
  const entries = new Map();
  for (let i=0;i<count;i++) {
    if (offset+46 > bytes.length || data.getUint32(offset,true) !== 0x02014b50) throw new Error('Excel 文件目录损坏。');
    const flags=data.getUint16(offset+8,true),method=data.getUint16(offset+10,true),compressed=data.getUint32(offset+20,true),size=data.getUint32(offset+24,true),nameLength=data.getUint16(offset+28,true),extra=data.getUint16(offset+30,true),comment=data.getUint16(offset+32,true),local=data.getUint32(offset+42,true);
    if (flags & 1) throw new Error('请先解除 Excel 文件密码保护。');
    total += size; if (total > 67108864 || size > 33554432) throw new Error('工作簿解压后过大，请仅保留名单信息表。');
    const name=decoder.decode(bytes.subarray(offset+46,offset+46+nameLength)); entries.set(name,{method,compressed,size,local}); offset+=46+nameLength+extra+comment;
  }
  async function read(name, optional=false) {
    const entry=entries.get(name); if (!entry) { if(optional)return ''; throw new Error('缺少 Excel 工作表内容。'); }
    const {local,method,compressed,size}=entry;
    if (local+30>bytes.length || data.getUint32(local,true)!==0x04034b50) throw new Error('Excel 内容损坏。');
    const start=local+30+data.getUint16(local+26,true)+data.getUint16(local+28,true);
    if(start+compressed>bytes.length)throw new Error('Excel 内容不完整。');
    let result;
    if(method===0)result=bytes.slice(start,start+compressed);
    else if(method===8) {
      const reader=new Blob([bytes.slice(start,start+compressed)]).stream().pipeThrough(new DecompressionStream('deflate-raw')).getReader();
      const chunks=[];let length=0;
      while(true){const {done,value}=await reader.read();if(done)break;length+=value.length;if(length>size || length>33554432){await reader.cancel();throw new Error('Excel 解压大小异常。');}chunks.push(value);}
      result=new Uint8Array(length);let position=0;for(const chunk of chunks){result.set(chunk,position);position+=chunk.length;}
    } else throw new Error('不支持此 Excel 压缩格式。');
    if(result.length!==size)throw new Error('Excel 内容长度异常。');
    return decoder.decode(result);
  }
  const shared=await read('xl/sharedStrings.xml',true);
  const strings=shared?nodes(xml(shared),'si').map(si=>nodes(si,'t').map(t=>t.textContent).join('')):[];
  const styles=await read('xl/styles.xml',true), formats=new Map(),styleFormats=[];
  if(styles){const doc=xml(styles);nodes(doc,'numFmt').forEach(node=>formats.set(node.getAttribute('numFmtId'),node.getAttribute('formatCode')));const cellXfs=nodes(doc,'cellXfs')[0];if(cellXfs)nodes(cellXfs,'xf').forEach(node=>styleFormats.push(formats.get(node.getAttribute('numFmtId'))));}
  const relationships=new Map(nodes(xml(await read('xl/_rels/workbook.xml.rels')),'Relationship').filter(rel=>rel.getAttribute('TargetMode')!=='External').map(rel=>[rel.getAttribute('Id'),rel.getAttribute('Target')]));
  const workbook=xml(await read('xl/workbook.xml')), sheets=[];
  for(const sheet of nodes(workbook,'sheet')) {
    const relationship=sheet.getAttributeNS('http://schemas.openxmlformats.org/officeDocument/2006/relationships','id');const target=relationships.get(relationship);if(!target)continue;
    const path=normalizePath(target.startsWith('/')?target:'xl/'+target),doc=xml(await read(path));
    const rows=[];let cells=0;
    for(const row of nodes(doc,'row')) {
      if(rows.length>10000)throw new Error('单张表最多读取 10000 行，请移除多余内容。');
      const values=[];
      for(const cell of nodes(row,'c')) {
        if(++cells>300000)throw new Error('表格内容过多。');
        const ref=cell.getAttribute('r')||'',letters=/^[A-Z]+/.exec(ref)?.[0];if(!letters)continue;
        let col=0;for(const letter of letters)col=col*26+letter.charCodeAt(0)-64;if(col>256)continue;
        const type=cell.getAttribute('t'),raw=nodes(cell,'v')[0]?.textContent||'';
        let value=type==='s'?(strings[Number(raw)]||''):type==='inlineStr'?nodes(cell,'t').map(t=>t.textContent).join(''):raw;
        if((!type || type==='n') && value!=='' && Number.isFinite(Number(value))) {
          const number=Number(value);value=String(number);
          const format=styleFormats[Number(cell.getAttribute('s')||0)];if(Number.isSafeInteger(number)&&/^0{2,20}$/.test(format||''))value=value.padStart(format.length,'0');
        }
        const unsafeIdentifierNumber=checkIdentifierPrecision&&(!type||type==='n')&&raw!==''&&(!Number.isSafeInteger(Number(raw))||Math.abs(Number(raw))>=1e15);
        values[col-1]={value,formula:nodes(cell,'f').length>0,...(unsafeIdentifierNumber?{unsafeIdentifierNumber:true}:{})};
      }
      if(values.some(cell=>cell?.value!==''||cell?.formula))rows.push({row:Number(row.getAttribute('r')),values});
    }
    sheets.push({name:sheet.getAttribute('name'),rows});
  }
  if(!sheets.length)throw new Error('文件没有可读取的工作表。');
  return sheets;
}
export const fields={account:['学号','学生学号','账号','学生账号'],name:['姓名','名字','学生姓名'],className:['班级','班级名称','行政班','行政班级'],grade:['当前所在级','年级','所在级','当前年级'],phone:['手机号','手机号码','手机','联系电话','联系电话号码','联系方式','移动电话']};
export const teacherFields={account:['工号','教师工号','职工号','教职工号','账号','教师账号'],name:['姓名','名字','教师姓名'],className:['部门','所在部门','部门名称','院系','院系名称'],phone:fields.phone};
export function rosterFields(accountType='student',format='legacy'){
 if(accountType==='mixed'&&format==='unified-v2')return {account:['学号/工号','学号／工号'],name:['姓名'],className:['班级/院系','班级／院系'],yearOrTeacher:['年级/老师','年级／老师'],phone:fields.phone};
 if(!['student','teacher'].includes(accountType))throw new Error('请选择学生或教师名单。');
 if(!['legacy','unified-v1'].includes(format))throw new Error('请选择新版统一五列或旧版兼容格式。');
 if(format==='legacy')return accountType==='teacher'?teacherFields:fields;
 return {account:['学号/工号','学号／工号'],name:['姓名'],className:['班级/院系','班级／院系'],[accountType==='teacher'?'teacherMarker':'enrollmentYear']:['年级/老师','年级／老师'],phone:fields.phone};
}
const normalize=value=>String(value||'').replace(/\s/g,'').replace(/[（(].*?[）)]/g,'');
export function detectHeader(sheet,accountType='student',format='legacy'){
 const row=sheet.rows.find(r=>r.row===1);if(!row)return null;
 const mapping={};for(const [field,aliases]of Object.entries(rosterFields(accountType,format)))mapping[field]=row.values.findIndex(cell=>aliases.includes(normalize(cell?.value)));
 return ['account','name'].every(field=>mapping[field]>=0)?{row:1,mapping,format,headers:Array.from(row.values,cell=>cell?.value||'')}:null;
}
export function mappedRows(sheet,header,mapping,accountType='student',format='legacy'){const keys=Object.keys(rosterFields(accountType,format));return sheet.rows.filter(row=>row.row>header).map(row=>{const result=format==='unified-v2'?{row:row.row,formula:false,formatError:'',account:'',name:'',className:'',yearOrTeacher:'',phone:''}:{row:row.row,formula:false,account:'',name:'',className:'',grade:'',phone:''};for(const field of keys){const cell=row.values[mapping[field]];result[field]=format==='unified-v2'?String(cell?.value??''):cell?.value||'';result.formula ||= !!cell?.formula;}return result;}).filter(row=>keys.some(field=>row[field]!=='')||(format==='unified-v2'&&(row.formula||row.formatError)));}
export function parseRosterText(text,accountType='student',format='legacy'){
 if(accountType==='mixed'&&format==='unified-v2')return String(text).split(/\r?\n/).flatMap((line,index)=>{if(!line.trim())return [];const parts=/[,，\t]/.test(line)?line.split(/[,，\t]/).map(part=>part.trim()):line.trim().split(/[ \u3000]+/);return [{row:index+1,account:parts[0]||'',name:parts[1]||'',className:parts[2]||'',yearOrTeacher:parts[3]||'',phone:parts[4]||'',formula:false,formatError:parts.length>5?'多于五列':''}];});
 const teacher=accountType==='teacher',unified=format==='unified-v1';rosterFields(accountType,format);const maxColumns=unified?5:teacher?4:5;
 return String(text).split(/\r?\n/).flatMap((line,index)=>{if(!line.trim())return [];const parts=/[,，\t]/.test(line)?line.split(/[,，\t]/).map(part=>part.trim()):line.trim().split(/[ \u3000]+/);const row={row:index+1,account:parts[0]||'',name:parts[1]||'',className:parts[2]||'',grade:unified||teacher?'':parts[3]||'',phone:parts[unified||!teacher?4:3]||'',formatError:parts.length>maxColumns?(maxColumns===4?'多于四列':'多于五列'):'',formula:false};if(unified)row[teacher?'teacherMarker':'enrollmentYear']=parts[3]||'';return [row];});
}

export function missingRequiredRows(rows,format='legacy'){
 if(!['unified-v1','unified-v2'].includes(format))return [];
 return rows.filter(row=>['account','name','className','grade','enrollmentYear','teacherMarker','yearOrTeacher','phone'].some(key=>String(row[key]??'').trim()!=='')&&(!String(row.account??'').trim()||!String(row.name??'').trim())).map(row=>row.row);
}
