package cn.pocketlab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class Catalog {
    final Store s; final ObjectMapper json;
    Catalog(Store s,ObjectMapper json){this.s=s;this.json=json;}
    @GetMapping("/equipment")
    public ResponseEntity<Api.Envelope<Api.Page<Api.Equipment>>> list(@AuthenticationPrincipal Api.User actor,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Boolean active,HttpServletRequest req) throws JsonProcessingException {
        int size=pageSize==null?(actor.role().equals("student")?9:10):pageSize;Store.pagination(page,size);
        keyword=Business.search(keyword,q);
        if(keyword.length()>100||category.length()>80)throw Api.invalid("筛选条件过长");
        List<Object> args=new ArrayList<>();String where=" WHERE 1=1";
        if(actor.role().equals("student"))where+=" AND active=TRUE";
        if(active!=null){where+=" AND active=?";args.add(active);}
        if(!keyword.isBlank()){where+=" AND (name LIKE ? ESCAPE '!' OR model LIKE ? ESCAPE '!')";String match="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";args.add(match);args.add(match);}
        if(!category.isBlank()){where+=" AND category=?";args.add(category);}
        long total=s.db.queryForObject("SELECT COUNT(*) FROM equipment"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var items=s.db.query(Store.EQUIPMENT_SELECT+where+" ORDER BY CASE WHEN e.id='CAT01' THEN 0 WHEN e.id='CAT03' THEN 1 ELSE 2 END,e.id LIMIT ? OFFSET ?",Store.EQUIPMENT,args.toArray());
        return cached(new Api.Page<>(items,total,page,size),req);
    }
    @GetMapping("/equipment/{id}")
    public ResponseEntity<Api.Envelope<Api.Equipment>> detail(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req) throws JsonProcessingException {
        // Inactive equipment remains readable for historical loans, but cannot accept new applications.
        return cached(s.equipment(id,false),req);
    }
    private <T> ResponseEntity<Api.Envelope<T>> cached(T data,HttpServletRequest req) throws JsonProcessingException {
        String etag="\""+Security.digest(json.writeValueAsString(data))+"\"";
        var builder=ResponseEntity.status(matches(req.getHeader("If-None-Match"),etag)?304:200)
            .header("ETag",etag).header("Cache-Control","private, no-cache").header("Vary","Authorization, Origin");
        return builder.body(matches(req.getHeader("If-None-Match"),etag)?null:Api.ok(data,req));
    }
    private boolean matches(String header,String etag) {
        return header!=null&&Arrays.stream(header.split(",")).map(String::trim).anyMatch(v->v.equals(etag)||v.equals("W/"+etag)||v.equals("*"));
    }
    @GetMapping("/duty-slots")
    public Api.Envelope<Api.Page<Api.Duty>> duties(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="false") boolean includeDisabled,@RequestParam(required=false) Boolean enabled,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to,HttpServletRequest req) {
        Store.pagination(page,pageSize);
        if(includeDisabled||Boolean.FALSE.equals(enabled))Store.role(actor,"reviewer","admin");
        var snapshot=s.clock.instant().atZone(java.time.ZoneId.of("Asia/Shanghai"));var today=snapshot.toLocalDate();
        var args=new ArrayList<Object>();String where=" WHERE slot_date>=?";args.add(from==null?today:from);
        if(from==null&&to==null){
            where+=" AND (slot_date>? OR (slot_date=? AND end_time>?))";args.add(today);args.add(today);args.add(snapshot.toLocalTime());
        }
        if(to!=null){if(from!=null&&to.isBefore(from))throw Api.invalid("日期范围无效");where+=" AND slot_date<=?";args.add(to);}
        if(includeDisabled)where+=" AND rule_id IS NULL";
        if(enabled!=null||!includeDisabled){where+=" AND enabled=?";args.add(enabled==null?true:enabled);}
        long total=s.db.queryForObject("SELECT COUNT(*) FROM duty_slot"+where,Long.class,args.toArray());args.add(pageSize);args.add((page-1)*pageSize);
        return Api.ok(new Api.Page<>(s.db.query("SELECT * FROM duty_slot"+where+" ORDER BY slot_date,start_time,id LIMIT ? OFFSET ?",Store.DUTY,args.toArray()),total,page,pageSize),req);
    }
    @PostMapping("/duty-slots") @Transactional
    public Api.Envelope<Api.Duty> createDuty(@AuthenticationPrincipal Api.User actor,@Valid @RequestBody Api.DutyInput input,HttpServletRequest req) {
        Store.role(s.lockUser(actor.id()),"reviewer");
        if(input.date().isBefore(s.today())||!input.endTime().isAfter(input.startTime()))throw Api.invalid("值班日期或时间无效");
        String id=Store.id();
        s.db.update("INSERT INTO duty_slot(id,slot_date,start_time,end_time,location,enabled) VALUES(?,?,?,?,?,TRUE)",id,input.date(),input.startTime(),input.endTime(),input.location().trim());
        return Api.ok(s.db.queryForObject("SELECT * FROM duty_slot WHERE id=?",Store.DUTY,id),req);
    }
}
