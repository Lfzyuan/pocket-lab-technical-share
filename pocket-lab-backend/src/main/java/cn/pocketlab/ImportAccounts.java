package cn.pocketlab;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api/users/import")
public class ImportAccounts {
 final Store s;final Business b;final PasswordEncoder passwords;
 ImportAccounts(Store s,Business b,PasswordEncoder p){this.s=s;this.b=b;passwords=p;}
 String encode(Object o){try{return b.json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
 JsonNode parse(String text){try{return b.json.readTree(text);}catch(Exception e){throw new IllegalStateException(e);}}
 @PostMapping("/preview") @Transactional public Object preview(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){
  Store.role(actor,"admin");var options=new Input(body,"rows","accountType","format","confirmYearPlanId");
  String format=RosterPolicy.format(options.has("format")?options.text("format",32,true):"legacy"),type=RosterPolicy.batchType(format,options.has("accountType")?options.text("accountType",16,true):"student");boolean unified=RosterPolicy.unified(format),mixed=format.equals("unified-v2");
  var input=body.path("rows");if(!input.isArray()||input.isEmpty()||input.size()>5000)throw Api.invalid("请选择包含1–5000行账号数据的表格");
  Map<String,Integer> counts=new HashMap<>();var normalized=new ArrayList<Map<String,Object>>();int index=1;
  for(var row:input){index++;new Input(row,"row","account","name","className","grade","phone","formula","formatError","enrollmentYear","teacherMarker","yearOrTeacher","confirmEnrollmentYear");
   if(row.hasNonNull("confirmEnrollmentYear")&&!row.path("confirmEnrollmentYear").isBoolean())throw Api.invalid("年份确认须为布尔值");
   if(row.hasNonNull("yearOrTeacher")&&!row.path("yearOrTeacher").isTextual())throw Api.invalid("“年级/老师”列内容无效，请检查后重新预览");
   String account=row.path("account").asText("").trim(),name=row.path("name").asText("").trim();if(!account.isEmpty()&&!name.isEmpty())counts.merge(account,1,Integer::sum);
   var item=map("row",row.path("row").asInt(index),"account",account,"name",name,"className",row.path("className").asText("").trim(),"grade",row.path("grade").asText("").trim(),"phone",row.path("phone").asText("").trim(),"formula",row.path("formula").asBoolean(),"formatError",row.path("formatError").asText(""),"enrollmentYear",RosterPolicy.normalizedYear(row.path("enrollmentYear").asText("")),"teacherMarker",row.path("teacherMarker").asText("").trim());
   if(mixed)item.put("yearOrTeacher",row.path("yearOrTeacher").asText("").trim());
   else if(!row.path("yearOrTeacher").asText("").isEmpty())throw Api.invalid("名单列内容与模板不符，请检查后重新预览");
   normalized.add(item);
  }
  String fingerprint=Security.digest(encode(map("format",format,"accountType",type,"rows",normalized))),confirmationPlan=options.has("confirmYearPlanId")?options.text("confirmYearPlanId",36,true):"";
  Set<String> confirmedAccounts=new HashSet<>();
  if(!confirmationPlan.isEmpty()){
   if(!unified)throw Api.invalid("旧格式不使用入学年份确认");
   var old=b.one("SELECT rows_json,created_at FROM import_plan WHERE id=? AND owner_id=?",confirmationPlan,actor.id());
   if(((java.time.OffsetDateTime)old.get("createdAt")).toInstant().plusSeconds(1800).isBefore(s.clock.instant()))throw Api.conflict("年份确认预览已过期，请重新预览");
   var frozen=parse(old.get("rowsJson").toString());
   if(!frozen.isArray()||frozen.isEmpty()||!frozen.get(0).path("confirmationFingerprint").asText().equals(fingerprint))throw Api.conflict("名单内容已变化，请重新预览后确认年份");
   for(var row:frozen)if(!row.path("yearConflict").isMissingNode()&&!row.path("yearConflict").isNull())confirmedAccounts.add(row.path("account").asText(""));
  }
  Set<String> existing=new HashSet<>(s.db.queryForList("SELECT account FROM lab_user",String.class));List<Object> rows=new ArrayList<>(),eligible=new ArrayList<>();Map<String,Integer> summary=new LinkedHashMap<>();
  for(String field:List.of("total","valid","invalid","existing","duplicate","ignored","needsConfirmation"))summary.put(field,field.equals("total")?input.size():0);
  for(int i=0;i<normalized.size();i++){
   var normalizedRow=normalized.get(i);var raw=input.get(i);String account=normalizedRow.get("account").toString(),name=normalizedRow.get("name").toString(),cls=normalizedRow.get("className").toString(),grade=normalizedRow.get("grade").toString(),phone=normalizedRow.get("phone").toString(),yearRaw=normalizedRow.get("enrollmentYear").toString(),marker=normalizedRow.get("teacherMarker").toString(),rawD=mixed?normalizedRow.get("yearOrTeacher").toString():"",rowType=mixed?RosterPolicy.mixedType(account,rawD):type;List<String> errors=new ArrayList<>();
   if(mixed){if(!yearRaw.isEmpty()||!marker.isEmpty())errors.add("“年级/老师”列内容与名单结构不符，请使用账号导入模版重新检查");yearRaw=rowType.equals("student")?rawD:"";marker=rowType.equals("teacher")?rawD:"";}
   if((Boolean)normalizedRow.get("formula"))errors.add("字段包含公式，请改为实际值");if(!normalizedRow.get("formatError").toString().isBlank())errors.add("列数不正确，请检查多余的内容或分隔符");
   try{AccountPolicy.rosterAccount(rowType,account);}catch(Api.Failure error){errors.add(error.getMessage());}
   if(name.length()>80)errors.add("姓名不能超过80字");if(cls.length()>100)errors.add("班级或院系不能超过100字");if(grade.length()>40)errors.add("当前所在级不能超过40字");
   if(!phone.isEmpty()&&!phone.matches("[0-9+() -]{5,32}"))errors.add("联系电话格式无效");if(name.chars().anyMatch(Character::isISOControl)||cls.chars().anyMatch(Character::isISOControl)||grade.chars().anyMatch(Character::isISOControl))errors.add("资料不能包含控制字符");
   Integer year=null,derived=RosterPolicy.derivedYear(account);
   if(unified){
    if(!grade.isEmpty())errors.add("统一五列的D列是入学年份或老师标记，不写当前所在级");
    if(rowType.equals("teacher")){if(!yearRaw.isEmpty()||!marker.isEmpty()&&!marker.equals("老师"))errors.add("老师的D列须为空或老师");}
    else {if(!marker.isEmpty())errors.add("学生批次不能填写老师标记");try{year=RosterPolicy.enrollmentYear(yearRaw);}catch(Api.Failure error){errors.add(error.getMessage());}}
   }else {
    if(!yearRaw.isEmpty()||!marker.isEmpty())errors.add("新五列字段必须选择统一五列格式");if(rowType.equals("teacher")&&!grade.isBlank())errors.add("老师旧格式不使用当前所在级");
   }
   boolean emptyRow=List.of(account,name,cls,grade,phone,yearRaw,marker,rawD,normalizedRow.get("formatError").toString()).stream().allMatch(String::isEmpty)&&!(Boolean)normalizedRow.get("formula");
   if(unified&&!emptyRow&&(account.isEmpty()||name.isEmpty()))errors.add("学号/工号和姓名均为必填，非空行请补齐后导入");
   boolean conflict=unified&&rowType.equals("student")&&year!=null&&derived!=null&&!year.equals(derived),claim=raw.path("confirmEnrollmentYear").asBoolean(),accepted=false;
   if(claim&&!existing.contains(account)){
    if(!conflict)throw Api.invalid("仅实际冲突的入学年份需要确认");
    if(!confirmedAccounts.contains(account))throw Api.invalid("请先预览本批名单，再由管理员确认采用表格年份");accepted=true;
   }
   String status=account.isEmpty()||name.isEmpty()?unified&&!emptyRow?"invalid":"ignored":existing.contains(account)?"existing":!errors.isEmpty()?"invalid":counts.get(account)>1?"duplicate":conflict&&!accepted?"confirmation":"valid";
   String message=switch(status){case "ignored"->"缺少账号或姓名，忽略此行";case "invalid"->String.join("；",errors);case "duplicate"->"本批次账号重复，请修正后重新检查";case "existing"->"账号已存在，保留全部原资料、密码和权限";case "confirmation"->"入学年份与学号推算不一致，请确认采用表格年份后重新预览";default->"可导入";};
   var item=new LinkedHashMap<String,Object>(normalizedRow);item.putAll(map("grade",unified||rowType.equals("teacher")?"":grade,"enrollmentYear",year,"format",format,"accountType",rowType,"initialPasswordPolicy","account-last6","confirmationFingerprint",fingerprint,"confirmationRequired",status.equals("confirmation"),"confirmationAccepted",accepted,"yearConflict",conflict?map("providedYear",year,"derivedYear",derived,"accountCohort",account.substring(4,6)):null,"status",status,"message",message));
   rows.add(item);if(status.equals("valid")||status.equals("confirmation"))eligible.add(item);summary.merge(status.equals("confirmation")?"needsConfirmation":status,1,Integer::sum);
  }
  String id=Store.id();int invalid=summary.get("invalid")+summary.get("duplicate")+summary.get("needsConfirmation");
  s.db.update("INSERT INTO import_plan(id,owner_id,rows_json,created_at,validation_errors) VALUES(?,?,?,?,?)",id,actor.id(),encode(eligible),s.now(),invalid);
  return Api.ok(map("planId",id,"format",format,"accountType",type,"rows",rows,"summary",summary,"canCommit",invalid==0&&summary.get("valid")>0),req);
 }
 @PostMapping("/commit") @Transactional public Object commit(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){
  Store.role(s.lockUser(actor.id()),"admin");var input=new Input(body,"planId");String planId=input.text("planId",36,true);
  var plan=b.one("SELECT * FROM import_plan WHERE id=? AND owner_id=? FOR UPDATE",planId,actor.id());
  if(plan.get("jobId")!=null)return job(actor,plan.get("jobId").toString(),req);
  if(((java.time.OffsetDateTime)plan.get("createdAt")).toInstant().plusSeconds(1800).isBefore(s.clock.instant()))throw Api.conflict("预览已过期，请重新校验");
  if(((Number)plan.get("validationErrors")).intValue()>0)throw Api.invalid("请先修正格式错误或重复账号，重新检查后再导入");
  var rows=parse(plan.get("rowsJson").toString());if(rows.isEmpty())throw Api.invalid("没有可导入的新账号");
  String id=Store.id();var job=map("id",id,"state","running","total",rows.size(),"processed",0,"created",0,"skipped",0,"failed",0,"errors",List.of());
  s.db.update("INSERT INTO import_job(id,owner_id,result_json,created_at) VALUES(?,?,?,?)",id,actor.id(),encode(job),s.now());s.db.update("UPDATE import_plan SET job_id=? WHERE id=?",id,planId);return Api.ok(job,req);
 }
 @GetMapping("/jobs/{id}") public Object job(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req){Store.role(actor,"admin");var row=b.one("SELECT result_json FROM import_job WHERE id=? AND owner_id=?",id,actor.id());return Api.ok(parse(row.get("resultJson").toString()),req);}
 @Scheduled(initialDelay=3000,fixedDelay=200) @Transactional public void process(){
  for(var job:b.rows("SELECT j.*,p.rows_json FROM import_job j JOIN import_plan p ON p.job_id=j.id WHERE j.result_json LIKE '%\"state\":\"running\"%' ORDER BY j.created_at LIMIT 1 FOR UPDATE")){
   var state=(ObjectNode)parse(job.get("resultJson").toString());int index=state.path("processed").asInt();var rows=parse(job.get("rowsJson").toString());
   if(index>=rows.size())state.put("state","done");
   else {
    var owner=s.user(job.get("ownerId").toString());
    if(!owner.enabled()||!owner.accountRole().equals("admin")){state.put("state","done");state.put("failed",state.path("total").asInt()-index);((com.fasterxml.jackson.databind.node.ArrayNode)state.get("errors")).add(b.json.valueToTree(map("message","管理员账号失效，任务停止")));}
    else {
     var row=rows.get(index);String account=row.path("account").asText("");
     if(s.db.queryForObject("SELECT COUNT(*) FROM lab_user WHERE account=?",Long.class,account)>0)state.put("skipped",state.path("skipped").asInt()+1);
     else {
      boolean created=insertAccount(row);String count=created?"created":"skipped";state.put(count,state.path(count).asInt()+1);
     }
     state.put("processed",index+1);if(index+1==rows.size())state.put("state","done");
    }
   }
   s.db.update("UPDATE import_job SET result_json=? WHERE id=?",encode(state),job.get("id"));
  }
 }

 // A duplicate-key statement leaves MySQL/H2 transactions usable. Only account duplicates are skipped;
 // deadlocks and other transaction failures propagate, rolling back this entire job step.
 boolean insertAccount(JsonNode row){
  String type=AccountPolicy.importType(row.path("accountType").asText("student")),format=RosterPolicy.format(row.path("format").asText("legacy")),account=row.path("account").asText("");
  if(!row.path("initialPasswordPolicy").asText("account-last6").equals("account-last6"))throw Api.invalid("导入密码策略无效");
  if(row.path("confirmationRequired").asBoolean())throw Api.invalid("入学年份尚未确认");
  String password=AccountPolicy.initialPassword(type,account),cls=row.path("className").asText("");Integer year=RosterPolicy.unified(format)&&!type.equals("teacher")&&!row.path("enrollmentYear").isNull()&&!row.path("enrollmentYear").isMissingNode()?row.path("enrollmentYear").asInt():null;
  try {
   AccountCohorts.lock(s);
   s.db.update("INSERT INTO lab_user(id,account,name,class_name,pickup_class_name,phone,grade,role,account_type,can_manage,password_hash,created_at,enrollment_year) VALUES(?,?,?,?,?,?,?,'student',?,FALSE,?,?,?)",
    Store.id(),account,row.path("name").asText(""),cls,AccountPolicy.trustedClass(type,cls),row.path("phone").asText(""),RosterPolicy.unified(format)||type.equals("teacher")?"":row.path("grade").asText(""),type,passwords.encode(password),s.now(),year);return true;
  } catch(DuplicateKeyException duplicate){
   // Current locking read, not a repeatable-read snapshot created before the competing insert.
   if(s.db.queryForList("SELECT id FROM lab_user WHERE account=? FOR UPDATE",String.class,account).isEmpty())throw duplicate;return false;
  }
 }
}
