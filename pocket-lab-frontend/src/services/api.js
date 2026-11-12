import {createClient} from './client.mjs';
const storage={get:key=>uni.getStorageSync(key),set:(key,value)=>uni.setStorageSync(key,value),remove:key=>uni.removeStorageSync(key),keys:()=>uni.getStorageInfoSync().keys};
function randomKey(){
  // #ifdef H5
  if(globalThis.crypto?.getRandomValues){const bytes=new Uint8Array(24);globalThis.crypto.getRandomValues(bytes);return [...bytes].map(n=>n.toString(16).padStart(2,'0')).join('');}
  // #endif
  return 'op-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+'-'+Math.random().toString(36).slice(2);
}
export const api=createClient({baseURL:import.meta.env?.VITE_API_BASE_URL||'/api',storage,randomKey,
  transport:options=>new Promise((resolve,reject)=>uni.request({...options,success:resolve,fail:reject})),
  onUnauthorized:()=>uni.$emit('session-expired')});
