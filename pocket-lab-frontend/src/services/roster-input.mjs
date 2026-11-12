// Native text import keeps the same explicit format contract as the browser XLSX tool.
// The browser entry lives in public/ and is not importable into a mini-program bundle.
export function parseImportText(text,accountType='student',format='legacy'){
 if(accountType==='mixed'&&format==='unified-v2')return String(text).split(/\r?\n/).flatMap((line,index)=>{if(!line.trim())return [];const parts=/[,，\t]/.test(line)?line.split(/[,，\t]/).map(part=>part.trim()):line.trim().split(/[ \u3000]+/);return [{row:index+1,account:parts[0]||'',name:parts[1]||'',className:parts[2]||'',yearOrTeacher:parts[3]||'',phone:parts[4]||'',formula:false,formatError:parts.length>5?'多于五列':''}];});
  if(!['student','teacher'].includes(accountType))throw new Error('请选择学生或教师名单。');
 if(!['legacy','unified-v1'].includes(format))throw new Error('请选择新版统一五列或旧版兼容格式。');
 const teacher=accountType==='teacher',unified=format==='unified-v1',maxColumns=unified?5:teacher?4:5;
 return String(text).split(/\r?\n/).flatMap((line,index)=>{if(!line.trim())return [];const parts=/[,，\t]/.test(line)?line.split(/[,，\t]/).map(part=>part.trim()):line.trim().split(/[ \u3000]+/);const row={row:index+1,account:parts[0]||'',name:parts[1]||'',className:parts[2]||'',grade:unified||teacher?'':parts[3]||'',phone:parts[unified||!teacher?4:3]||'',formatError:parts.length>maxColumns?(maxColumns===4?'多于四列':'多于五列'):'',formula:false};if(unified)row[teacher?'teacherMarker':'enrollmentYear']=parts[3]||'';return [row];});
}

export function missingRequiredRows(rows,format='legacy'){
 if(!['unified-v1','unified-v2'].includes(format))return [];
 return rows.filter(row=>['account','name','className','grade','enrollmentYear','teacherMarker','yearOrTeacher','phone'].some(key=>String(row[key]??'').trim()!=='')&&(!String(row.account??'').trim()||!String(row.name??'').trim())).map(row=>row.row);
}
