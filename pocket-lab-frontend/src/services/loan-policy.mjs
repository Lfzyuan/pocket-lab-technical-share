export function nextRenewMonths(record={}){
  if(record.renewCount>=2)return 0;
  return [1,2].includes(record.nextRenewMonths)?record.nextRenewMonths:record.renewCount===1?1:2;
}
export function renewLabel(record={}){const months=nextRenewMonths(record);return months?'续借'+months+'个月':'已续借2次';}
