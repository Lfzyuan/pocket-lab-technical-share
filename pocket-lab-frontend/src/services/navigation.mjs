const item=(id,name,icon)=>({id,name,icon});
export function primaryNavigation(role){
 if(role==='reviewer')return [item('home','工作台','home'),item('approvals','申请审批','check'),item('handover','发放归还','equipment'),item('equipment','库存查询','equipment'),item('personal','我的','user')];
 if(role==='admin')return [item('home','工作台','home'),item('management','管理','edit'),item('personal','我的','user')];
 if(role==='student')return [item('home','首页','home'),item('equipment','器材借用','equipment'),item('records','我的借用','borrow-box'),item('labs','实验室预约','lab'),item('works','作品展示','showcase'),item('resources','学习资源','book'),item('wishlist','心愿清单','heart'),item('personal','个人中心','user')];
 return [];
}
export function approvalNavigation(role){return role==='reviewer'?[item('review','器材借用','equipment'),item('bookings','实验室预约','lab'),item('purchases','购置申请','check'),item('otherBorrows','其他借用','borrow-box')]:role==='admin'?[item('submissions','作品投稿','check')]:[];}
export function managementNavigation(role){const common=[item('announcements','通知管理','records'),item('incidents','异常与维修','equipment')];return role==='admin'?[item('rules','规定与须知','info'),item('wishlist','心愿审批','check'),item('inventory','器材维护','equipment'),item('otherBorrows','其他借用','borrow-box'),item('users','账号管理','user'),item('importUsers','批量导入','user'),item('resources','资源维护','book'),item('works','作品维护','showcase'),item('submissions','投稿审核','check'),item('labs','实验室管理','lab'),item('stocklogs','库存日志','records'),item('records','借用记录','borrow-box'),item('handover','发放归还','equipment'),item('purchases','采购入库','check'),item('statistics','数据统计','check'),...common]:role==='reviewer'?[item('dutyManage','值班维护','clock'),...common]:[];}
export function staffServices(role){return role==='reviewer'?[...primaryNavigation(role).filter(n=>!['home','personal'].includes(n.id)),item('labs','实验室预约','lab'),...managementNavigation(role)]:role==='admin'?[item('management','管理中心','edit')]:[];}
export function activePrimary(page,role){
 if(page==='otherBorrows')return role==='reviewer'?'approvals':role==='admin'?'management':'equipment';
 if(role==='admin')return ['home','personal','profile','rules','duty','notifications'].includes(page)?(['profile','rules','duty','notifications'].includes(page)?'personal':page):'management';
 if(role==='reviewer'){
  if(['review','bookings','purchases','approvals'].includes(page))return 'approvals';
  if(['home','handover','equipment'].includes(page))return page;
  return 'personal';
 }
 if(['personal','profile','favorites','subscriptions','notifications','purchases','submissions','rules','duty'].includes(page))return 'personal';
 if(page==='bookings')return 'labs';
 return page;
}
export function mobileNavigation(role){const items=primaryNavigation(role);return role==='student'?['home','equipment','labs','records','personal'].map(id=>items.find(x=>x.id===id)):items;}

export const iconSource=name=>'/static/icons/'+name+(['heart','borrow-box','showcase'].includes(name)?'.svg':'.png');
