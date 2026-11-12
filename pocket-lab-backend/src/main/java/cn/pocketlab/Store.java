package cn.pocketlab;

import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
    final JdbcTemplate db;
    final Clock clock;
    Store(JdbcTemplate db, Clock clock) { this.db=db; this.clock=clock; }
    Timestamp now() { return Timestamp.from(clock.instant()); }
    LocalDate today() { return LocalDate.now(clock.withZone(ZoneId.of("Asia/Shanghai"))); }
    static String id() { return UUID.randomUUID().toString(); }
    static final org.springframework.jdbc.core.RowMapper<Api.User> USER = (r,n) -> new Api.User(r.getString("id"),r.getString("account"),r.getString("name"),r.getString("class_name"),r.getString("phone"),r.getString("email"),r.getString("role"),r.getBoolean("enabled"),r.getBoolean("can_manage"),r.getString("role"),r.getString("grade"),r.getInt("borrow_limit"),AccountPolicy.identity(r.getString("role"),r.getString("account_type")),r.getObject("enrollment_year",Integer.class));
    Api.User user(String id) { return db.query("SELECT * FROM lab_user WHERE id=?",USER,id).stream().findFirst().orElseThrow(Api::unauthorized); }
    Api.User lockUser(String id) { return db.query("SELECT * FROM lab_user WHERE id=? FOR UPDATE",USER,id).stream().filter(Api.User::enabled).findFirst().orElseThrow(Api::unauthorized); }
    static final String PICKUP_CANDIDATE="enabled=TRUE AND deleted_at IS NULL AND role<>'admin' AND id<>?";
    static final String PICKUP_CLASSMATE="enabled=TRUE AND role='student' AND account_type='student' AND id<>? AND pickup_class_name=?";
    String pickupClass(String id) {
        return Objects.requireNonNullElse(db.queryForObject(
            "SELECT CASE WHEN role='student' AND account_type='student' THEN pickup_class_name ELSE '' END FROM lab_user WHERE id=?",String.class,id),"");
    }
    static final String EQUIPMENT_SELECT="SELECT e.*,(SELECT COUNT(*) FROM borrow_request br WHERE br.equipment_id=e.id AND br.status='pending') AS pending,(SELECT COUNT(*) FROM favorite f WHERE f.equipment_id=e.id) AS favorite_count FROM equipment e";
    static final org.springframework.jdbc.core.RowMapper<Api.Equipment> EQUIPMENT = (r,n) -> new Api.Equipment(
        r.getString("id"),r.getString("name"),r.getString("brand"),r.getString("model"),r.getString("category"),r.getString("specification"),r.getString("description"),r.getString("accessories"),r.getString("location"),r.getString("image"),r.getBoolean("active"),r.getBoolean("requires_approval"),r.getBoolean("requires_asset"),r.getInt("stock"),r.getInt("stock")-r.getInt("reserved")-r.getInt("borrowed"),r.getInt("reserved"),r.getInt("borrowed"),r.getInt("pending"),r.getLong("favorite_count"),r.getInt("source_row")==0?null:r.getInt("source_total"),r.getInt("source_row")==0?null:r.getInt("source_available"),r.getInt("source_row")==0?null:r.getInt("source_row"),r.getLong("version"));
    Api.Equipment equipment(String id, boolean lock) {
        return db.query(EQUIPMENT_SELECT+" WHERE e.id=?"+(lock?" FOR UPDATE":""),EQUIPMENT,id).stream().findFirst().orElseThrow(Api::missing);
    }
    static final org.springframework.jdbc.core.RowMapper<Api.Duty> DUTY = (r,n)->new Api.Duty(r.getString("id"),r.getDate("slot_date").toLocalDate(),r.getTime("start_time").toLocalTime(),r.getTime("end_time").toLocalTime(),r.getString("location"),r.getBoolean("enabled"),r.getLong("version"));
    static OffsetDateTime time(ResultSet r, String field) throws SQLException {
        Timestamp t=r.getTimestamp(field); return t==null?null:t.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
    }
    static LocalDate calendarDate(ResultSet row,String dateColumn,String oldTimeColumn) throws SQLException {
        java.sql.Date date=row.getDate(dateColumn);if(date!=null)return date.toLocalDate();
        OffsetDateTime old=time(row,oldTimeColumn);return old==null?null:old.toLocalDate();
    }
    Api.Borrow borrow(String id, Api.User actor, boolean lock) {
        return borrow(id,actor,lock,false);
    }
    Api.Borrow borrow(String id, Api.User actor, boolean lock, boolean proxyView) {
        var rows=db.query("SELECT * FROM borrow_request WHERE id=?"+(lock?" FOR UPDATE":""),(r,n)->{
            java.sql.Date d=r.getDate("due_date");
            return new Api.Borrow(r.getString("id"),r.getString("equipment_id"),r.getString("user_id"),r.getString("user_name"),r.getString("class_name"),r.getString("phone"),r.getString("purpose"),r.getString("slot_id"),r.getInt("quantity"),r.getString("status"),r.getBoolean("requires_approval"),r.getBoolean("requires_asset"),r.getString("asset_number"),time(r,"created_at"),time(r,"approved_at"),time(r,"issued_at"),d==null?null:d.toLocalDate(),time(r,"returned_at"),time(r,"closed_at"),r.getInt("renew_count"),r.getLong("version"),List.of(),List.of(),
                r.getString("pickup_proxy_id"),r.getString("pickup_proxy_name"),r.getString("pickup_proxy_account"),null,
                r.getString("pickup_mode"),time(r,"agreed_pickup_at"),null,null,time(r,"pickup_deadline_at"),calendarDate(r,"agreed_pickup_date","agreed_pickup_at"));
        },id);
        if(rows.isEmpty()) throw Api.missing();
        var b=rows.getFirst();
        boolean student=actor.role().equals("student");
        boolean own=b.userId().equals(actor.id());
        boolean assignedProxy=actor.id().equals(b.pickupProxyId());
        if(proxyView&&!assignedProxy||student&&!own&&!assignedProxy)throw Api.missing();
        boolean pickupProxy=assignedProxy&&(proxyView||student);
        boolean hidePhone=pickupProxy||!operator(actor)&&b.pickupProxyId()!=null;
        List<String> actions=new ArrayList<>();
        if(pickupProxy&&b.status().equals("approved"))actions.add("schedule-pickup");
        if(actor.role().equals("student")&&b.userId().equals(actor.id())) {
            if(Set.of("pending","approved").contains(b.status())) actions.add("cancel");
            if(b.status().equals("borrowed")) {
                actions.add("return-request");
                if(LoanPolicy.canRenew(b.renewCount(),b.dueDate(),today())) actions.add("renew");
            }
        }
        boolean otherEquipmentOwnLoan=!pickupProxy&&operator(actor)&&own&&Inventory.OTHER_EQUIPMENT_CATEGORY.equals(
            db.queryForObject("SELECT category FROM equipment WHERE id=?",String.class,b.equipmentId()));
        if(!pickupProxy&&operator(actor)&&!otherEquipmentOwnLoan) switch(b.status()) {
            case "pending" -> actions.addAll(List.of("approve","reject"));
            case "approved" -> {if((b.pickupProxyId()==null||b.slotId()!=null)&&(b.pickupDeadlineAt()==null||!b.pickupDeadlineAt().toInstant().isBefore(clock.instant())))actions.add("issue");actions.add("extend-pickup");}
            case "returning" -> actions.addAll(List.of("return-confirm","return-reject"));
            default -> {}
        }
        List<Api.Event> events=db.query("SELECT * FROM borrow_event WHERE borrow_id=? ORDER BY occurred_at,id",(r,n)->new Api.Event(r.getString("id"),r.getString("action"),r.getString("from_status"),r.getString("to_status"),r.getString("actor_id"),r.getString("reason"),time(r,"occurred_at"),r.getString("actor_name"),r.getString("actor_type")),id);
        if(pickupProxy)events=events.stream().filter(event->Set.of("create","schedule-pickup","cancel","issue").contains(event.action()))
            .map(event->new Api.Event(event.id(),event.action(),event.fromStatus(),event.toStatus(),null,null,event.at(),null,event.actorType())).toList();
        Api.Duty pickupSlot=b.pickupProxyId()==null||b.slotId()==null?null:
            db.query("SELECT * FROM duty_slot WHERE id=?",DUTY,b.slotId()).stream().findFirst().orElse(null);
        String assetNumber=pickupProxy?null:b.assetNumber();
        if(!pickupProxy&&Set.of("borrowed","returning").contains(b.status())) {
            // Older collations could resolve a typed alias to an existing barcode. The
            // unique active checkout link identifies its canonical number without rewriting history.
            assetNumber=db.queryForList("SELECT asset_number FROM asset_unit WHERE borrow_id=? AND status='borrowed'"+(lock?" FOR UPDATE":""),String.class,b.id())
                .stream().findFirst().orElse(assetNumber);
        }
        return new Api.Borrow(b.id(),b.equipmentId(),pickupProxy?null:b.userId(),b.user(),pickupProxy?"":b.className(),hidePhone?"":b.phone(),pickupProxy?"":b.purpose(),b.slotId(),b.quantity(),b.status(),b.requiresApproval(),b.requiresAsset(),assetNumber,b.createdAt(),b.approvedAt(),b.issuedAt(),pickupProxy?null:b.dueDate(),pickupProxy?null:b.returnedAt(),pickupProxy?null:b.closedAt(),pickupProxy?0:b.renewCount(),b.version(),actions,events,
            b.pickupProxyId(),b.pickupProxyName(),b.pickupProxyAccount(),pickupSlot,b.pickupMode(),b.agreedPickupAt(),
            pickupProxy?null:db.queryForObject("SELECT account FROM lab_user WHERE id=?",String.class,b.userId()),
            db.queryForObject("SELECT name FROM equipment WHERE id=?",String.class,b.equipmentId()),b.pickupDeadlineAt(),b.agreedPickupDate());
    }
    void event(String borrowId,String action,String from,String to,String actor,String reason) {
        String eventId=id();
        String actorName=actor==null?"系统":db.queryForObject("SELECT name FROM lab_user WHERE id=?",String.class,actor);
        db.update("INSERT INTO borrow_event(id,borrow_id,action,from_status,to_status,actor_id,reason,occurred_at,actor_name,actor_type) VALUES(?,?,?,?,?,?,?,?,?,?)",eventId,borrowId,action,from,to,actor,reason,now(),actorName,actor==null?"system":"user");
        var rows=db.queryForList("SELECT user_id,equipment_id FROM borrow_request WHERE id=?",borrowId);if(!rows.isEmpty()){
            var row=rows.getFirst();String title=actor==null&&action.equals("cancel")?"预约逾期未领取已自动取消":switch(action){case "schedule-pickup"->"代领同学已选择领取时间";case "approve"->"借用申请已通过";case "reject"->"借用申请未通过";case "issue"->"器材已现场发放";case "return-request"->"归还申请已提交";case "return-confirm","return-confirm-direct"->"器材已完好归还";case "return-reject"->"归还验收被退回";case "renew"->"续借成功";case "incident"->"借用异常登记";default->"借用记录已更新";};
            db.update("INSERT INTO user_notification(id,user_id,title,body,dedupe_key,request_id,equipment_id,created_at) VALUES(?,?,?,?,?,?,?,?)",id(),row.get("user_id"),title,action.equals("return-request")?"请查看值班时间，并于申请归还后的一周内交回器材和配件，交回截止："+ReturnPolicy.format(clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime().plus(ReturnPolicy.WINDOW))+"。申请归还不延长原借期；须由老师现场验收完成归还。":reason==null?"请查看借用记录的最新状态和办理历史。":reason,"event:"+eventId,borrowId,row.get("equipment_id"),now());
        }
    }
    static boolean operator(Api.User user){return user.role().equals("admin") || user.role().equals("reviewer");}
    static void role(Api.User user,String... allowed) { if(!List.of(allowed).contains(user.role()) && !(List.of(allowed).contains("reviewer") && (user.canManage() || user.accountRole().equals("admin")))) throw Api.forbidden(); }
    static void pagination(int page,int size) { if(page<1||size<1||size>100||page>1_000_000) throw Api.invalid("分页参数超出范围"); }
}
