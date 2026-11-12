import {ApiError} from './client.mjs';
export const REGULATION_KINDS=Object.freeze(['management','usage']);
export function regulationRequest(kind,{guest=false,baseURL='/api',getSession=()=>null}={}){
 if(!REGULATION_KINDS.includes(kind))throw new ApiError('文档不存在',{status:404,code:'NOT_FOUND'});
 const session=guest?null:getSession();if(!guest&&!session?.accessToken)throw new ApiError('登录已失效，请重新登录后阅读或下载',{status:401,code:'UNAUTHORIZED'});
 return {url:baseURL.replace(/\/$/,'')+(guest?'/public':'')+'/regulations/'+kind+'/download',headers:{Accept:'application/pdf',...(!guest?{Authorization:'Bearer '+session.accessToken}:{})},credentials:'omit',session:session?{accessToken:session.accessToken,user:{id:session.user?.id}}:null};
}
export async function fetchRegulationPdf(kind,{guest=false,baseURL='/api',getSession=()=>null,clearSession=()=>{},fetchImpl=globalThis.fetch,signal,timeoutMs=30000}={}){
 const request=regulationRequest(kind,{guest,baseURL,getSession}),controller=new AbortController();let timedOut=false;
 const abort=()=>controller.abort();if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});
 const timer=setTimeout(()=>{timedOut=true;controller.abort();},timeoutMs);
 try{
  const response=await fetchImpl(request.url,{method:'GET',headers:request.headers,credentials:'omit',signal:controller.signal});
  if(!response.ok){
   if(response.status===401&&!guest&&getSession()?.accessToken===request.session.accessToken)clearSession();
   const payload=await response.json().catch(()=>null);
   throw new ApiError(response.status===401?'登录已失效，请重新登录后阅读或下载':payload?.message||'文档暂时无法读取，请稍后重试',{status:response.status,code:payload?.code||'HTTP_ERROR',requestId:payload?.requestId||''});
  }
  if((response.headers.get('content-type')||'').split(';')[0].trim().toLowerCase()!=='application/pdf')throw new ApiError('服务器返回的不是PDF文件，请联系管理员检查正文',{code:'INVALID_PDF'});
  const blob=await response.blob(),head=new Uint8Array(await blob.slice(0,5).arrayBuffer());
  if(!blob.size||String.fromCharCode(...head)!=='%PDF-')throw new ApiError('PDF文件内容无效，请联系管理员检查正文',{code:'INVALID_PDF'});
  if(controller.signal.aborted)throw new DOMException('Aborted','AbortError');
  if(!guest){const current=getSession();if(current?.accessToken!==request.session.accessToken||current?.user?.id!==request.session.user.id)throw new ApiError('登录状态已变化，请重新打开文档',{code:'DOCUMENT_CONTEXT_CHANGED'});}
  return blob;
 }catch(error){
  if(error instanceof ApiError)throw error;
  if(controller.signal.aborted)throw new ApiError(timedOut?'文档读取超时，请稍后重试':'已取消文档加载',{code:timedOut?'DOCUMENT_TIMEOUT':'DOCUMENT_CANCELLED'});
  throw new ApiError('文档暂时无法读取，请检查网络后重试',{code:'DOCUMENT_NETWORK_ERROR'});
 }finally{clearTimeout(timer);signal?.removeEventListener('abort',abort);}
}
export function pdfFilename(row){const value=String(row.fileName||row.title||'实验室规定').replace(/[\x00-\x1f\x7f/\\]/g,'_');return /\.pdf$/i.test(value)?value:value+'.pdf';}
export function openReadingWindow(windowObject=globalThis.window){
 let popup;try{popup=windowObject?.open('about:blank','_blank');if(popup){popup.opener=null;popup.document.title='正在读取PDF';popup.document.body.textContent='正在读取PDF，请稍候…';}}catch{closeReadingWindow(popup);return null;}return popup||null;
}
export function showPdfInWindow(popup,url){try{if(!popup||popup.closed)return false;popup.location.replace(url);return true;}catch{return false;}}
export function closeReadingWindow(popup){try{if(popup&&!popup.closed)popup.close();}catch{}}
// A reader tab may outlive this component. Keep its Blob alive until every reader closes.
export function releasePdfUrl(url,windows=[],{revoke=URL.revokeObjectURL,schedule=setInterval,cancel=clearInterval,delay=setTimeout}={}){
 if(!url)return;
 const open=()=>windows.some(popup=>{try{return popup&&!popup.closed;}catch{return false;}});
 if(!open()){const timer=delay(()=>revoke(url),30000);timer?.unref?.();return;}
 const timer=schedule(()=>{if(!open()){cancel(timer);revoke(url);}},1000);timer?.unref?.();
}
export async function openNativeRegulation(kind,{guest,baseURL,getSession,clearSession,platform,save=false,isCurrent=()=>true,onTask=()=>{}}={}){
 const request=regulationRequest(kind,{guest,baseURL,getSession});
 const result=await new Promise((resolve,reject)=>{const task=platform.downloadFile({url:request.url,header:request.headers,timeout:30000,success:resolve,fail:()=>reject(new ApiError('文档下载失败，请检查网络后重试'))});onTask(task);});
 const current=()=>isCurrent()&&(guest||getSession()?.accessToken===request.session.accessToken);if(!current())throw new ApiError('已取消文档加载',{code:'DOCUMENT_CANCELLED'});
 if(result.statusCode===401){if(!guest&&getSession()?.accessToken===request.session.accessToken)clearSession();throw new ApiError('登录已失效，请重新登录后阅读或下载',{status:401});}
 if(result.statusCode!==200)throw new ApiError('文档暂时无法读取，请稍后重试',{status:result.statusCode});
 let path=result.tempFilePath;
 if(save){const manager=platform.getFileSystemManager?.(),saveFile=manager?.saveFile?manager.saveFile.bind(manager):platform.saveFile?.bind(platform);if(!saveFile)throw new ApiError('当前平台暂不支持保存文件，请在浏览器中下载');const saved=await new Promise((resolve,reject)=>saveFile({tempFilePath:path,success:resolve,fail:()=>reject(new ApiError('文件保存失败，请检查本机空间后重试'))}));path=saved.savedFilePath;}
 if(!current())throw new ApiError('已取消文档加载',{code:'DOCUMENT_CANCELLED'});
 await new Promise((resolve,reject)=>platform.openDocument({filePath:path,fileType:'pdf',showMenu:true,success:resolve,fail:()=>reject(new ApiError('PDF暂时无法打开，请联系管理员检查正文'))}));return {saved:save};
}
