export function validateBorrowLimit(value,reason){
 const limit=typeof value==='number'?value:Number(String(value??'').trim());
 if(!Number.isInteger(limit)||limit<1||limit>100)throw Error('未归还上限须为1至100之间的整数');
 const explanation=String(reason??'').trim();if(!explanation)throw Error('请填写调整原因');if(explanation.length>500)throw Error('调整原因不能超过500字');
 return {limit,reason:explanation};
}
export function createBorrowLimit(client){return {save(userRecord,value,reason){
 if(client.getSession()?.user?.role!=='admin')throw Error('只有管理员可以调整未归还上限');
 if(typeof userRecord?.id!=='string'||!userRecord.id)throw Error('请先选择需要调整的账号');
 return client.mutate('/users/'+encodeURIComponent(userRecord.id)+'/borrow-limit',validateBorrowLimit(value,reason),{method:'PATCH'});
}};}
