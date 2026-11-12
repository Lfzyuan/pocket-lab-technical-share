export const WISH_CATEGORIES=['单片机','FPGA','嵌入式','物联网','机器人','人工智能','其他'];
export function validateWish(input){
 const body={name:(input.name||'').trim(),model:(input.model||'').trim(),category:input.category||'',description:(input.description||'').trim(),function:(input.function||'').trim()};
 if(!body.name||body.name.length>160)throw Error('请填写器材名称（最多160字）');
 if(body.model.length>100)throw Error('型号最多100字');
 if(!WISH_CATEGORIES.includes(body.category))throw Error('请选择器材分类');
 if(!body.description||body.description.length>2000)throw Error('请填写用途（最多2000字）');
 if(body.function.length>2000)throw Error('功能最多2000字');
 if(input.urls!==undefined&&!Array.isArray(input.urls))throw Error('商品或资料链接格式不正确');
 const links=input.urls===undefined?[(input.url||'')]:input.urls;
 body.urls=links.map(value=>{if(typeof value!=='string')throw Error('请填写有效链接');return value.trim();}).filter(Boolean);
 if(body.urls.some(value=>!/^https?:\/\/[^\s]+$/i.test(value)||value.length>1000))throw Error('每条链接须为有效的http或https地址，最多1000字');
 return body;
}
export function createWishlist(client){return {
 async list({page=1,category='',keyword='',status='',stage=''}={}){
  const q=(stage?'stage='+encodeURIComponent(stage)+'&':'')+(status?'status='+encodeURIComponent(status)+'&':'')+'page='+page+'&pageSize=9'+(category?'&category='+encodeURIComponent(category):'')+(keyword.trim()?'&keyword='+encodeURIComponent(keyword.trim()):'');
  const {data}=await client.request('/wishlist?'+q);
  if(!Array.isArray(data?.items)||!Number.isInteger(data?.total)||data.total<0)throw Error('心愿清单暂未返回有效数据，请稍后重试');
  return data;
 },
 async review(row,action,reason=''){if(!['approve','reject'].includes(action))throw Error('审批操作无效');return client.mutate('/wishlist/'+encodeURIComponent(row.id)+'/'+action,{version:row.version,...(action==='reject'?{reason}: {})});},
 async markStocked(row,equipmentId){if(!equipmentId)throw Error('请选择实际完成入库的器材');return client.mutate('/wishlist/'+encodeURIComponent(row.id)+'/stocked',{version:row.version,equipmentId,confirmed:true});},
 async create(input){return client.mutate('/wishlist',validateWish(input));},
 async support(row){
  if(!row?.id)throw Error('请刷新后再支持这条心愿');
  const result=await client.mutate('/wishlist/'+encodeURIComponent(row.id)+'/support',row.supportedByMe?undefined:{},{method:row.supportedByMe?'DELETE':'POST'});
  if(result?.id!==row.id||!Number.isInteger(result?.supportCount)||result.supportCount<0||typeof result.supportedByMe!=='boolean')throw Error('操作结果尚未核对，请刷新心愿清单');
  return result;
 }
};}

// Preserve references to unchanged cards during polling; replacing the whole page used to refresh every support button.
export function reconcileWishPage(target,result){
 const current=new Map(target.items.map(row=>[row.id,row]));
 const rows=result.items.map(next=>{const existing=current.get(next.id);if(!existing)return next;for(const [key,value] of Object.entries(next)){if(JSON.stringify(existing[key])!==JSON.stringify(value))existing[key]=value;}return existing;});
 if(rows.length!==target.items.length||rows.some((row,index)=>row!==target.items[index]))target.items=rows;
 for(const key of ['total','page','pageSize'])if(result[key]!==undefined&&target[key]!==result[key])target[key]=result[key];
}
export function wishStageLabel(row){return row.publicStage==='stocked'||row.procurementStage==='stocked'?'已采购并上架':row.publicStage==='planned'||row.status==='approved'?'已列入计划':'';}
