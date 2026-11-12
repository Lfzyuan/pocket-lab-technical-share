package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api")
public class Directory {
    final Store s;final Business b;final PasswordEncoder passwords;
    Directory(Store s,Business b,PasswordEncoder passwords){this.s=s;this.b=b;this.passwords=passwords;}
    @GetMapping("/users/search")
    public Api.Envelope<?> search(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){Store.role(actor,"student");keyword=Business.search(keyword,q);if(keyword.trim().length()<1)throw Api.invalid("请至少输入1个字或字符检索人员");String match=like(keyword.trim());return Api.ok(b.page("SELECT id,account,name,class_name","FROM lab_user"," WHERE enabled=TRUE AND role='student' AND (account LIKE ? ESCAPE '!' OR name LIKE ? ESCAPE '!')",List.of(match,match),"account",page,pageSize),req);}
    @GetMapping("/users/pickup-candidates")
    public Api.Envelope<?> pickupCandidates(@AuthenticationPrincipal Api.User actor,
        @RequestParam String keyword,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="10") int pageSize,HttpServletRequest req) {
        Store.role(actor,"student");
        String search=keyword.trim();
        if(search.length()<1||search.length()>80)throw Api.invalid("请输入1至80个字或字符检索代领人");
        String match=like(search);
        return Api.ok(b.page("SELECT id,account,name","FROM lab_user",
            " WHERE "+Store.PICKUP_CANDIDATE+" AND (account LIKE ? ESCAPE '!' OR name LIKE ? ESCAPE '!')",
            List.of(actor.id(),match,match),"account",page,pageSize),req);
    }
    @GetMapping("/users/pickup-classmates")
    public Api.Envelope<?> pickupClassmates(@AuthenticationPrincipal Api.User actor,
        @RequestParam String keyword,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req) {
        Store.role(actor,"student");
        if(keyword.trim().length()<1||keyword.length()>80)throw Api.invalid("请输入1至80个字或字符检索本班同学");
        String className=s.pickupClass(actor.id());
        Store.pagination(page,pageSize);
        if(className.isBlank())return Api.ok(new Api.Page<>(List.of(),0,page,pageSize),req);
        String match=like(keyword.trim());
        return Api.ok(b.page("SELECT id,account,name,pickup_class_name AS class_name","FROM lab_user",
            " WHERE "+Store.PICKUP_CLASSMATE+" AND (account LIKE ? ESCAPE '!' OR name LIKE ? ESCAPE '!')",
            List.of(actor.id(),className,match,match),"account",page,pageSize),req);
    }
    @GetMapping("/users/pickup-classmates/{id}/contact")
    public Api.Envelope<?> pickupContact(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req) {
        Store.role(actor,"student");
        String className=s.pickupClass(actor.id());
        if(className.isBlank())throw Api.missing();
        String phone=s.db.queryForList("SELECT COALESCE(phone,'') FROM lab_user WHERE "+Store.PICKUP_CLASSMATE+" AND id=?",
            String.class,actor.id(),className,id).stream().findFirst().orElseThrow(Api::missing);
        return Api.ok(Map.of("phone",phone),req);
    }
    @PostMapping("/users/import") @Transactional
    public Api.Envelope<Object> importUsers(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /users/import",body,()->{
        Store.role(actor,"admin");var p=new Input(body,"users","dryRun");var users=body.get("users");if(users==null||!users.isArray()||users.isEmpty()||users.size()>500)throw Api.invalid("每批导入1至500名学生");boolean preview=p.has("dryRun")&&p.bool("dryRun");
        if(!preview)AccountCohorts.lock(s);
        var seen=new HashSet<String>();var valid=new ArrayList<Input>();var errors=new ArrayList<Map<String,Object>>();
        for(int i=0;i<users.size();i++){try{
            var u=new Input(users.get(i),"account","name","className","password","grade","phone");String account=u.text("account",32,true);u.text("name",80,true);u.text("className",100,false);u.text("grade",40,false);String phone=u.text("phone",32,false);if(!phone.isEmpty()&&!phone.matches("[0-9+() -]{5,32}"))throw Api.invalid("联系电话格式无效");String password=u.text("password",64,true);
            if(!account.matches("[A-Za-z0-9_-]{2,32}"))throw Api.invalid("账号格式无效");
            if(password.codePointCount(0,password.length())<6)throw Api.invalid("密码至少6位");
            if(!seen.add(account)||s.db.queryForObject("SELECT COUNT(*) FROM lab_user WHERE account=?",Long.class,account)>0)throw Api.conflict("账号重复或已经存在");valid.add(u);
        }catch(Api.Failure e){errors.add(map("row",i+1,"message",e.getMessage()));}}
        if(!errors.isEmpty()){if(!preview)throw Api.invalid("整批未导入："+errors);return map("valid",false,"imported",0,"total",users.size(),"errors",errors,"items",List.of());}
        var items=new ArrayList<Object>();if(!preview)for(var u:valid){String id=Store.id();s.db.update("INSERT INTO lab_user(id,account,name,class_name,pickup_class_name,grade,phone,role,password_hash,created_at) VALUES(?,?,?,?,?,?,?,'student',?,?)",id,u.text("account",32,true),u.text("name",80,true),u.text("className",100,false),u.text("className",100,false),u.text("grade",40,false),u.text("phone",32,false),passwords.encode(u.node.get("password").asText()),s.now());items.add(s.user(id));}
        return map("valid",true,"dryRun",preview,"imported",preview?0:valid.size(),"total",valid.size(),"errors",List.of(),"items",items);
    }),req);}
    @PutMapping("/duty-slots/{id}") @Transactional
    public Api.Envelope<Object> duty(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PUT /duty-slots/"+id,body,()->{Store.role(actor,"reviewer");var p=new Input(body,"version","date","startTime","endTime","location","enabled");var row=b.one("SELECT * FROM duty_slot WHERE id=? FOR UPDATE",id);b.version(row,p);var date=p.date("date");var start=p.time("startTime");var end=p.time("endTime");if(date.isBefore(s.today())||!end.isAfter(start))throw Api.invalid("值班日期或时间无效");
        if(s.db.queryForObject("SELECT COUNT(*) FROM borrow_request WHERE slot_id=? AND status IN ('pending','approved')",Long.class,id)>0)throw Api.conflict("此值班已有未领取申请，请先协调申请人；可以停用以阻止新申请");
        s.db.update("UPDATE duty_slot SET slot_date=?,start_time=?,end_time=?,location=?,enabled=?,version=version+1 WHERE id=?",date,start,end,p.text("location",200,true),p.bool("enabled"),id);return b.one("SELECT id,slot_date AS date,start_time,end_time,location,enabled,version FROM duty_slot WHERE id=?",id);
    }),req);}
    @PatchMapping("/duty-slots/{id}/enabled") @Transactional
    public Api.Envelope<Object> enabled(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PATCH /duty-slots/"+id+"/enabled",body,()->{Store.role(actor,"reviewer");var p=new Input(body,"enabled");if(s.db.update("UPDATE duty_slot SET enabled=?,version=version+1 WHERE id=?",p.bool("enabled"),id)==0)throw Api.missing();return b.one("SELECT id,slot_date AS date,start_time,end_time,location,enabled,version FROM duty_slot WHERE id=?",id);}),req);}
}
