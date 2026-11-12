export const REGULATION_CONTENT_KINDS=Object.freeze(['management','usage']);
export const REGULATION_BODY_LIMIT=100000;
export const REGULATION_TITLES=Object.freeze({management:'口袋实验室管理办法（试行）',usage:'口袋实验室线上服务平台使用须知'});
export function regulationKind(value){return typeof value==='string'&&REGULATION_CONTENT_KINDS.includes(value)?value:null;}
export function regulationContentPath(kind,{publicRead=false}={}){if(!regulationKind(kind))throw new Error('文档不存在');return (publicRead?'/public':'')+'/regulations/'+kind+'/content';}
export function validateRegulationBody(body){if(typeof body!=='string'||!body.trim())return '请输入正文内容';if(body.length>REGULATION_BODY_LIMIT)return '正文不能超过100000个字符';return '';}
export function regulationWriteBody(body,version){const error=validateRegulationBody(body);if(error)throw new Error(error);if(!Number.isInteger(version)||version<0)throw new Error('请先读取正文的最新版本');return {body,version};}
export function readableRegulationBody(record){return record?.format==='plain'&&record.published===true&&typeof record.body==='string'&&record.body.trim()?record.body:'';}
export function regulationParagraphs(body){return typeof body==='string'?body.replace(/\r\n?/g,'\n').split(/\n[\t ]*\n/):[];}
export function regulationUpdatedAt(value){const date=new Date(value);return value&&Number.isFinite(date.getTime())?date.toLocaleString('zh-CN',{year:'numeric',month:'long',day:'numeric',hour:'2-digit',minute:'2-digit'}):'';}
export function sameRegulationText(first,second){return typeof first==='string'&&typeof second==='string'&&first.replace(/\r\n?/g,'\n')===second.replace(/\r\n?/g,'\n');}
