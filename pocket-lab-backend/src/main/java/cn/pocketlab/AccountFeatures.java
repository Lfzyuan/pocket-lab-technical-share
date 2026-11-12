package cn.pocketlab;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.map;

@RestController @RequestMapping("/api")
public class AccountFeatures {
 final Store s;final PasswordEncoder passwords;final Accounts accounts;final OtherBorrows otherBorrows;
 AccountFeatures(Store s,PasswordEncoder p,Accounts a,OtherBorrows otherBorrows){this.s=s;passwords=p;accounts=a;this.otherBorrows=otherBorrows;}
 @PostMapping("/me/password") @Transactional
 public Object password(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode p,HttpServletRequest req){
  accounts.limit("password:"+actor.id());s.lockUser(actor.id());String old=p.path("currentPassword").asText(),next=p.path("newPassword").asText();
  if(next.codePointCount(0,next.length())<6)throw Api.invalid("新密码至少6位");
  if(!passwords.matches(old,s.db.queryForObject("SELECT password_hash FROM lab_user WHERE id=?",String.class,actor.id())))throw Api.invalid("当前密码不正确");
  s.db.update("UPDATE lab_user SET password_hash=? WHERE id=?",passwords.encode(next),actor.id());s.db.update("DELETE FROM auth_session WHERE user_id=?",actor.id());
  return Api.ok(map("passwordChanged",true,"reauthenticationRequired",true),req);
 }
 @PostMapping("/me/view") @Transactional
 public Object view(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){
  var u=s.lockUser(actor.id());String view=body.path("view").asText();if(!Set.of("user","management").contains(view))throw Api.invalid("视角无效");
  if(view.equals("management")&&!u.canManage())throw Api.forbidden();
  s.db.update("UPDATE auth_session SET view_mode=? WHERE token_hash=?",view,Security.digest(req.getHeader("Authorization").substring(7)));
  return Api.ok(u.view(view),req);
 }
 @PostMapping("/users/{id}/management") @Transactional
 public Object management(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest req){
  Store.role(s.lockUser(actor.id()),"admin");var user=s.user(id);if(user.accountRole().equals("admin"))throw Api.invalid("管理员账号受保护");
  if(!body.path("enabled").isBoolean())throw Api.invalid("权限格式不正确");boolean enabled=body.path("enabled").asBoolean();
  if(s.db.update("UPDATE lab_user SET can_manage=? WHERE id=? AND deleted_at IS NULL",enabled,id)!=1)throw Api.missing();
  if(!enabled)s.db.update("UPDATE auth_session SET view_mode='user' WHERE user_id=?",id);
  return Api.ok(s.user(id),req);
 }
 List<Api.User> selected(JsonNode body,boolean lock){
  JsonNode ids=body.path("ids");if(!ids.isArray()||ids.isEmpty()||ids.size()>10000)throw Api.invalid("请选择账号");
  Set<String> unique=new TreeSet<>();ids.forEach(id->unique.add(id.asText()));if(unique.size()!=ids.size())throw Api.invalid("重复账号");
  var group=AccountCohorts.group(body);
  if(group!=null){
   var expected=new HashSet<>(s.db.query("SELECT id,pickup_class_name FROM lab_user WHERE deleted_at IS NULL AND role='student' AND account_type='student'",
       (r,n)->group.includes(r.getString("pickup_class_name"))?r.getString("id"):null));
   expected.remove(null);
   if(!expected.equals(unique))throw Api.conflict("分组名单已变化，请重新预览");
  }
  List<Api.User> users=new ArrayList<>();for(String id:unique){var found=s.db.query("SELECT * FROM lab_user WHERE id=? AND deleted_at IS NULL"+(lock?" FOR UPDATE":""),Store.USER,id);if(found.isEmpty())throw Api.conflict("账号列表已变化，请刷新");var user=found.getFirst();if(user.accountRole().equals("admin"))throw Api.invalid("管理员账号受保护");users.add(user);}
  return users;
 }
 List<Object> blockers(List<Api.User> users){List<Object> result=new ArrayList<>();for(var u:users){var loans=new ArrayList<>(s.db.query("SELECT b.id,b.status,b.quantity,e.name FROM borrow_request b JOIN equipment e ON e.id=b.equipment_id WHERE b.user_id=? AND b.status IN ('borrowed','returning')",(r,n)->map("id",r.getString(1),"status",r.getString(2),"quantity",r.getInt(3),"equipmentName",r.getString(4)),u.id()));
  loans.addAll(s.db.query("SELECT id,status,quantity,name FROM other_borrow_request WHERE user_id=? AND status IN ('borrowed','returning')",(r,n)->map("id",r.getString(1),"status",r.getString(2),"quantity",r.getInt(3),"equipmentName","其他借用 · "+r.getString(4)),u.id()));
  if(!loans.isEmpty())result.add(map("id",u.id(),"name",u.name(),"account",u.account(),"studentNumber",u.studentNumber(),"loans",loans));}return result;}
 @PostMapping("/users/delete-preview")
 public Object check(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){Store.role(actor,"admin");return Api.ok(map("blockers",blockers(selected(body,false))),req);}
 @PostMapping("/users/delete") @Transactional
 public Object delete(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){Store.role(s.lockUser(actor.id()),"admin");
  s.db.queryForObject("SELECT name FROM business_lock WHERE name='extensions' FOR UPDATE",String.class);
  AccountCohorts.lock(s);
  var users=selected(body,true);if(!blockers(users).isEmpty())throw Api.conflict("存在未归还器材，本次未删除任何账号，请刷新查看");
  for(var u:users){var pending=s.db.queryForList("SELECT id,equipment_id,status FROM borrow_request WHERE user_id=? AND status IN ('pending','approved') FOR UPDATE",u.id());for(var row:pending){if("approved".equals(row.get("status")))s.db.update("UPDATE equipment SET reserved=reserved-1,version=version+1 WHERE id=?",row.get("equipment_id"));s.db.update("UPDATE borrow_request SET status='cancelled',active_slot=NULL,version=version+1 WHERE id=?",row.get("id"));s.event(row.get("id").toString(),"cancel",row.get("status").toString(),"cancelled",actor.id(),"账号删除，取消未领取申请");}
   otherBorrows.cancelUnissuedForUser(actor,u.id());
   s.db.update("UPDATE lab_user SET enabled=FALSE,can_manage=FALSE,deleted_at=? WHERE id=?",s.now(),u.id());s.db.update("DELETE FROM auth_session WHERE user_id=?",u.id());}
  return Api.ok(map("deletedCount",users.size()),req);
 }
 @PostMapping("/users/disable-cohort") @Transactional
 public Object disable(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){Store.role(s.lockUser(actor.id()),"admin");AccountCohorts.lock(s);if(AccountCohorts.group(body)==null)throw Api.invalid("请选择班级或年级");var users=selected(body,true);int count=0;for(var u:users){count+=s.db.update("UPDATE lab_user SET enabled=FALSE WHERE id=? AND enabled=TRUE",u.id());s.db.update("DELETE FROM auth_session WHERE user_id=?",u.id());}return Api.ok(map("disabledCount",count),req);}
}
