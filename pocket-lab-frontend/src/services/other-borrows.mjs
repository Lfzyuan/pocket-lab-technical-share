import {validBorrowDate,chinaDate} from './borrow-dates.mjs';
import {normalizeAssetBarcode} from './asset-barcode.mjs';
const endpoint='/other-borrow-requests';

function text(value,label,limit,required=false){
  const result=String(value??'').trim();
  if(required&&!result)throw Error('请填写'+label);
  if(result.length>limit)throw Error(label+'不能超过'+limit+'字');
  return result;
}

export function shanghaiParts(value){
  if(!value||Number.isNaN(Date.parse(value)))return {date:'',time:''};
  const local=new Date(Date.parse(value)+8*3600000).toISOString();return {date:local.slice(0,10),time:local.slice(11,16)};
}
export function shanghaiInstant(date,time){
  if(!/^\d{4}-\d{2}-\d{2}$/.test(date||'')||!/^\d{2}:\d{2}$/.test(time||''))return '';
  const instant=new Date(date+'T'+time+':00+08:00');
  if(Number.isNaN(instant.getTime()))return '';
  const roundtrip=new Date(instant.getTime()+8*3600000).toISOString().slice(0,16);
  return roundtrip===date+'T'+time?instant.toISOString():'';
}
function requiredDate(value,label){const result=validBorrowDate(value);if(!result)throw Error('请选择'+label);return result;}
export function validateOtherBorrow(form,now=new Date()){
 const result={name:text(form.name,'器材名称',160,true),model:text(form.model,'型号',160),functionDescription:text(form.functionDescription,'功能',2000),purpose:text(form.purpose,'用途',500,true),issuedDate:requiredDate(form.issuedDate,'实际借出日期'),returnedDate:requiredDate(form.returnedDate,'实际归还日期')};
 if(result.returnedDate<result.issuedDate)throw Error('实际归还日期不能早于借出日期');
 if(result.returnedDate>chinaDate(now))throw Error('实际归还日期不能晚于今天');
 return result;
}

export function validateOtherCheckout(form){
  const name=text(form.name,'器材名称',160,true);
  const teacher=text(form.teacherUserId,'老师',36);
  if(!teacher||teacher==='__none__')throw Error('请填写并匹配指导老师');
  const purpose=text(form.purpose,'用途',500);
  return {workflowMode:'checkout',name,teacherUserId:teacher,...(purpose?{purpose}:{})};
}

export function createOtherBorrows(client){
  return {
    async list({page=1,pageSize=5,mine=true,status='',workflowMode='registration',keyword='',from='',to=''}={}){
      const query={page,pageSize,mine,workflowMode};
      if(keyword.trim())query.keyword=keyword.trim();
      if(from)query.from=from; if(to)query.to=to;
      if(status)query.status=status;
      const search=Object.entries(query).map(([key,value])=>key+'='+encodeURIComponent(value)).join('&');
      return (await client.request(endpoint+'?'+search)).data;
    },
    async detail(id){
      return (await client.request(endpoint+'/'+encodeURIComponent(id))).data;
    },
    create(form){
      return client.mutate(endpoint,validateOtherBorrow(form));
    },
    createCheckout(form){return client.mutate(endpoint,validateOtherCheckout(form));},
    action(row,action,{reason='',physicalAvailable=false,checked=false,assetNumber,remark=''}={}){
      if(!row.allowedActions?.includes(action))throw Error('当前状态不支持此操作，请刷新申请');
      const body={version:row.version};
      if(action==='confirm')return client.mutate(endpoint+'/'+encodeURIComponent(row.id)+'/confirm',body);
      if(action==='approve'){
        if(!physicalAvailable)throw Error('请先确认有可借实物');
        body.physicalAvailable=true;
      }
      if(['reject','return-reject'].includes(action))body.reason=text(reason,'处理原因',500,true);
      if(['issue','return-confirm','return-confirm-direct'].includes(action)){
        if(!checked)throw Error('请先完成现场核验');
        body.checked=true;
        if(action==='return-confirm-direct'&&(row.workflowMode!=='checkout'||!row.assetNumber))throw Error('现场归还须核对已绑定的资产编号');
        if(row.workflowMode==='checkout'){
          const code=normalizeAssetBarcode(assetNumber);
          if(typeof assetNumber==='string'&&assetNumber.trim()&&!code)throw Error('编号须为100字以内，且不能包含换行或控制字符');
          if(action==='issue'){
            if(code)body.assetNumber=code;
            const note=text(remark,'发放备注',500);
            if(note)body.remark=note;
          }else if(row.assetNumber){
            if(!code)throw Error('请扫描或手输发放时绑定的资产编号');
            if(code!==row.assetNumber)throw Error('归还资产编号与发放记录不匹配');
            body.assetNumber=code;
          }
        }
      }
      return client.mutate(endpoint+'/'+encodeURIComponent(row.id)+'/'+action,body);
    }
  };
}
