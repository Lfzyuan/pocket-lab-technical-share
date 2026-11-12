const SESSION='pocket-lab-live-session-v1';
const PENDING='pocket-lab-live-pending-v1';
const PREFIX='pocket-lab-live-catalog-v1:';
export class ApiError extends Error {
  constructor(message,{status=0,code='NETWORK_ERROR',requestId='',uncertain=false}={}) {
    super(message);Object.assign(this,{status,code,requestId,uncertain});
  }
}
export function createClient({transport,storage,baseURL='/api',now=Date.now,randomKey,onUnauthorized=()=>{}}) {
  const read=key=>{try{return storage.get(key)||null;}catch{return null;}};
  const write=(key,value)=>{try{storage.set(key,value);}catch{throw new ApiError('本机无法保存操作凭证，请先检查浏览器存储设置',{code:'STORAGE_ERROR'});}};
  let session=read(SESSION);
  function clearSession(){session=null;storage.remove(SESSION);for(const k of storage.keys())if(k.startsWith(PREFIX))storage.remove(k);onUnauthorized();}
  function setSession(value){session=value;write(SESSION,value);}
  async function request(path,{method='GET',data,headers={},anonymous=false}={}) {
    const identity=session?.accessToken;
    let response;
    try {
      response=await transport({url:baseURL.replace(/\/$/,'')+path,method,data,header:{...(!anonymous&&identity?{Authorization:`Bearer ${identity}`}:{ }),'Content-Type':'application/json',...headers},timeout:15000});
    } catch {
      throw new ApiError(method==='GET'?'网络连接失败，请稍后刷新':'提交结果待确认，请保留本次操作并重试确认',{uncertain:method!=='GET'});
    }
    if(response.statusCode===304)return {notModified:true,headers:response.header||{}};
    const payload=response.data;
    if(response.statusCode>=200&&response.statusCode<300&&payload?.code==='OK')return {data:payload.data,headers:response.header||{}};
    if(response.statusCode===401&&!anonymous&&identity===session?.accessToken)clearSession();
    const status=response.statusCode;
    throw new ApiError(payload?.message||'请求暂时未完成，请稍后重试',{status,code:payload?.code||'HTTP_ERROR',requestId:payload?.requestId||'',uncertain:method!=='GET'&&(status>=500||status>=200&&status<300||!status)});
  }
  async function catalog(path){
    if(!session?.user?.id)throw new ApiError('请先登录',{status:401,code:'UNAUTHORIZED'});
    const key=PREFIX+session.user.id+':'+path;
    const cached=read(key);
    try {
      let result=await request(path,{headers:cached?.etag?{'If-None-Match':cached.etag}:{}});
      if(result.notModified&&cached)return {...cached,stale:false};
      if(result.notModified)result=await request(path);
      const etag=Object.entries(result.headers).find(([k])=>k.toLowerCase()==='etag')?.[1];
      const value={data:result.data,etag,at:now()};
      try{write(key,value);}catch{/* Display fresh data even if the optional cache cannot be saved. */}
      return {...value,stale:false};
    } catch(e){
      if(e.status||!cached)throw e;
      return {...cached,stale:true};
    }
  }
  const pendingKey=()=>PENDING+':'+session?.user?.id;
  function pending(){const p=read(pendingKey());return p?.userId===session?.user?.id?p:null;}
  async function mutate(path,body,{method='POST'}={}){
    const userId=session?.user?.id;
    if(!userId)throw new ApiError('请先登录',{status:401,code:'UNAUTHORIZED'});
    const previous=pending();
    const signature=JSON.stringify({path,body,method});
    if(previous&&JSON.stringify({path:previous.path,body:previous.body,method:previous.method||'POST'})!==signature)throw new ApiError('上一笔提交结果尚未确认，请先处理页面上方的待确认操作',{code:'PENDING_OPERATION'});
    if(previous&&now()-previous.at>=86400000)throw new ApiError('这笔待确认操作已超过24小时，请查询记录或联系老师核对，勿直接重复提交',{code:'PENDING_EXPIRED'});
    const operation=previous||{userId,path,body,method,signature,key:randomKey(),at:now()};
    // Persist before sending: a reload or timeout must not lose the original idempotency key.
    const operationKey=pendingKey();write(operationKey,operation);
    try {
      const result=await request(path,{method:operation.method||'POST',data:operation.body,headers:{'Idempotency-Key':operation.key}});
      storage.remove(operationKey);return result.data;
    } catch(e){
      // An explicit 4xx is a definite rejection. Network errors and 5xx keep the same request for retry.
      if(!e.uncertain&&e.status!==401)storage.remove(operationKey);
      throw e;
    }
  }
  async function retry(){const p=pending();if(!p)throw new ApiError('没有待确认操作');return mutate(p.path,p.body,{method:p.method||'POST'});}
  function acknowledge(){storage.remove(pendingKey());}
  return {request,catalog,mutate,retry,pending,acknowledge,setSession,clearSession,getSession:()=>session,
    async login(account,password){
      account=(account||'').trim();
      if(!account)throw new ApiError('请输入账号或学号',{code:'VALIDATION_ERROR'});
      if(!password||!password.trim())throw new ApiError('请输入密码',{code:'VALIDATION_ERROR'});
      let r;
      try{r=await request('/auth/login',{method:'POST',data:{account,password},anonymous:true});}
      catch(e){
        if(!e.status||e.status>=500||e.uncertain)throw new ApiError(e.status?'登录服务暂时不可用，请稍后重试':'无法连接登录服务，请检查网络后重新登录',
          {status:e.status,code:e.code,requestId:e.requestId});
        throw e;
      }
      setSession(r.data);return r.data.user;
    },
    async logout(){try{await request('/auth/logout',{method:'POST'});}finally{clearSession();}}
  };
}
