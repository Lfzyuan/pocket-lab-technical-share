package cn.pocketlab;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/borrow-requests")
public class Borrows {
    final Store s; final ObjectMapper json;final LoanLimits limits;final AssetLedger assets;
    static final Set<String> STATES=Set.of("pending","approved","borrowed","returning","returned","rejected","cancelled","closed");
    Borrows(Store s,ObjectMapper json,LoanLimits limits,AssetLedger assets){this.s=s;this.json=json;this.limits=limits;this.assets=assets;}
    private static final String LOAN_FROM=" FROM borrow_request br JOIN equipment e ON e.id=br.equipment_id JOIN lab_user u ON u.id=br.user_id";
    private static final String LOAN_ORDER="CASE WHEN br.status='returning' THEN 0 WHEN br.status='approved' THEN 1 WHEN br.status='pending' THEN 2 WHEN br.status='borrowed' THEN 3 ELSE 4 END,br.created_at DESC,br.id DESC";
    private record LoanFilter(String where,List<Object> args) {}
    private LoanFilter filter(Api.User actor,boolean pickupProxy,String status,String statuses,String equipmentId,
        String q,String keyword,LocalDate from,LocalDate to,String borrowerId) {
        String where=" WHERE 1=1";var args=new ArrayList<Object>();
        if(pickupProxy){where+=" AND br.pickup_proxy_id=?";args.add(actor.id());}
        else if(actor.role().equals("student")){where+=" AND br.user_id=?";args.add(actor.id());}
        if(status!=null&&statuses!=null)throw Api.invalid("status与statuses不能同时使用");
        if(statuses!=null){
            var selected=Arrays.stream(statuses.split(",",-1)).map(String::trim).distinct().toList();
            if(selected.isEmpty()||selected.stream().anyMatch(value->!STATES.contains(value)))throw Api.invalid("未知借用状态组合");
            where+=" AND br.status IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")";args.addAll(selected);
        }else if(status!=null){if(!STATES.contains(status))throw Api.invalid("未知借用状态");where+=" AND br.status=?";args.add(status);}
        if(equipmentId!=null&&!equipmentId.isBlank()){if(equipmentId.length()>36)throw Api.invalid("器材ID过长");where+=" AND br.equipment_id=?";args.add(equipmentId);}
        if(borrowerId!=null&&!borrowerId.isBlank()){if(borrowerId.length()>36)throw Api.invalid("借用人ID过长");where+=" AND br.user_id=?";args.add(borrowerId);}
        String search=Business.search(keyword,q);
        if(search.length()>100)throw Api.invalid("搜索条件过长");
        if(!search.isBlank()){
            String match=Business.like(search.trim());
            if(pickupProxy){
                where+=" AND (br.user_name LIKE ? ESCAPE '!' OR e.name LIKE ? ESCAPE '!')";
                args.addAll(List.of(match,match));
            }else{
                where+=" AND (br.user_name LIKE ? ESCAPE '!' OR u.account LIKE ? ESCAPE '!' OR e.name LIKE ? ESCAPE '!' OR e.model LIKE ? ESCAPE '!' OR br.asset_number LIKE ? ESCAPE '!' OR br.id LIKE ? ESCAPE '!' OR br.purpose LIKE ? ESCAPE '!')";
                args.addAll(Collections.nCopies(7,match));
            }
        }
        var dates=new Business.Filters();dates.dates("br.created_at",from,to,ZoneId.of("Asia/Shanghai"));
        where+=dates.where.replace(" WHERE 1=1","");args.addAll(dates.args);
        return new LoanFilter(where,args);
    }
    @GetMapping
    public Api.Envelope<Api.Page<Api.Borrow>> list(@AuthenticationPrincipal Api.User actor,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer pageSize,
        @RequestParam(required=false) String status,@RequestParam(required=false) String statuses,@RequestParam(required=false) String equipmentId,
        @RequestParam(defaultValue="false") boolean pickupProxy,@RequestParam(defaultValue="") String q,
        @RequestParam(defaultValue="") String keyword,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,
        @RequestParam(required=false) String borrowerId,HttpServletRequest req) {
        int size=pageSize==null?(actor.role().equals("student")?5:10):pageSize;Store.pagination(page,size);
        var f=filter(actor,pickupProxy,status,statuses,equipmentId,q,keyword,from,to,borrowerId);
        long total=s.db.queryForObject("SELECT COUNT(*)"+LOAN_FROM+f.where(),Long.class,f.args().toArray());
        var args=new ArrayList<>(f.args());args.add(size);args.add((page-1)*size);
        var ids=s.db.queryForList("SELECT br.id"+LOAN_FROM+f.where()+" ORDER BY "+LOAN_ORDER+" LIMIT ? OFFSET ?",String.class,args.toArray());
        return Api.ok(new Api.Page<>(ids.stream().map(id->s.borrow(id,actor,false,pickupProxy)).toList(),total,page,size),req);
    }
    @PostMapping("/bulk-preview") @Transactional(readOnly=true)
    public Api.Envelope<?> bulkPreview(@AuthenticationPrincipal Api.User actor,@RequestBody com.fasterxml.jackson.databind.JsonNode body,HttpServletRequest req) {
        if(!Store.operator(actor))throw Api.forbidden();
        var input=new Business.Input(body,"action","filters");String action=input.choice("action","approve","reject","issue","return-confirm");
        var raw=body.get("filters");
        if(raw==null)raw=json.createObjectNode();
        var fields=new Business.Input(raw,"q","keyword","equipmentId","status","statuses","from","to","borrowerId");
        var f=filter(actor,false,fields.has("status")?fields.text("status",32,true):null,
            fields.has("statuses")?fields.text("statuses",200,true):null,fields.text("equipmentId",36,false),
            fields.text("q",100,false),fields.text("keyword",100,false),fields.has("from")?fields.date("from"):null,
            fields.has("to")?fields.date("to"):null,fields.text("borrowerId",36,false));
        long total=s.db.queryForObject("SELECT COUNT(*)"+LOAN_FROM+f.where(),Long.class,f.args().toArray());
        if(total>5000)throw Api.invalid("筛选结果超过5000条，请缩小条件后再批量处理；本次未截取或处理任何记录");
        var ids=s.db.queryForList("SELECT br.id"+LOAN_FROM+f.where()+" ORDER BY "+LOAN_ORDER,String.class,f.args().toArray());
        var items=new ArrayList<Map<String,Object>>();
        for(String id:ids){var loan=s.borrow(id,actor,false);if(!loan.allowedActions().contains(action))continue;
            items.add(Business.map("id",loan.id(),"version",loan.version(),"action",action,"requiresAsset",loan.requiresAsset(),
                "needsAsset",loan.assetNumber()!=null&&"return-confirm".equals(action),"assetNumber",loan.assetNumber(),
                "equipmentId",loan.equipmentId(),"equipmentName",loan.equipmentName(),"user",loan.user(),"userAccount",loan.userAccount(),
                "status",loan.status(),"allowedActions",loan.allowedActions()));}
        return Api.ok(Business.map("action",action,"total",total,"eligible",items.size(),"skipped",total-items.size(),"items",items),req);
    }
    @GetMapping("/{id}")
    public Api.Envelope<Api.Borrow> detail(@AuthenticationPrincipal Api.User actor,@PathVariable String id,
        @RequestParam(defaultValue="false") boolean pickupProxy,HttpServletRequest req) {
        return Api.ok(s.borrow(id,actor,false,pickupProxy),req);
    }
    @PostMapping @Transactional
    public Api.Envelope<Api.Borrow> create(@AuthenticationPrincipal Api.User actor,@Valid @RequestBody Api.BorrowCreate input,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest req) {
        if(input.pickupProxyId()==null&&(input.phone()==null||input.phone().isBlank()))
            throw Api.invalid("请填写联系电话");
        return Api.ok(once(actor,key,"POST /api/borrow-requests",input,()->{
            Store.role(actor,"student");var e=s.equipment(input.equipmentId(),true);limits.requireCapacity(actor.id());
            if(!e.active())throw Api.conflict("器材已下架");
            long active=s.db.queryForObject("SELECT COUNT(*) FROM borrow_request WHERE user_id=? AND equipment_id=? AND active_slot=1",Long.class,actor.id(),e.id());
            if(active>0)throw Api.conflict("同一种器材已有进行中的借用");
            boolean negotiated="agreed".equals(input.pickupMode());
            if(negotiated){
                if(input.agreedPickupAt()!=null&&input.agreedPickupDate()!=null)throw Api.invalid("新日期与旧时间字段不能混用");
                if(input.agreedPickupDate()!=null&&(input.agreedPickupDate().getYear()<1000||input.agreedPickupDate().getYear()>9999))throw Api.invalid("约定日期超出数据库支持范围");
                if(input.slotId()!=null||input.pickupProxyId()!=null)throw Api.invalid("协商领取不接受值班时间或代领人，请清除其他领取方式");
            }else if(input.agreedPickupAt()!=null||input.agreedPickupDate()!=null)throw Api.invalid("仅协商领取可填写约定日期");
            boolean approvalRequired=Inventory.approvalRequired(e.id());
            Api.User proxy=null;
            if(input.pickupProxyId()!=null) {
                if(approvalRequired)throw Api.invalid("此器材需本人领取");
                if(input.slotId()!=null)throw Api.invalid("代领时间须由代领同学选择");
                proxy=s.db.query("SELECT * FROM lab_user WHERE "+Store.PICKUP_CANDIDATE+" AND id=?",Store.USER,
                    actor.id(),input.pickupProxyId()).stream().findFirst()
                    .orElseThrow(()->Api.invalid("请选择一名已启用的其他账号代领"));
            } else if(!negotiated) {
                pickupDuty(input.slotId());
            }
            String phone=input.phone();
            if(phone==null)phone=proxy==null?"":proxy.phone();
            phone=Objects.toString(phone,"").trim();
            String status=approvalRequired?"pending":"approved",id=Store.id();
            if(!approvalRequired)reserve(e);
            s.db.update("INSERT INTO borrow_request(id,user_id,equipment_id,quantity,status,active_slot,user_name,class_name,phone,purpose,slot_id,requires_approval,requires_asset,created_at,approved_at,pickup_proxy_id,pickup_proxy_name,pickup_proxy_account) VALUES(?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?,?)",
                id,actor.id(),e.id(),1,status,actor.name(),actor.className(),phone,Objects.toString(input.purpose(),"").trim(),input.slotId(),approvalRequired,e.requiresAsset(),s.now(),approvalRequired?null:s.now(),
                proxy==null?null:proxy.id(),proxy==null?null:proxy.name(),proxy==null?null:proxy.account());
            s.db.update("UPDATE borrow_request SET pickup_mode=?,agreed_pickup_at=?,agreed_pickup_date=? WHERE id=?",negotiated?"agreed":"duty",
                input.agreedPickupAt()==null?null:Timestamp.from(input.agreedPickupAt().toInstant()),input.agreedPickupDate(),id);
            if(!approvalRequired)s.db.update("UPDATE borrow_request SET pickup_deadline_at=? WHERE id=?",Timestamp.from(PickupPolicy.deadline(s.clock.instant().atOffset(ZoneOffset.UTC)).toInstant()),id);
            s.event(id,"create",null,status,actor.id(),null);
            if(proxy!=null)notifyProxy(proxy.id(),id,e.id(),"有用户请你代领器材",actor.name()+"请你代领"+e.name()+"。请打开个人代领记录选择值班时间。","requested");
            return s.borrow(id,actor,false);
        }),req);
    }
    @PostMapping("/{id}/{action}") @Transactional
    public Api.Envelope<Api.Borrow> action(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@PathVariable String action,
        @Valid @RequestBody Api.Action input,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req) {
        return Api.ok(once(actor,key,"POST /api/borrow-requests/"+id+"/"+action,input,()->perform(actor,id,action,input)),req);
    }
    private Api.Borrow perform(Api.User actor,String id,String action,Api.Action input) {
        if(input.reason()!=null&&!Set.of("reject","return-reject","extend-pickup").contains(action))throw Api.invalid("此操作不接受 reason 字段");
        boolean directReturn = action.equals("return-confirm-direct");
        if (directReturn && !Store.operator(actor)) throw Api.forbidden();
        if(input.assetNumber()!=null&&!Set.of("issue","return-confirm","return-confirm-direct").contains(action))throw Api.invalid("此操作不接受 assetNumber 字段");
        if(!action.equals("issue")&&(input.identityChecked()!=null||input.quantity()!=null))throw Api.invalid("仅发放时接受身份核验及数量字段");
        if(!Set.of("return-confirm","return-confirm-direct").contains(action)&&input.checked()!=null)throw Api.invalid("仅完好验收时接受checked字段");
        if(!action.equals("schedule-pickup")&&input.slotId()!=null)throw Api.invalid("仅代领排期时接受slotId字段");
        if(!action.equals("extend-pickup")&&input.pickupDeadlineAt()!=null)throw Api.invalid("仅延期领取时接受pickupDeadlineAt字段");
        // Always lock equipment before borrow; concurrent operations on the same stock serialize.
        boolean proxyAction="schedule-pickup".equals(action);
        var initial=s.borrow(id,actor,false,proxyAction);var equipment=s.equipment(initial.equipmentId(),true);var b=s.borrow(id,actor,true,proxyAction);
        if(actor.role().equals("student")&&!actor.id().equals(b.userId())&&!proxyAction)
            throw Api.forbidden();
        if(b.version()!=input.version())throw Api.conflict("记录已更新，请刷新后重试");
        if(Inventory.OTHER_EQUIPMENT_CATEGORY.equals(equipment.category())&&actor.id().equals(b.userId())&&
            Set.of("approve","reject","issue","return-confirm","return-confirm-direct","return-reject").contains(action))throw Api.forbidden();
        String target;
        switch(action) {
            case "approve" -> {
                Store.role(actor,"reviewer");state(b,"pending");
                if(!equipment.active())throw Api.conflict("器材已下架");
                reserve(equipment);target="approved";
                s.db.update("UPDATE borrow_request SET approved_at=?,pickup_deadline_at=? WHERE id=?",s.now(),Timestamp.from(PickupPolicy.deadline(s.clock.instant().atOffset(ZoneOffset.UTC)).toInstant()),id);
            }
            case "reject" -> {Store.role(actor,"reviewer");state(b,"pending");reason(input);target="rejected";}
            case "cancel" -> {
                owner(actor,b);state(b,"pending","approved");target="cancelled";
                if(b.status().equals("approved"))s.db.update("UPDATE equipment SET reserved=reserved-1,version=version+1 WHERE id=?",equipment.id());
                if(b.pickupProxyId()!=null)notifyProxy(b.pickupProxyId(),id,equipment.id(),"代领申请已取消",b.user()+"已取消"+equipment.name()+"的借用申请。","cancelled");
            }
            case "extend-pickup" -> {
                Store.role(actor,"reviewer");state(b,"approved");reason(input);
                var deadline=input.pickupDeadlineAt();
                if(deadline==null||!deadline.toInstant().isAfter(s.clock.instant())||b.pickupDeadlineAt()!=null&&!deadline.toInstant().isAfter(b.pickupDeadlineAt().toInstant()))throw Api.invalid("延期截止须晚于当前时间和已有截止时间");
                s.db.update("UPDATE borrow_request SET pickup_deadline_at=? WHERE id=?",Timestamp.from(deadline.toInstant()),id);target="approved";
            }
            case "schedule-pickup" -> {
                if(!actor.id().equals(b.pickupProxyId()))throw Api.forbidden();
                state(b,"approved");
                pickupDuty(input.slotId());
                s.db.update("UPDATE borrow_request SET slot_id=? WHERE id=?",input.slotId(),id);
                target=b.status();
            }
            case "issue" -> {
                Store.role(actor,"reviewer");state(b,"approved");limits.requireCapacity(b.userId());
                if(b.pickupDeadlineAt()!=null&&b.pickupDeadlineAt().toInstant().isBefore(s.clock.instant()))throw Api.conflict("已过领取期限，请联系管理人员延期或重新申请");
                if(b.pickupProxyId()!=null&&b.slotId()==null)throw Api.conflict("请等待代领同学选择值班时间");
                if(!Boolean.TRUE.equals(input.identityChecked())||!Objects.equals(input.quantity(),1))throw Api.invalid("发放前须确认现场身份及数量1件");
                String asset=AssetLedger.number(input.assetNumber());
                assets.claim(asset,equipment.id(),id,null);
                target="borrowed";
                s.db.update("UPDATE equipment SET reserved=reserved-1,borrowed=borrowed+1,version=version+1 WHERE id=?",equipment.id());
                s.db.update("UPDATE borrow_request SET asset_number=?,issued_at=?,due_date=? WHERE id=?",asset,s.now(),LoanPolicy.initialDue(s.today()),id);
            }
            case "return-request" -> {owner(actor,b);state(b,"borrowed");target="returning";}
            case "return-reject" -> {Store.role(actor,"reviewer");state(b,"returning");reason(input);target="borrowed";}
            case "return-confirm", "return-confirm-direct" -> {
                Store.role(actor,"reviewer");state(b,directReturn ? "borrowed" : "returning");
                if(!Boolean.TRUE.equals(input.checked()))throw Api.invalid("完好归还前须确认实物和配件验收");
                String supplied = AssetLedger.number(input.assetNumber());
                if (directReturn) assets.releaseDirect(b.assetNumber(), supplied, equipment.id(), id, null);
                else assets.release(b.assetNumber(), supplied, id, null);
                target="returned";
                s.db.update("UPDATE equipment SET borrowed=borrowed-1,version=version+1 WHERE id=?",equipment.id());
                s.db.update("UPDATE borrow_request SET returned_at=? WHERE id=?",s.now(),id);
            }
            case "renew" -> {
                owner(actor,b);state(b,"borrowed");
                if(b.renewCount()>=LoanPolicy.MAX_RENEWALS)throw new Api.Failure(409,"RENEW_LIMIT","最多可续借两次");
                if(b.dueDate()==null)throw Api.conflict("借用到期日期缺失，请联系老师");
                if(s.today().isAfter(b.dueDate()))throw new Api.Failure(409,"OVERDUE","已逾期，请联系老师处理");
                target="borrowed";
                s.db.update("UPDATE borrow_request SET due_date=?,renew_count=renew_count+1 WHERE id=?",LoanPolicy.renewedDue(b.dueDate(),b.renewCount()),id);
            }
            default -> throw Api.missing();
        }
        Integer active=Set.of("pending","approved","borrowed","returning").contains(target)?1:null;
        s.db.update("UPDATE borrow_request SET status=?,active_slot=?,version=version+1 WHERE id=?",target,active,id);
        s.event(id,action,b.status(),target,actor.id(),input.reason()==null?null:input.reason().trim());
        return s.borrow(id,actor,false,proxyAction);
    }
    private void pickupDuty(String slotId) {
        if(slotId==null||slotId.isBlank())throw Api.invalid("请选择有效的领取值班时间");
        var duties=s.db.query("SELECT * FROM duty_slot WHERE id=? AND enabled=TRUE FOR UPDATE",Store.DUTY,slotId);
        if(duties.isEmpty()||!duties.getFirst().date().atTime(duties.getFirst().endTime()).isAfter(LocalDateTime.now(s.clock.withZone(ZoneId.of("Asia/Shanghai")))))
            throw Api.invalid("请选择有效的领取值班时间");
    }
    private void notifyProxy(String userId,String borrowId,String equipmentId,String title,String body,String event) {
        s.db.update("INSERT INTO user_notification(id,user_id,title,body,dedupe_key,request_id,equipment_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
            Store.id(),userId,title,body,"pickup-proxy:"+borrowId+":"+event,borrowId,equipmentId,s.now());
    }
    private void reserve(Api.Equipment e) {
        if(e.available()<1)throw new Api.Failure(409,"STOCK_INSUFFICIENT","当前可借库存不足");
        s.db.update("UPDATE equipment SET reserved=reserved+1,version=version+1 WHERE id=?",e.id());
    }
    private void state(Api.Borrow b,String... allowed) {if(!List.of(allowed).contains(b.status()))throw Api.conflict("当前状态不允许此操作");}
    private void owner(Api.User u,Api.Borrow b) {Store.role(u,"student");if(!u.id().equals(b.userId()))throw Api.forbidden();}
    private void reason(Api.Action a) {if(a.reason()==null||a.reason().isBlank())throw Api.invalid("请填写处理原因");}
    private Api.Borrow once(Api.User actor,String key,String scope,Object input,Supplier<Api.Borrow> operation) {
        if(!key.matches("[A-Za-z0-9_-]{8,100}"))throw Api.invalid("Idempotency-Key 须为8至100位字母数字、下划线或连字符");
        var userLocks=new ArrayList<String>();userLocks.add(actor.id());
        if(scope.startsWith("POST /api/borrow-requests/")) {
            String loanId=scope.substring("POST /api/borrow-requests/".length(),scope.lastIndexOf('/'));
            var rows=s.db.queryForList("SELECT user_id,pickup_proxy_id FROM borrow_request WHERE id=?",loanId);
            if(rows.isEmpty())throw Api.missing();var row=rows.getFirst();
            userLocks.add(row.get("user_id").toString());if(row.get("pickup_proxy_id")!=null)userLocks.add(row.get("pickup_proxy_id").toString());
        } else if(input instanceof Api.BorrowCreate creation && creation.pickupProxyId()!=null &&
            !s.db.queryForList("SELECT id FROM lab_user WHERE id=?",String.class,creation.pickupProxyId()).isEmpty())userLocks.add(creation.pickupProxyId());
        LoanLimits.lockUsers(s,userLocks.toArray(String[]::new));
        Api.User current=s.lockUser(actor.id());
        if(!current.role().equals(actor.role()) && !(current.canManage() && actor.role().equals("reviewer")))throw Api.forbidden();
        String hash=Security.digest(encode(input));
        var rows=s.db.queryForList("SELECT * FROM idempotency_record WHERE user_id=? AND key_value=?",actor.id(),key);
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            if(((Timestamp)row.get("expires_at")).toInstant().isAfter(s.clock.instant())) {
                if(!scope.equals(row.get("scope"))||!hash.equals(row.get("request_hash")))throw new Api.Failure(409,"IDEMPOTENCY_CONFLICT","此幂等键已用于不同请求");
                try {
                    var response=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree((String)row.get("response_json"));
                    if(!Store.operator(actor)&&response.hasNonNull("pickupProxyId"))response.put("phone","");
                    return json.treeToValue(response,Api.Borrow.class);
                } catch(JsonProcessingException e) {throw new IllegalStateException(e);}
            }
            s.db.update("DELETE FROM idempotency_record WHERE user_id=? AND key_value=?",actor.id(),key);
        }
        var result=operation.get();
        s.db.update("INSERT INTO idempotency_record(user_id,key_value,scope,request_hash,response_json,expires_at) VALUES(?,?,?,?,?,?)",actor.id(),key,scope,hash,encode(result),Timestamp.from(s.clock.instant().plusSeconds(86400)));
        return result;
    }
    private String encode(Object o) {try{return json.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
}
