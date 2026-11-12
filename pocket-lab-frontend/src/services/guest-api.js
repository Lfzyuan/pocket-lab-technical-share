import {createGuestClient,publicAssetURL,publicFileURL} from './guest-client.mjs';
const baseURL=import.meta.env?.VITE_API_BASE_URL||'/api';
function transport(options){
  // #ifdef H5
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),options.timeout);
  return fetch(options.url,{method:'GET',headers:options.header,credentials:'omit',signal:controller.signal})
    .then(async response=>({statusCode:response.status,data:await response.json().catch(()=>null)})).finally(()=>clearTimeout(timer));
  // #endif
  // #ifndef H5
  return new Promise((resolve,reject)=>uni.request({...options,success:resolve,fail:reject}));
  // #endif
}
export const guestApi=createGuestClient({baseURL,transport});
export const guestAsset=value=>publicAssetURL(value,baseURL);
export const guestFile=id=>publicFileURL(id,baseURL);
