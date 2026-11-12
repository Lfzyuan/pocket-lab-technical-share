export const ACCOUNT_TEMPLATE_PATH='/static/templates/account-import.xlsx';
export const ACCOUNT_TEMPLATE_NAME='账号导入模版.xlsx';
export function validateTemplateZip(value){
 const bytes=value instanceof Uint8Array?value:value instanceof ArrayBuffer?new Uint8Array(value):null;
 if(!bytes||bytes.length<22||bytes.length>10485760)throw new Error('模版内容为空或损坏，请重新下载');
 const data=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength);if(data.getUint32(0,true)!==0x04034b50)throw new Error('模版内容无效，请重新下载');
 let end=-1;for(let i=bytes.length-22;i>=Math.max(0,bytes.length-65557);i--)if(data.getUint32(i,true)===0x06054b50){end=i;break;}
 if(end<0||data.getUint16(end+10,true)<1)throw new Error('模版内容损坏，请重新下载');
 const count=data.getUint16(end+10,true),start=data.getUint32(end+16,true),size=data.getUint32(end+12,true);if(count>2000||start+size>end)throw new Error('模版内容损坏，请重新下载');
 let offset=start;const names=[];
 for(let i=0;i<count;i++){if(offset+46>start+size||data.getUint32(offset,true)!==0x02014b50)throw new Error('模版内容损坏，请重新下载');const length=data.getUint16(offset+28,true),extra=data.getUint16(offset+30,true),comment=data.getUint16(offset+32,true),local=data.getUint32(offset+42,true);if(offset+46+length+extra+comment>start+size||local+30>start||data.getUint32(local,true)!==0x04034b50)throw new Error('模版内容损坏，请重新下载');names.push(String.fromCharCode(...bytes.subarray(offset+46,offset+46+length)));offset+=46+length+extra+comment;}
 if(offset!==start+size||!['[Content_Types].xml','xl/workbook.xml'].every(name=>names.includes(name))||!names.some(name=>/^xl\/worksheets\/[^/]+\.xml$/.test(name)))throw new Error('模版内容无效，请重新下载');return {bytes:bytes.length,entries:count};
}
export async function downloadAccountTemplate({platform=uni,origin='',isCurrent=()=>true,onTask=()=>{}}={}){
 const response=await new Promise((resolve,reject)=>{const task=platform.downloadFile({url:origin.replace(/\/$/,'')+ACCOUNT_TEMPLATE_PATH+'?v=mixed-20261105-1',timeout:30000,success:resolve,fail:()=>reject(new Error('模版下载失败，请重试'))});onTask(task);});
 if(!isCurrent())return null;if(response.statusCode!==200)throw new Error('模版暂时无法下载，请重试');
 const fs=platform.getFileSystemManager?.();if(!fs?.readFile)throw new Error('当前设备无法校验文件，请在浏览器下载模版');
 const data=await new Promise((resolve,reject)=>fs.readFile({filePath:response.tempFilePath,success:r=>resolve(r.data),fail:()=>reject(new Error('模版无法读取，请重试'))}));validateTemplateZip(data);if(!isCurrent())return null;
 const save=fs.saveFile?fs.saveFile.bind(fs):platform.saveFile?.bind(platform);if(!save)throw new Error('当前设备无法保存文件，请在浏览器下载模版');
 const saved=await new Promise((resolve,reject)=>save({tempFilePath:response.tempFilePath,success:resolve,fail:()=>reject(new Error('模版保存失败，请检查本机空间'))}));if(!isCurrent())return null;
 await new Promise((resolve,reject)=>platform.openDocument({filePath:saved.savedFilePath,fileType:'xlsx',showMenu:true,success:resolve,fail:()=>reject(new Error('模版已保存，暂时无法预览，请从文件菜单打开'))}));return {filePath:saved.savedFilePath,name:ACCOUNT_TEMPLATE_NAME};
}
