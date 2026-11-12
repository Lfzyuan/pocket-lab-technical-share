package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api")
public class Messages {
    final Store s;final Business b;
    Messages(Store s,Business b){this.s=s;this.b=b;}
    @GetMapping("/favorites")
    public Api.Envelope<?> favorites(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){Store.role(actor,"student");Store.pagination(page,pageSize);long total=s.db.queryForObject("SELECT COUNT(*) FROM favorite WHERE user_id=?",Long.class,actor.id());var items=s.db.query(Store.EQUIPMENT_SELECT+" JOIN favorite f ON f.equipment_id=e.id WHERE f.user_id=? ORDER BY f.created_at DESC,e.id LIMIT ? OFFSET ?",Store.EQUIPMENT,actor.id(),pageSize,(page-1)*pageSize);return Api.ok(new Api.Page<>(items,total,page,pageSize),req);}
    @PostMapping("/favorites") @Transactional
    public Api.Envelope<Object> favorite(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /favorites",body,()->{Store.role(actor,"student");var p=new Input(body,"equipmentId");String id=p.text("equipmentId",36,true);if(!s.equipment(id,false).active())throw Api.conflict("器材已下架");if(s.db.queryForObject("SELECT COUNT(*) FROM favorite WHERE user_id=? AND equipment_id=?",Long.class,actor.id(),id)==0)s.db.update("INSERT INTO favorite(user_id,equipment_id,created_at) VALUES(?,?,?)",actor.id(),id,s.now());return map("equipmentId",id,"favorited",true);}),req);}
    @DeleteMapping("/favorites/{id}") @Transactional
    public Api.Envelope<Object> unfavorite(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"DELETE /favorites/"+id,JsonNodeFactory.instance.objectNode(),()->{Store.role(actor,"student");s.db.update("DELETE FROM favorite WHERE user_id=? AND equipment_id=?",actor.id(),id);return map("equipmentId",id,"favorited",false);}),req);}
    @GetMapping("/stock-subscriptions")
    public Api.Envelope<?> subscriptions(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){Store.role(actor,"student");return Api.ok(b.page("SELECT *","FROM stock_subscription"," WHERE user_id=?",List.of(actor.id()),"created_at DESC,id DESC",page,pageSize),req);}
    @PostMapping("/stock-subscriptions") @Transactional
    public Api.Envelope<Object> subscribe(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /stock-subscriptions",body,()->{Store.role(actor,"student");var p=new Input(body,"equipmentId");String equipment=p.text("equipmentId",36,true);var e=s.equipment(equipment,true);if(!e.active()||e.available()>0)throw Api.conflict("仅能订阅当前无货的上架器材");var previous=b.rows("SELECT * FROM stock_subscription WHERE user_id=? AND equipment_id=?",actor.id(),equipment);if(!previous.isEmpty()&&"waiting".equals(previous.getFirst().get("status")))return previous.getFirst();s.db.update("DELETE FROM stock_subscription WHERE user_id=? AND equipment_id=?",actor.id(),equipment);String id=Store.id();s.db.update("INSERT INTO stock_subscription(id,user_id,equipment_id,status,created_at) VALUES(?,?,?,'waiting',?)",id,actor.id(),equipment,s.now());return b.one("SELECT * FROM stock_subscription WHERE id=?",id);}),req);}
    @DeleteMapping("/stock-subscriptions/{id}") @Transactional
    public Api.Envelope<Object> unsubscribe(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"DELETE /stock-subscriptions/"+id,JsonNodeFactory.instance.objectNode(),()->{Store.role(actor,"student");if(s.db.update("UPDATE stock_subscription SET status='cancelled' WHERE id=? AND user_id=?",id,actor.id())==0)throw Api.missing();return b.one("SELECT * FROM stock_subscription WHERE id=?",id);}),req);}
    @GetMapping("/notifications")
    public Api.Envelope<?> notifications(@AuthenticationPrincipal Api.User actor,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
        @RequestParam(required=false) Boolean read,HttpServletRequest req){
        Store.role(actor,"student","reviewer","admin");
        var args=new ArrayList<Object>();args.add(actor.id());if(read!=null)args.add(read);
        var data=b.page("SELECT id,user_id,title,body,request_id,equipment_id,is_read AS `read`,created_at AS at",
            "FROM user_notification"," WHERE user_id=? AND deleted_at IS NULL"+(read==null?"":" AND is_read=?"),args,
            "created_at DESC,id DESC",page,pageSize);
        return Api.ok(map("items",data.items(),"total",data.total(),"page",page,"pageSize",pageSize,"unreadCount",unread(actor)),req);
    }

    private long unread(Api.User actor){
        return s.db.queryForObject("SELECT COUNT(*) FROM user_notification WHERE user_id=? AND is_read=FALSE AND deleted_at IS NULL",Long.class,actor.id());
    }

    @PostMapping("/notifications/{id}/read") @Transactional
    public Api.Envelope<Object> read(@AuthenticationPrincipal Api.User actor,@PathVariable String id,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"POST /notifications/"+id+"/read",JsonNodeFactory.instance.objectNode(),()->{
            Store.role(actor,"student","reviewer","admin");
            if(s.db.update("UPDATE user_notification SET is_read=TRUE WHERE id=? AND user_id=? AND deleted_at IS NULL",id,actor.id())==0)throw Api.missing();
            return map("id",id,"read",true,"unreadCount",unread(actor));
        }),req);
    }

    @PostMapping("/notifications/batch") @Transactional
    public Api.Envelope<Object> batch(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"POST /notifications/batch",body,()->{
            Store.role(actor,"student","reviewer","admin");
            var input=new Input(body,"action","ids");
            String action=input.choice("action","read-all","delete-selected","delete-all");
            int changed;
            if(action.equals("delete-selected")){
                JsonNode ids=body.path("ids");
                if(!ids.isArray()||ids.isEmpty()||ids.size()>100)throw Api.invalid("请选择1至100条消息");
                var unique=new HashSet<String>();
                for(var id:ids)if(!id.isTextual()||!id.asText().matches("[A-Za-z0-9_-]{1,36}")||!unique.add(id.asText()))throw Api.invalid("消息编号无效或重复");
                var args=new ArrayList<Object>();args.add(s.now());args.add(actor.id());args.addAll(unique);
                changed=s.db.update("UPDATE user_notification SET deleted_at=? WHERE user_id=? AND deleted_at IS NULL AND id IN ("+
                    String.join(",",Collections.nCopies(unique.size(),"?"))+")",args.toArray());
                if(changed!=unique.size())throw Api.conflict("部分消息已变化，请刷新后重试");
            }else{
                if(body.has("ids"))throw Api.invalid("全部操作不接受单条消息编号");
                changed=action.equals("read-all")
                    ?s.db.update("UPDATE user_notification SET is_read=TRUE WHERE user_id=? AND deleted_at IS NULL AND is_read=FALSE",actor.id())
                    :s.db.update("UPDATE user_notification SET deleted_at=? WHERE user_id=? AND deleted_at IS NULL",s.now(),actor.id());
            }
            return map("affected",changed,"unreadCount",unread(actor));
        }),req);
    }

    @GetMapping({"/announcements","/admin/announcements"})
    public Api.Envelope<?> announcements(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){boolean manage=req.getRequestURI().endsWith("/admin/announcements");if(manage)Store.role(actor,"reviewer","admin");return Api.ok(b.page("SELECT *","FROM announcement",manage?"":" WHERE enabled=TRUE",List.of(),"updated_at DESC,id DESC",page,pageSize),req);}
    @PostMapping("/announcements") @Transactional
    public Api.Envelope<Object> createAnnouncement(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /announcements",body,()->{Store.role(actor,"reviewer","admin");var p=new Input(body,"content","enabled");String id=Store.id();s.db.update("INSERT INTO announcement(id,content,enabled,updated_by,updated_at) VALUES(?,?,?,?,?)",id,p.text("content",500,true),p.bool("enabled"),actor.id(),s.now());return b.one("SELECT * FROM announcement WHERE id=?",id);}),req);}
    @PutMapping("/announcements/{id}") @Transactional
    public Api.Envelope<Object> editAnnouncement(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PUT /announcements/"+id,body,()->{Store.role(actor,"reviewer","admin");var p=new Input(body,"content","enabled","version");var row=b.one("SELECT * FROM announcement WHERE id=?",id);b.version(row,p);s.db.update("UPDATE announcement SET content=?,enabled=?,updated_by=?,updated_at=?,version=version+1 WHERE id=?",p.text("content",500,true),p.bool("enabled"),actor.id(),s.now(),id);return b.one("SELECT * FROM announcement WHERE id=?",id);}),req);}
    @PatchMapping("/announcements/{id}/enabled") @Transactional
    public Api.Envelope<Object> enableAnnouncement(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PATCH /announcements/"+id+"/enabled",body,()->{Store.role(actor,"reviewer","admin");var p=new Input(body,"enabled");if(s.db.update("UPDATE announcement SET enabled=?,updated_by=?,updated_at=?,version=version+1 WHERE id=?",p.bool("enabled"),actor.id(),s.now(),id)==0)throw Api.missing();return b.one("SELECT * FROM announcement WHERE id=?",id);}),req);}
    @DeleteMapping("/announcements/{id}") @Transactional
    public Api.Envelope<Object> deleteAnnouncement(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"DELETE /announcements/"+id,JsonNodeFactory.instance.objectNode(),()->{Store.role(actor,"reviewer","admin");if(s.db.update("DELETE FROM announcement WHERE id=?",id)==0)throw Api.missing();return map("id",id,"deleted",true);}),req);}
    @Scheduled(initialDelay=60000,fixedDelay=60000) @Transactional
    public void scheduledNotices(){
        s.db.queryForObject("SELECT name FROM business_lock WHERE name='extensions' FOR UPDATE",String.class);
        for(var row:b.rows("SELECT id,user_id,equipment_id,due_date FROM borrow_request WHERE status IN ('borrowed','returning') AND due_date<=?",s.today().plusDays(3))){
            var due=(java.time.LocalDate)row.get("dueDate");String stage=due.isBefore(s.today())?"overdue":"due";String id=(String)row.get("id");b.notify((String)row.get("userId"),stage.equals("overdue")?"借用已逾期":"器材即将到期","预计归还日期："+due+"，请按值班时间归还。","borrow:"+id+":"+stage,id,(String)row.get("equipmentId"));
        }
        for(var row:b.rows("SELECT id,user_id,equipment_id FROM borrow_request WHERE status='returning'")){
            String id=(String)row.get("id");
            var requests=b.rows("SELECT id,occurred_at FROM borrow_event WHERE borrow_id=? AND action='return-request' ORDER BY occurred_at DESC,id DESC LIMIT 1",id);
            if(requests.isEmpty())continue;
            var request=requests.getFirst();var at=(java.time.OffsetDateTime)request.get("occurredAt");
            if(ReturnPolicy.overdue(at.toInstant(),s.clock.instant()))b.notify((String)row.get("userId"),"申请归还已超过一周",
                "申请归还后请于一周内交回器材，交回截止："+ReturnPolicy.format(at.plus(ReturnPolicy.WINDOW))+"。系统尚未记录验收归还，请查看值班时间并尽快交回器材和配件；如已交回，请联系老师完成验收。原预计归还日期仍有效。",
                "return-window:"+request.get("id"),id,(String)row.get("equipmentId"));
        }
        for(var row:b.rows("SELECT x.*,e.name FROM stock_subscription x JOIN equipment e ON e.id=x.equipment_id JOIN lab_user u ON u.id=x.user_id WHERE x.status='waiting' AND e.active=TRUE AND e.stock-e.reserved-e.borrowed>0 AND u.enabled=TRUE")){
            b.notify((String)row.get("userId"),"订阅器材已有库存",row.get("name")+"当前有货，请查询实时库存。本提醒不预留器材。","subscription:"+row.get("id"),null,(String)row.get("equipmentId"));s.db.update("UPDATE stock_subscription SET status='notified' WHERE id=?",row.get("id"));
        }
    }
}
