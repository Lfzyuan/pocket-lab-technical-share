export function chinaToday(now=new Date()){return new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(now);}
export function shiftDay(day,offset){const d=new Date(day+'T12:00:00+08:00');d.setUTCDate(d.getUTCDate()+offset);return chinaToday(d);}
export function chinaWeekStart(now=new Date()){const day=chinaToday(new Date(now)),weekday=new Date(day+'T12:00:00+08:00').getUTCDay();return shiftDay(day,-((weekday+6)%7));}
export function dutyWeek(start,slots){return Array.from({length:7},(_,index)=>{const date=shiftDay(start,index),day=new Date(date+'T12:00:00+08:00').getUTCDay();return {date,label:['日','一','二','三','四','五','六'][day],slots:slots.filter(slot=>slot.date===date&&slot.enabled!==false).sort((a,b)=>a.startTime.localeCompare(b.startTime))};});}

export function availableDutySlots(slots,now=new Date()){
 const current=new Date(now).getTime();
 return slots.filter(slot=>{if(!slot||slot.enabled===false||!/^\d{4}-\d{2}-\d{2}$/.test(slot.date||'')||!/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d+)?)?$/.test(slot.endTime||''))return false;const end=Date.parse(slot.date+'T'+slot.endTime+'+08:00');return Number.isFinite(end)&&end>current;});
}
export function advanceDutyWindow({start,selected,followCurrentWeek,followToday},now=new Date()){
 const today=chinaToday(new Date(now)),follow=followCurrentWeek??followToday??true;
 const first=follow||!start?chinaWeekStart(now):chinaWeekStart(new Date(start+'T12:00:00+08:00')),last=shiftDay(first,6);
 const valid=selected&&selected>=today&&selected>=first&&selected<=last;
 const fallback=first<today?today:first;
 return {today,start:first,selected:valid?selected:fallback<=last?fallback:''};
}
