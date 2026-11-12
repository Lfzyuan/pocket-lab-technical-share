package cn.pocketlab;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;
@RestController @RequestMapping("/api/duty-rules")
public class WeeklySchedule {
 final Store s;final Business b;
 WeeklySchedule(Store s,Business b){this.s=s;this.b=b;}
 Map<String,Object> display(Map<String,Object> r){var out=new LinkedHashMap<>(r);out.put("weekdays",Arrays.stream(r.get("weekdays").toString().split(",")).map(Integer::parseInt).toList());out.put("startTime",r.get("startTime").toString().substring(0,5));out.put("endTime",r.get("endTime").toString().substring(0,5));return out;}
 @GetMapping public Object list(@AuthenticationPrincipal Api.User actor,HttpServletRequest req){Store.role(actor,"reviewer","admin");return Api.ok(map("items",b.rows("SELECT * FROM duty_rule ORDER BY start_time,id").stream().map(this::display).toList()),req);}
 @PostMapping @Transactional public Object create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){return save(actor,null,body,req);}
 @PatchMapping("/{id}") @Transactional public Object edit(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest req){return save(actor,id,body,req);}
 synchronized Object save(Api.User actor,String id,JsonNode body,HttpServletRequest req){Store.role(s.lockUser(actor.id()),"reviewer","admin");var old=id==null?null:b.one("SELECT * FROM duty_rule WHERE id=? FOR UPDATE",id);if(old!=null&&body.path("version").asLong(-1)!=((Number)old.get("version")).longValue())throw Api.conflict("安排已更新，请刷新");
  var merged=b.json.valueToTree(old==null?map():display(old));var obj=(com.fasterxml.jackson.databind.node.ObjectNode)merged;body.fields().forEachRemaining(e->obj.set(e.getKey(),e.getValue()));
  Set<Integer> days=new TreeSet<>();if(!obj.path("weekdays").isArray())throw Api.invalid("请选择重复日");for(JsonNode d:obj.path("weekdays")){if(!d.isInt()||d.asInt()<1||d.asInt()>7)throw Api.invalid("重复日无效");days.add(d.asInt());}if(days.isEmpty())throw Api.invalid("请选择重复日");
  LocalTime start,end;try{start=LocalTime.parse(obj.path("startTime").asText());end=LocalTime.parse(obj.path("endTime").asText());}catch(Exception e){throw Api.invalid("时间格式无效");}if(!end.isAfter(start))throw Api.invalid("结束时间应晚于开始时间");String location=obj.path("location").asText().trim();if(location.isEmpty()||location.length()>200||!obj.path("enabled").isBoolean())throw Api.invalid("请填写地点和启用状态");boolean enabled=obj.path("enabled").asBoolean();
  if(enabled)for(var r:b.rows("SELECT * FROM duty_rule WHERE enabled=TRUE AND location=?",location)){if(Objects.equals(r.get("id"),id))continue;var rd=Arrays.stream(r.get("weekdays").toString().split(",")).map(Integer::parseInt).toList();if(rd.stream().anyMatch(days::contains)&&start.isBefore(LocalTime.parse(r.get("endTime").toString()))&&end.isAfter(LocalTime.parse(r.get("startTime").toString())))throw Api.conflict("同地点的每周时段重叠");}
  if(old!=null&&body.has("weekdays")&&s.db.queryForObject("SELECT COUNT(*) FROM borrow_request b JOIN duty_slot d ON d.id=b.slot_id WHERE d.rule_id=? AND b.status IN ('pending','approved')",Long.class,id)>0)throw Api.conflict("此安排已有待领取申请，请先协调办理；仍可停用安排");
  String saved=id==null?Store.id():id,weekdays=String.join(",",days.stream().map(String::valueOf).toList());if(old==null)s.db.update("INSERT INTO duty_rule(id,weekdays,start_time,end_time,location,enabled) VALUES(?,?,?,?,?,?)",saved,weekdays,start,end,location,enabled);else s.db.update("UPDATE duty_rule SET weekdays=?,start_time=?,end_time=?,location=?,enabled=?,version=version+1 WHERE id=?",weekdays,start,end,location,enabled,saved);
  retire(saved);expand();return Api.ok(display(b.one("SELECT * FROM duty_rule WHERE id=?",saved)),req);
 }
 void retire(String id){s.db.update("UPDATE duty_slot SET enabled=FALSE,version=version+1 WHERE rule_id=? AND slot_date>=?",id,s.today());}
 @DeleteMapping("/{id}") @Transactional public synchronized Object delete(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest req){Store.role(s.lockUser(actor.id()),"reviewer","admin");if(s.db.update("DELETE FROM duty_rule WHERE id=? AND version=?",id,body.path("version").asLong(-1))!=1)throw Api.conflict("安排已更新，请刷新");retire(id);return Api.ok(map("deleted",true),req);}
 @Scheduled(initialDelay=1000,fixedDelay=3600000) @Transactional public synchronized void refresh(){expand();}
 void expand(){for(var rule:b.rows("SELECT * FROM duty_rule WHERE enabled=TRUE")){String id=rule.get("id").toString();Set<Integer> days=new HashSet<>(Arrays.stream(rule.get("weekdays").toString().split(",")).map(Integer::parseInt).toList());for(int i=0;i<56;i++){LocalDate date=s.today().plusDays(i);if(!days.contains(date.getDayOfWeek().getValue()))continue;String slot=UUID.nameUUIDFromBytes((id+date).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();var existing=s.db.queryForList("SELECT id FROM duty_slot WHERE rule_id=? AND slot_date=?",String.class,id,date);if(existing.isEmpty())s.db.update("INSERT INTO duty_slot(id,slot_date,start_time,end_time,location,enabled,rule_id) VALUES(?,?,?,?,?,TRUE,?)",slot,date,rule.get("startTime"),rule.get("endTime"),rule.get("location"),id);else if(s.db.queryForObject("SELECT COUNT(*) FROM borrow_request WHERE slot_id=? AND status IN('pending','approved')",Long.class,existing.getFirst())==0)s.db.update("UPDATE duty_slot SET start_time=?,end_time=?,location=?,enabled=TRUE WHERE id=?",rule.get("startTime"),rule.get("endTime"),rule.get("location"),existing.getFirst());else s.db.update("UPDATE duty_slot SET enabled=TRUE WHERE id=?",existing.getFirst());}}}
}
