import {api} from './api.js';
const base=()=> (import.meta.env.VITE_API_BASE_URL||'/api').replace(/\/$/,'');
const key=()=> 'pocket-lab-file-pending-v1:'+api.getSession()?.user?.id;
export async function chooseAndUpload({imagesOnly=false,accept=""}={}){
  const session=api.getSession();if(!session)throw Error('请重新登录');
  // #ifdef H5
  const file=await new Promise(resolve=>{const input=document.createElement('input');input.type='file';input.accept=accept||(imagesOnly?'.png,.jpg,.jpeg,.webp':'.pdf,.png,.jpg,.jpeg,.webp,.zip,.mp4,.txt,.csv');input.onchange=()=>resolve(input.files?.[0]);input.oncancel=()=>resolve(null);input.click();});
  if(!file)return null;if(accept==='.pdf'&&!/\.pdf$/i.test(file.name))throw Error('请选择PDF格式的正式正文');if(!file.size||file.size>=(imagesOnly?30*1024*1024:5*1024*1024*1024))throw Error(imagesOnly?'请选择小于30MB的图片':'请选择小于5GB的文件');
  if(imagesOnly&&!/\.(png|jpe?g|webp)$/i.test(file.name))throw Error('请选择PNG、JPEG或WebP图片');
  const storageKey=key(),signature=JSON.stringify({name:file.name,size:file.size,lastModified:file.lastModified});let operation=uni.getStorageSync(storageKey);
  if(operation&&operation.signature!==signature)throw Error('上次文件上传结果待确认，请重新选择同一文件核对后再上传其他文件');
  if(operation&&Date.now()-operation.at>=86400000)throw Error('上次上传已超过24小时，请联系管理员核对文件记录');
  operation||={signature,key:'file-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2),at:Date.now()};uni.setStorageSync(storageKey,operation);
  const body=new FormData();body.append('file',file);const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),120000);
  try{const response=await fetch(base()+'/files',{method:'POST',headers:{Authorization:'Bearer '+session.accessToken,'Idempotency-Key':operation.key},body,signal:controller.signal});
    const payload=await response.json();if(response.status===401)api.clearSession();
    if(response.status>=400&&response.status<500&&response.status!==401)uni.removeStorageSync(storageKey);
    if(!response.ok||payload.code!=='OK')throw Error(payload.message||'文件上传失败');uni.removeStorageSync(storageKey);return {...payload.data,...(imagesOnly?{previewUrl:URL.createObjectURL(file)}:{})};
  }catch(error){throw Error(error.message==='Failed to fetch'||error.name==='AbortError'?'上传结果待确认，请重新选择同一文件，系统会沿用原凭证核对':error.message);}finally{clearTimeout(timer);}
  // #endif
  // #ifndef H5
  throw Error('此端请通过POST /api/files的上传接口接入文件选择；当前网页已支持上传');
  // #endif
}
export async function downloadFile(path,name='资料'){
  const session=api.getSession();if(!session)throw Error('请重新登录');
  // #ifdef H5
  const response=await fetch(base()+path.replace(/^\/api/,''),{headers:{Authorization:'Bearer '+session.accessToken}});
  if(response.status===401)api.clearSession();if(!response.ok){const error=await response.json().catch(()=>({}));throw Error(error.message||'文件下载失败');}
  const blob=await response.blob(),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=name;document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),30000);
  // #endif
  // #ifndef H5
  await new Promise((resolve,reject)=>uni.downloadFile({url:base()+path.replace(/^\/api/,''),header:{Authorization:'Bearer '+session.accessToken},success:r=>r.statusCode===200?uni.openDocument({filePath:r.tempFilePath,showMenu:true,success:resolve,fail:reject}):reject(Error('文件下载失败')),fail:reject}));
  // #endif
}
