// Explicit read-only adapter: no identity, session, private cache or pending operation.
export const GUEST_MESSAGE='当前为游客模式，请登录后操作';
const PRIVATE_PAGES=new Set(['records','bookings','favorites','subscriptions','notifications','purchases','submissions','profile','otherBorrows']);
export const isGuestPrivatePage=page=>PRIVATE_PAGES.has(page);
const READ_ROUTES=/^\/(?:equipment(?:\/[A-Za-z0-9_-]+)?|labs(?:\/[A-Za-z0-9_-]+(?:\/availability)?)?|works(?:\/[A-Za-z0-9_-]+)?|resources(?:\/categories)?|wishlist|announcements|duty-slots|regulations)$/;
const QUERY_KEYS=new Set(['q','keyword','page','pageSize','category','equipmentId','days','date','dateFrom','endDate','from','to','status','stage']);
const EMPTY_PATHS=new Set(['/favorites','/borrow-requests','/stock-subscriptions','/notifications','/purchase-requests','/work-submissions','/lab-bookings','/other-borrows']);
export function createGuestPortalClient({publicClient,onDenied=()=>{}}){
 const denied=()=>{onDenied(GUEST_MESSAGE);const error=new Error(GUEST_MESSAGE);error.status=403;error.code='GUEST_READ_ONLY';throw error;};
 async function request(path,options={}){
  if(typeof path!=='string'||Object.keys(options).some(key=>key!=='method')||(options.method&&options.method!=='GET'))return denied();
  const [route,query='',extra]=path.split('?');
  if(extra!==undefined||path.includes('#')||path.length>2048)return denied();
  const pairs=[];
  try{for(const pair of query.split('&').filter(Boolean)){const [key,value='']=pair.split('=');const decoded=decodeURIComponent(key);if(QUERY_KEYS.has(decoded))pairs.push(encodeURIComponent(decoded)+'='+value);else if(!['active','includeDisabled'].includes(decoded))throw Error('Unsupported query');}}catch{return denied();}
  if(EMPTY_PATHS.has(route))return {data:{items:[],total:0,page:1,pageSize:10,unreadCount:0}};
  if(!READ_ROUTES.test(route))return denied();
  const data=await publicClient.request('/public'+route+(pairs.length?'?'+pairs.join('&'):''));
  // Only the public projection is returned; UI needs active for its normal borrow button.
  if(route==='/equipment'||/^\/equipment\//.test(route)){
   const active=item=>({...item,active:true});
   return {data:Array.isArray(data?.items)?{...data,items:data.items.map(active)}:active(data)};
  }
  return {data};
 }
 return {request,catalog:request,mutate:async()=>denied(),pending:()=>null,getSession:()=>null,
  setSession:denied,login:async()=>denied(),logout:async()=>{},acknowledge:denied};
}

// Bind every async result to the UI context that started it, including child panels.
export function createPortalContextClient({getClient,getContext}){
 return new Proxy({},{get(_,key){const client=getClient(),value=client[key];if(typeof value!=='function')return value;
  if(!['request','catalog','mutate'].includes(key))return value.bind(client);
  return async(...args)=>{const context=getContext();try{const result=await value.apply(client,args);if(context!==getContext())throw staleContext();return result;}catch(error){if(context!==getContext())throw staleContext();throw error;}};
 }});
}
function staleContext(){const error=new Error('浏览模式已切换，请重新查看');error.code='STALE_CONTEXT';error.status=409;return error;}
