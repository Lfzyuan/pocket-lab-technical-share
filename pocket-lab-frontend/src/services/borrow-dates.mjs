export function validBorrowDate(value){
 if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)||value<'0001-01-01')return '';
 const date=new Date(value+'T00:00:00+08:00');if(Number.isNaN(date.getTime()))return '';
 return new Date(date.getTime()+8*3600000).toISOString().slice(0,10)===value?value:'';
}
export function chinaDate(now=new Date()){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date(now));}
export function formatBorrowDate(value){
 if(validBorrowDate(value))return value;
 if(typeof value!=='string'||!value.trim()||Number.isNaN(Date.parse(value)))return '—';
 return chinaDate(new Date(value));
}

export function formatBorrowMinute(value){
 if(validBorrowDate(value))return value;
 if(typeof value!=='string'||!value.trim()||Number.isNaN(Date.parse(value)))return '—';
 const parts=new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).formatToParts(new Date(value));
 const fields=Object.fromEntries(parts.map(({type,value:part})=>[type,part]));
 return `${fields.year}-${fields.month}-${fields.day} ${fields.hour}:${fields.minute}`;
}
