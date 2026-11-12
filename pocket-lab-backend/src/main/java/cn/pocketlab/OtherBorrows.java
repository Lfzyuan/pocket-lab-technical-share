package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController
@RequestMapping("/api/other-borrow-requests")
public class OtherBorrows {
    private static final Set<String> STATES = Set.of(
        "pending", "approved", "borrowed", "returning", "returned", "rejected", "cancelled", "registered", "confirmed");
    private static final Map<String, List<String>> ACTION_FIELDS = Map.of(
        "confirm", List.of("version"),
        "approve", List.of("version", "physicalAvailable"),
        "reject", List.of("version", "reason"),
        "issue", List.of("version", "checked"),
        "cancel", List.of("version"),
        "return-request", List.of("version"),
        "return-confirm", List.of("version", "checked"),
        "return-confirm-direct", List.of("version", "checked", "assetNumber"),
        "return-reject", List.of("version", "reason"));
    private static final Map<String, String> STATUS_NAMES = Map.of(
        "pending", "申请中", "approved", "已批准", "borrowed", "已发放",
        "returning", "待归还", "returned", "已归还", "rejected", "未通过", "cancelled", "已取消",
        "registered", "待确认", "confirmed", "已确认");
    private static final String COLUMNS = "id,user_id,user_name,user_account,class_name,phone,name,model,"
        + "function_description,purpose,quantity,status,reason,created_at,reviewed_by_name,reviewed_at,"
        + "issued_at,return_requested_at,returned_at,version,workflow_mode,confirmed_by,confirmed_by_name,confirmed_at,issued_date,returned_date,"
        + "teacher_user_id,teacher_name,expected_return_month,asset_number";
    private final Store store;
    private final Business business;
    private final LoanLimits limits;
    private final AssetLedger assets;
    private final TeacherDirectory teachers;

    OtherBorrows(Store store, Business business, LoanLimits limits, AssetLedger assets, TeacherDirectory teachers) {
        this.store = store;
        this.business = business;
        this.limits = limits;
        this.assets = assets;
        this.teachers = teachers;
    }

    @GetMapping
    public Api.Envelope<?> list(@AuthenticationPrincipal Api.User actor,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(required = false) Integer pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "false") boolean mine,
        @RequestParam(defaultValue = "") String keyword,
        @RequestParam(required = false) String workflowMode,
        @RequestParam(required = false) LocalDate from, @RequestParam(required = false) LocalDate to,
        @RequestParam(required = false) String borrowerId, HttpServletRequest request) {
        int size = pageSize == null ? (actor.role().equals("student") ? 5 : 10) : pageSize;
        if (keyword.length() > 100) throw Api.invalid("搜索条件过长");
        var args = new ArrayList<Object>();
        String where = " WHERE 1=1";
        if (!Store.operator(actor) || mine) {
            where += " AND user_id=?";
            args.add(actor.id());
        }
        if (status != null) {
            if (!STATES.contains(status)) throw Api.invalid("未知其他借用状态");
            where += " AND status=?";
            args.add(status);
        }
        if (!keyword.isBlank()) {
            String match = like(keyword.trim());
            where += " AND (name LIKE ? ESCAPE '!' OR model LIKE ? ESCAPE '!'"
                + " OR user_name LIKE ? ESCAPE '!' OR user_account LIKE ? ESCAPE '!')";
            args.addAll(List.of(match, match, match, match));
        }
        if (workflowMode != null) {
            if (!Set.of("legacy", "registration", "checkout").contains(workflowMode)) throw Api.invalid("登记模式无效");
            where += " AND workflow_mode=?";
            args.add(workflowMode);
        }
        if (borrowerId != null && !borrowerId.isBlank()) {
            if (borrowerId.length() > 36) throw Api.invalid("借用人ID过长");
            if (!Store.operator(actor) && !actor.id().equals(borrowerId)) throw Api.forbidden();
            where += " AND user_id=?";
            args.add(borrowerId);
        }
        var dates = new Filters();
        dates.dates("created_at", from, to, ZoneId.of("Asia/Shanghai"));
        where += dates.where.replace(" WHERE 1=1", "");
        args.addAll(dates.args);
        var result = business.page("SELECT " + COLUMNS, "FROM other_borrow_request", where,
            args, "created_at DESC,id DESC", page, size);
        return Api.ok(new Api.Page<>(result.items().stream().map(row -> decorate(row, actor)).toList(),
            result.total(), page, size), request);
    }

    @GetMapping("/{id}")
    public Api.Envelope<?> detail(@AuthenticationPrincipal Api.User actor,
        @PathVariable String id, HttpServletRequest request) {
        return Api.ok(decorate(find(id, actor, false), actor), request);
    }

    @PostMapping
    @Transactional
    public Api.Envelope<?> create(@AuthenticationPrincipal Api.User actor,
        @RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key,
        HttpServletRequest request) {
        return Api.ok(business.write(actor, key, "POST /other-borrow-requests", body, () -> {
            Store.role(actor, "student");
            if(body.has("workflowMode")) {
                if(!"checkout".equals(body.path("workflowMode").asText()))throw Api.invalid("借用流程无效");
                return createCheckout(actor,body);
            }
            var input = new Input(body, "name", "model", "functionDescription", "purpose", "issuedAt", "returnedAt", "issuedDate", "returnedDate");
            boolean dates=input.has("issuedDate")||input.has("returnedDate");
            OffsetDateTime issued=null,returned=null;LocalDate issuedDate=null,returnedDate=null;
            if(dates){
                if(input.has("issuedAt")||input.has("returnedAt"))throw Api.invalid("日期与旧时间字段不能混用");
                issuedDate=input.date("issuedDate");returnedDate=input.date("returnedDate");
                if(issuedDate.getYear()<1000||returnedDate.getYear()>9999)throw Api.invalid("日期超出数据库支持范围");
                if(returnedDate.isBefore(issuedDate))throw Api.invalid("实际归还日期不能早于实际借出日期");
                if(returnedDate.isAfter(store.today()))throw Api.invalid("请在实际借还完成后登记，归还日期不能晚于上海今天");
            }else{
                issued=actualTime(input,"issuedAt");returned=actualTime(input,"returnedAt");
                if(returned.toInstant().isBefore(issued.toInstant()))throw Api.invalid("实际归还时间不能早于实际借出时间");
                if(returned.toInstant().isAfter(store.clock.instant()))throw Api.invalid("请在实际借还完成后登记，归还时间不能晚于当前时间");
            }
            String id = Store.id();
            store.db.update("INSERT INTO other_borrow_request(id,user_id,user_name,user_account,class_name,phone,"
                    + "name,model,function_description,purpose,quantity,status,created_at,workflow_mode,issued_at,returned_at,issued_date,returned_date)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,1,'registered',?,'registration',?,?,?,?)",
                id, actor.id(), actor.name(), actor.account(), actor.className(), actor.phone(),
                input.text("name", 160, true), input.text("model", 160, false),
                input.text("functionDescription", 2000, false), input.text("purpose", 500, false), store.now(),
                issued==null?null:Timestamp.from(issued.toInstant()),returned==null?null:Timestamp.from(returned.toInstant()),issuedDate,returnedDate);
            recordEvent(id, "register", null, "registered", actor, null);
            return decorate(find(id, actor, false), actor);
        }), request);
    }

    private Map<String,Object> createCheckout(Api.User actor,JsonNode body) {
        var input=new Input(body,"workflowMode","name","teacherUserId","expectedReturnMonth","purpose");
        TeacherDirectory.Teacher teacher=teachers.require(input.text("teacherUserId",36,true));
        limits.requireCapacity(actor.id());
        String month=input.text("expectedReturnMonth",7,false);
        if(!month.isEmpty()&&!month.matches("[1-9][0-9]{3}-(0[1-9]|1[0-2])"))throw Api.invalid("预计归还月份须为YYYY-MM");
        String id=Store.id();var now=store.now();
        store.db.update("INSERT INTO other_borrow_request(id,user_id,user_name,user_account,class_name,phone,name,purpose,quantity,status,created_at,issued_at,workflow_mode,teacher_user_id,teacher_name,expected_return_month)"
            + " VALUES(?,?,?,?,?,?,?,?,1,'borrowed',?,?,'checkout',?,?,?)",id,actor.id(),actor.name(),actor.account(),actor.className(),actor.phone(),
            input.text("name",160,true),input.text("purpose",500,false),now,now,teacher.id(),teacher.name(),month.isEmpty()?null:month);
        recordEvent(id,"create",null,"borrowed",actor,null);
        notifyTeacher(find(id,actor,false));
        return decorate(find(id,actor,false),actor);
    }

    @PostMapping("/{id}/{action}")
    @Transactional
    public Api.Envelope<?> action(@AuthenticationPrincipal Api.User actor,
        @PathVariable String id, @PathVariable String action, @RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        if (action.equals("issue")) {var row=find(id,actor,false);LoanLimits.lockUsers(store,actor.id(),row.get("userId").toString());}
        return Api.ok(business.write(actor, key, "POST /other-borrow-requests/" + id + "/" + action,
            body, () -> perform(actor, id, action, body)), request);
    }

    private Object perform(Api.User actor, String id, String action, JsonNode body) {
        var fields = ACTION_FIELDS.get(action);
        if (fields == null) throw Api.missing();
        boolean directReturn=action.equals("return-confirm-direct");
        if(directReturn&&!Store.operator(actor))throw Api.forbidden();
        var row = find(id, actor, true);
        boolean checkout="checkout".equals(row.get("workflowMode"));
        if(checkout&&"issue".equals(action))fields=List.of("version","checked","assetNumber","remark");
        if(checkout&&"return-confirm".equals(action))fields=List.of("version","checked","assetNumber");
        var input = new Input(body, fields.toArray(String[]::new));
        business.version(row, input);
        if ("registration".equals(row.get("workflowMode"))) {
            if (!action.equals("confirm")) throw Api.conflict("补登记记录只需由管理端确认，无须审批或再次借还");
            if (!Store.operator(actor)) throw Api.forbidden();
            state(row, "registered");
            store.db.update("UPDATE other_borrow_request SET status='confirmed',confirmed_by=?,confirmed_by_name=?,"
                + "confirmed_at=?,version=version+1 WHERE id=?", actor.id(), actor.name(), store.now(), id);
            recordEvent(id, "confirm", "registered", "confirmed", actor, null);
            return decorate(find(id, actor, false), actor);
        }
        if (action.equals("confirm")) throw Api.conflict("历史申请须按原借还流程办理");
        String target;
        String reason = null;
        switch (action) {
            case "approve" -> {
                staff(actor, row);
                state(row, "pending");
                if (!input.bool("physicalAvailable")) throw Api.invalid("请确认实际有可借设备");
                reviewed(id, actor, null);
                target = "approved";
            }
            case "reject" -> {
                staff(actor, row);
                state(row, "pending");
                reason = input.text("reason", 500, true);
                reviewed(id, actor, reason);
                target = "rejected";
            }
            case "issue" -> {
                staff(actor, row);
                state(row, "approved");
                limits.requireCapacity(row.get("userId").toString());
                if (!input.bool("checked")) throw Api.invalid("请确认已现场核对身份、实物及配件并发放1件");
                String asset=null;
                if(checkout) {
                    asset=AssetLedger.number(input.text("assetNumber",100,false));
                    String remark=input.text("remark",500,false);
                    if(!remark.isEmpty())reason=remark;
                    assets.claim(asset,null,null,id);
                }
                store.db.update("UPDATE other_borrow_request SET issued_at=?,asset_number=? WHERE id=?",store.now(),asset,id);
                target = "borrowed";
            }
            case "cancel" -> {
                owner(actor, row);
                state(row, "pending", "approved");
                target = "cancelled";
            }
            case "return-request" -> {
                owner(actor, row);
                state(row, "borrowed");
                store.db.update("UPDATE other_borrow_request SET return_requested_at=? WHERE id=?", store.now(), id);
                target = "returning";
            }
            case "return-confirm", "return-confirm-direct" -> {
                staff(actor, row);
                state(row, directReturn ? "borrowed" : "returning");
                if (!input.bool("checked")) throw Api.invalid("请确认实物及配件已验收归还");
                if(directReturn&&!checkout)throw Api.conflict("历史申请不支持资产编号现场归还");
                if(checkout) {
                    String asset=(String)row.get("assetNumber");
                    String supplied=AssetLedger.number(input.text("assetNumber",100,false));
                    if(directReturn)assets.releaseDirect(asset,supplied,null,null,id);
                    else assets.release(asset,supplied,null,id);
                }
                store.db.update("UPDATE other_borrow_request SET returned_at=? WHERE id=?", store.now(), id);
                target = "returned";
            }
            case "return-reject" -> {
                staff(actor, row);
                state(row, "returning");
                reason = input.text("reason", 500, true);
                target = "borrowed";
            }
            default -> throw Api.missing();
        }
        store.db.update("UPDATE other_borrow_request SET status=?,version=version+1 WHERE id=?", target, id);
        recordEvent(id, action, row.get("status").toString(), target, actor, reason);
        if(checkout&&"issue".equals(action)) notifyTeacher(row);
        return decorate(find(id, actor, false), actor);
    }

    private void notifyTeacher(Map<String,Object> row) {
        if(row.get("teacherUserId") instanceof String teacherId) business.notify(teacherId,"指导学生已领取其他器材",
            row.get("userName")+"已领取"+row.get("name")+"。","other-checkout-issued:"+row.get("id"),null,null);
    }

    Map<String,Object> record(String id,Api.User actor) { return decorate(find(id,actor,false),actor); }

    private Map<String, Object> find(String id, Api.User actor, boolean lock) {
        var row = business.one("SELECT " + COLUMNS + " FROM other_borrow_request WHERE id=?"
            + (lock ? " FOR UPDATE" : ""), id);
        if (!Store.operator(actor) && !actor.id().equals(row.get("userId"))) throw Api.missing();
        return row;
    }

    private Map<String, Object> decorate(Map<String, Object> row, Api.User actor) {
        var result = new LinkedHashMap<>(row);
        result.put("user", result.remove("userName"));
        if(result.get("issuedDate")==null&&result.get("issuedAt") instanceof OffsetDateTime time)result.put("issuedDate",time.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate());
        if(result.get("returnedDate")==null&&result.get("returnedAt") instanceof OffsetDateTime time)result.put("returnedDate",time.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate());
        var actions = new ArrayList<String>();
        boolean own = actor.id().equals(row.get("userId"));
        String status = row.get("status").toString();
        if ("registration".equals(row.get("workflowMode"))) {
            if (Store.operator(actor) && status.equals("registered")) actions.add("confirm");
        } else {
            if (own) {
                if (Set.of("pending", "approved").contains(status)) actions.add("cancel");
                if (status.equals("borrowed")) actions.add("return-request");
            }
            if (Store.operator(actor) && !own) {
                switch (status) {
                    case "pending" -> actions.addAll(List.of("approve", "reject"));
                    case "approved" -> actions.add("issue");
                    case "returning" -> actions.addAll(List.of("return-confirm", "return-reject"));
                    default -> { }
                }
            }
        }
        result.put("allowedActions", actions);
        var events=business.rows("SELECT id,action,from_status,to_status,actor_id,actor_name,reason,"
            + "created_at AS at FROM other_borrow_event WHERE request_id=? ORDER BY created_at,id", row.get("id"));
        result.put("events",events);
        if("checkout".equals(row.get("workflowMode"))) {
            result.put("issueRemark",events.stream().filter(event->"issue".equals(event.get("action"))).findFirst().map(event->event.get("reason")).orElse(null));
        } else {
            result.keySet().removeAll(Set.of("teacherUserId","teacherName","expectedReturnMonth","assetNumber"));
        }
        return result;
    }

    // Account deletion holds the extension write gate before locking the selected users and these requests.
    void cancelUnissuedForUser(Api.User actor, String userId) {
        Store.role(actor, "admin");
        var rows = business.rows("SELECT id,status FROM other_borrow_request WHERE user_id=?"
            + " AND status IN ('pending','approved') FOR UPDATE", userId);
        for (var row : rows) {
            String id = row.get("id").toString();
            store.db.update("UPDATE other_borrow_request SET status='cancelled',version=version+1 WHERE id=?", id);
            recordEvent(id, "cancel", row.get("status").toString(), "cancelled", actor, "账号删除，取消未领取申请");
        }
    }

    private OffsetDateTime actualTime(Input input, String field) {
        try { return OffsetDateTime.parse(input.text(field, 100, true)); }
        catch (DateTimeParseException error) { throw Api.invalid("请填写带时区的有效" + field + "时间"); }
    }

    private void owner(Api.User actor, Map<String, Object> row) {
        if (!actor.id().equals(row.get("userId"))) throw Api.forbidden();
    }

    private void staff(Api.User actor, Map<String, Object> row) {
        if (!Store.operator(actor) || actor.id().equals(row.get("userId"))) throw Api.forbidden();
    }

    private void reviewed(String id, Api.User actor, String reason) {
        store.db.update("UPDATE other_borrow_request SET reviewed_by=?,reviewed_by_name=?,reviewed_at=?,reason=? WHERE id=?",
            actor.id(), actor.name(), store.now(), reason, id);
    }

    private void recordEvent(String id, String action, String from, String to, Api.User actor, String reason) {
        String eventId = Store.id();
        store.db.update("INSERT INTO other_borrow_event(id,request_id,action,from_status,to_status,actor_id,actor_name,reason,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)", eventId, id, action, from, to, actor.id(), actor.name(), reason, store.now());
        var row = business.one("SELECT user_id,name,workflow_mode FROM other_borrow_request WHERE id=?", id);
        String statusName="checkout".equals(row.get("workflowMode"))&&"approved".equals(to)?"待发放":STATUS_NAMES.get(to);
        String body = row.get("name") + "的其他借用申请" + statusName
            + (reason == null ? "，请在其他借用中查看办理记录。" : "，说明：" + reason);
        business.notify(row.get("userId").toString(), "其他借用" + statusName, body,
            "other-borrow-event:" + eventId, null, null);
    }
}
