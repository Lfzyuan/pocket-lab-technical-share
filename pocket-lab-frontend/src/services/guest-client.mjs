const RESOURCES=new Set(['equipment','labs','works','resources','wishlist','announcements','duty-slots','regulations']);
const QUERY=new Set(['q','keyword','page','pageSize','category','equipmentId','days','date','dateFrom','endDate','from','to','status','stage']);
function publicPath(path){
  if(typeof path==='string'&&/^\/public\/regulations\/(?:management|usage)\/content$/.test(path))return true;
  if(typeof path!=='string'||path.length>2048||!/^\/public\/(equipment|labs|works|resources|wishlist|announcements|duty-slots|regulations)(?:\/[A-Za-z0-9_-]+(?:\/availability)?)?(?:\?[^#]*)?$/.test(path))return false;
  const [route,query='']=path.split('?');const parts=route.split('/');
  if(!RESOURCES.has(parts[2])||(parts.length===5&&(parts[2]!=='labs'||parts[4]!=='availability')))return false;
  if(parts.length===4&&(!['equipment','labs','works'].includes(parts[2])&&!(parts[2]==='resources'&&parts[3]==='categories')))return false;
  try{return query.split('&').filter(Boolean).every(part=>{const key=decodeURIComponent(part.split('=')[0].replace(/\+/g,' '));return QUERY.has(key)&&(!['status','stage'].includes(key)||parts[2]==='wishlist')&&(!['from','to'].includes(key)||parts[2]==='duty-slots');});}catch{return false;}
}
export function createGuestClient({transport,baseURL='/api'}){
  return {async request(path,options={}){
    if(!publicPath(path)||Object.keys(options).some(key=>key!=='method')||(options.method&&options.method!=='GET'))throw new Error('游客仅可浏览公开内容');
    let response;
    try{response=await transport({url:baseURL.replace(/\/$/,'')+path,method:'GET',header:{Accept:'application/json'},timeout:15000,withCredentials:false});}
    catch{throw new Error('公开内容暂时无法加载，请检查网络后重试');}
    if(response.statusCode>=200&&response.statusCode<300&&response.data?.code==='OK')return response.data.data;
    throw new Error(typeof response.data?.message==='string'?response.data.message:'公开内容暂时无法加载，请稍后重试');
  }};
}
export function publicAssetURL(value,baseURL='/api'){
  if(typeof value!=='string'||value.includes('..')||value.includes('\\'))return '';
  if(/^\/static\/[A-Za-z0-9_/.-]+$/.test(value))return value;
  if(/^\/api\/public\/files\/[A-Za-z0-9_-]+\/content$/.test(value))return baseURL.replace(/\/$/,'')+value.slice(4);
  return '';
}
export function publicFileURL(id,baseURL='/api'){
  return typeof id==='string'&&/^[A-Za-z0-9_-]+$/.test(id)?publicAssetURL('/api/public/files/'+id+'/content',baseURL):'';
}
