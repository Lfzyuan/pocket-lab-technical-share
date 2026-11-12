import {normalizeAssetBarcode} from './asset-barcode.mjs';
import {createOtherBorrows} from './other-borrows.mjs';

export const isOtherLoan=row=>row?.sourceType==='other';
export const loanRecordKey=row=>row.recordKey||((isOtherLoan(row)?'other':'normal')+':'+row.id);
export const loanRecordPath=row=>(isOtherLoan(row)?'/other-borrow-requests/':'/borrow-requests/')+encodeURIComponent(typeof row==='string'?row:row.id);

export const borrowActionLabels={approve:'批准申请',reject:'拒绝申请',issue:'确认发放','return-confirm':'确认归还'};
export function borrowFilters(value={}){
  const query={};
  for(const key of ['q','equipmentId','from','to','borrowerId'])if(String(value[key]||'').trim())query[key]=String(value[key]).trim();
  const statuses=Array.isArray(value.statuses)?value.statuses.filter(Boolean):String(value.statuses||'').split(',').filter(Boolean);
  if(statuses.length)query.statuses=[...new Set(statuses)].join(',');
  return query;
}
export function borrowActionBody(row,action,{assetNumber,reason='',pickupDeadlineAt}={}){
  if(!row?.id||!Number.isInteger(row.version)||!row.allowedActions?.includes(action))throw Error('记录状态已变化，请刷新后重新办理');
  const body={version:row.version};
  if(action==='issue')Object.assign(body,{identityChecked:true,quantity:1});
  if(['return-confirm','return-confirm-direct'].includes(action))body.checked=true;
  if(action==='extend-pickup'){
    const deadline=pickupDeadlineAt;
    if(!deadline||Number.isNaN(Date.parse(deadline)))throw Error('请选择新的领取截止日期和时间');
    if(!reason.trim())throw Error('请填写延期说明');
    if(reason.trim().length>500)throw Error('延期说明不能超过500字');
    Object.assign(body,{pickupDeadlineAt:deadline,reason:reason.trim()});
  }
  if(action==='reject'){
    if(!reason.trim())throw Error('请填写拒绝原因');
    if(reason.trim().length>500)throw Error('拒绝原因不能超过500字');
    body.reason=reason.trim();
  }
  if(['issue','return-confirm','return-confirm-direct'].includes(action)){
    const code=normalizeAssetBarcode(assetNumber);
    if(typeof assetNumber==='string'&&assetNumber.trim()&&!code)throw Error('编号须为100字以内，且不能包含换行或控制字符');
    if(action==='return-confirm-direct'&&!row.assetNumber)throw Error('现场归还须核对已绑定的资产编号');
    if(action!=='issue'&&row.assetNumber){
      if(!code)throw Error('请扫描或手动填写发放时绑定的资产编号');
      if(code!==row.assetNumber)throw Error('归还资产编号与发放记录不匹配');
    }
    if(code&&(action==='issue'||row.assetNumber))body.assetNumber=code;
  }
  return body;
}
export function createBorrowManagement(client){
  const other=createOtherBorrows(client);
  function search(filters,page,pageSize){
    return Object.entries({scope:'management',...borrowFilters(filters),...(page?{page,pageSize}:{})})
      .map(([key,value])=>encodeURIComponent(key)+'='+encodeURIComponent(value)).join('&');
  }
  return {
    async scanAsset(assetNumber,{q='',page=1}={}){
      const path='/loan-records/asset-scan?assetNumber='+encodeURIComponent(assetNumber)+'&page='+page+'&q='+encodeURIComponent(q.trim());
      return (await client.request(path)).data;
    },
    async list(filters,page=1,pageSize=10){
      return (await client.request('/loan-records?'+search(filters,page,pageSize))).data;
    },
    async summary(filters){
      return (await client.request('/loan-records/summary?'+search(filters))).data;
    },
    async detail(row){
      const data=(await client.request(loanRecordPath(row))).data;
      return {...data,sourceType:isOtherLoan(row)?'other':'normal',recordKey:typeof row==='string'?'normal:'+row:loanRecordKey(row)};
    },
    async preview(action,filters){
      if(!borrowActionLabels[action])throw Error('不支持此批量操作');
      const data=(await client.request('/loan-records/bulk-preview',{method:'POST',data:{action,scope:'management',filters:borrowFilters(filters)}})).data;
      if(!Array.isArray(data.items)||data.items.length!==data.eligible)throw Error('批量预览不完整，请刷新后重试');
      return {...data,items:data.items.map(row=>({...row,allowedActions:[...(row.allowedActions||[])]}))};
    },
    execute(row,action,options={}){
      if(isOtherLoan(row))return other.action(row,action,{...options,checked:true,physicalAvailable:action==='approve'});
      return client.mutate(loanRecordPath(row)+'/'+action,borrowActionBody(row,action,options));
    }
  };
}
