package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api")
public class Laboratories {
    final Store s;final Business b;final TeacherDirectory teachers;
    Laboratories(Store s,Business b,TeacherDirectory teachers){this.s=s;this.b=b;this.teachers=teachers;}
    static final String LAB="SELECT id,name,location,description AS `desc`,capacity,major_equipment,manager_teacher_name,version,opening_date_from,opening_date_to,opening_slot_ids,opening_half_day_date_from,opening_half_day_date_to,opening_half_day_slot_ids";
    static final String BOOK="SELECT x.*,x.first_date AS date,u.name AS `user`,u.class_name,u.account,l.name AS lab_name,l.location AS lab_location,(SELECT reviewer.name FROM lab_user reviewer WHERE reviewer.id=x.reviewed_by) AS reviewed_by_name FROM lab_booking x JOIN lab_user u ON u.id=x.user_id JOIN lab l ON l.id=x.lab_id";
    @GetMapping("/labs")
    public Api.Envelope<?> labs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){return Api.ok(b.page(LAB,"FROM lab"," WHERE deleted=FALSE",List.of(),"name,id",page,pageSize),req);}
    @PostMapping("/labs") @Transactional
    public Api.Envelope<Object> create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /labs",body,()->{Store.role(actor,"admin");var p=new Input(body,"name","location","desc","capacity","majorEquipment","managerTeacherName","openingSchedule");String id=Store.id();s.db.update("INSERT INTO lab(id,name,location,description,capacity,major_equipment,manager_teacher_name) VALUES(?,?,?,?,?,?,?)",id,p.text("name",160,true),p.text("location",200,true),p.text("desc",2000,true),p.number("capacity",1,1000),p.text("majorEquipment",2000,false),p.text("managerTeacherName",80,false));if(p.has("openingSchedule"))replacePlan(id,body.get("openingSchedule"));return b.one(LAB+" FROM lab WHERE id=?",id);}),req);}
    @PutMapping("/labs/{id}") @Transactional
    public Api.Envelope<Object> update(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PUT /labs/"+id,body,()->{Store.role(actor,"admin");var p=new Input(body,"version","name","location","desc","capacity","majorEquipment","managerTeacherName","openingSchedule");var lab=b.one("SELECT * FROM lab WHERE id=? AND deleted=FALSE FOR UPDATE",id);b.version(lab,p);long capacity=p.number("capacity",1,1000);
        var dates=s.db.queryForList("SELECT DISTINCT c.slot_date FROM lab_booking x JOIN lab_booking_cell c ON c.booking_id=x.id WHERE x.lab_id=? AND x.status='approved' AND c.slot_date>=?",java.sql.Date.class,id,s.today());
        for(var date:dates)for(String period:SCHEDULE_PERIODS)if(occupied(id,date.toLocalDate(),period,"")>capacity)throw Api.conflict("容量不能小于已有批准预约的人数");
        s.db.update("UPDATE lab SET name=?,location=?,description=?,capacity=?,major_equipment=?,manager_teacher_name=?,version=version+1 WHERE id=?",p.text("name",160,true),p.text("location",200,true),p.text("desc",2000,true),capacity,p.has("majorEquipment")?p.text("majorEquipment",2000,false):lab.get("majorEquipment"),p.has("managerTeacherName")?p.text("managerTeacherName",80,false):lab.get("managerTeacherName"),id);if(p.has("openingSchedule"))replacePlan(id,body.get("openingSchedule"));return b.one(LAB+" FROM lab WHERE id=?",id);
    }),req);}
    // Replacing one generation of opening windows leaves the other generation intact.
    void replacePlan(String id, JsonNode body) {
        var input = new Input(body, "dateFrom", "dateTo", "slotIds", "mode");
        LocalDate from = input.date("dateFrom"), to = input.date("dateTo");
        validateOpeningDates(from, to);
        JsonNode raw = body.get("slotIds");
        String mode = input.text("mode", 16, false);
        if (!mode.isEmpty() && !mode.equals("halfDay")) throw Api.invalid("请选择上午、下午或晚上");
        List<String> selected = raw != null && raw.isArray() && raw.isEmpty() ? List.of() : halfDaySlots(raw);
        var approved = b.rows("SELECT DISTINCT c.slot_date,c.slot_id FROM lab_booking x JOIN lab_booking_cell c "
            + "ON c.booking_id=x.id WHERE x.lab_id=? AND x.status='approved' AND c.slot_date>=?", id, s.today());
        for (var cell : approved) {
            LocalDate day = (LocalDate) cell.get("slotDate");
            String slot = (String) cell.get("slotId");
            if (!HALF_DAYS.contains(slot)) continue;
            if (day.isBefore(from) || day.isAfter(to) || !selected.contains(slot)) {
                throw Api.conflict(day + " " + periodLabel(slot) + "已有批准预约，不能关闭；本次资料与时间均未修改");
            }
        }
        s.db.update("DELETE FROM lab_opening_half_day WHERE lab_id=? AND slot_date>=?", id, s.today());
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (String slot : selected) setOpening(id, day, slot, true);
        }
        setPlanSummary(id, from, to, String.join(",", selected), false);
    }

    @DeleteMapping("/labs/{id}") @Transactional
    public Api.Envelope<Object> delete(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"DELETE /labs/"+id,body,()->{
        Store.role(actor,"admin");var p=new Input(body,"version");var lab=b.one("SELECT * FROM lab WHERE id=? AND deleted=FALSE FOR UPDATE",id);b.version(lab,p);
        if(s.db.queryForObject("SELECT COUNT(*) FROM lab_booking WHERE lab_id=? AND status IN ('pending','approved') AND end_date>=?",Long.class,id,s.today())>0)throw Api.conflict("该实验室还有未结束的待审批或已批准预约，请先处理预约后再删除");
        s.db.update("UPDATE lab SET deleted=TRUE,version=version+1 WHERE id=?",id);return map("id",id,"deleted",true);
    }),req);}
    @PutMapping("/labs/{id}/schedule/{date}/{slotId}") @Transactional
    public Api.Envelope<Object> schedule(@AuthenticationPrincipal Api.User actor, @PathVariable String id,
                                         @PathVariable LocalDate date, @PathVariable String slotId,
                                         @RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key,
                                         HttpServletRequest request) {
        return Api.ok(b.write(actor, key, "PUT /labs/" + id + "/schedule/" + date + "/" + slotId, body, () -> {
            Store.role(actor, "admin");
            var input = new Input(body, "enabled");
            boolean enabled = input.bool("enabled");
            b.one("SELECT id FROM lab WHERE id=? AND deleted=FALSE FOR UPDATE", id);
            if (!HALF_DAYS.contains(slotId)) throw Api.invalid("请选择上午、下午或晚上");
            validateOpeningDates(date, date);
            if (!enabled && approvedUsesOpening(id, date, slotId)) throw Api.conflict("已有批准预约，不能直接关闭此时段，请先协调预约人");
            setOpening(id, date, slotId, enabled);
            setPlanSummary(id, null, null, null, true);
            return map("labId", id, "date", date, "slotId", slotId, "enabled", enabled);
        }), request);
    }

    @PutMapping("/labs/{id}/schedule-range") @Transactional
    public Api.Envelope<Object> scheduleRange(@AuthenticationPrincipal Api.User actor, @PathVariable String id,
                                              @RequestBody JsonNode body, @RequestHeader("Idempotency-Key") String key,
                                              HttpServletRequest request) {
        return Api.ok(b.write(actor, key, "PUT /labs/" + id + "/schedule-range", body, () -> {
            Store.role(actor, "admin");
            var input = new Input(body, "dateFrom", "dateTo", "slotIds", "enabled");
            LocalDate from = input.date("dateFrom"), to = input.date("dateTo");
            boolean enabled = input.bool("enabled");
            List<String> selected = halfDaySlots(body.get("slotIds"));
            validateOpeningDates(from, to);
            b.one("SELECT id FROM lab WHERE id=? AND deleted=FALSE FOR UPDATE", id);
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                for (String slot : selected) {
                    if (!enabled && approvedUsesOpening(id, day, slot)) {
                        throw Api.conflict(day + " " + periodLabel(slot) + "已有批准预约，不能关闭；本次整批未修改");
                    }
                    setOpening(id, day, slot, enabled);
                }
            }
            setPlanSummary(id, null, null, null, true);
            return map("labId", id, "dateFrom", from, "dateTo", to, "slotIds", selected, "enabled", enabled);
        }), request);
    }

    private void validateOpeningDates(LocalDate from, LocalDate to) {
        LocalDate today = s.today();
        if (from.isBefore(today) || to.isBefore(from) || to.isAfter(today.plusDays(365))) {
            throw Api.invalid("开放日期须为今天起一年内，结束日期不能早于开始日期");
        }
    }

    private void setPlanSummary(String id, LocalDate from, LocalDate to, String slots, boolean increment) {
        s.db.update("UPDATE lab SET opening_half_day_date_from=?,opening_half_day_date_to=?,opening_half_day_slot_ids=?"
            + (increment ? ",version=version+1" : "") + " WHERE id=?", from, to, slots, id);
    }

    private static String openingTable(String slot) {
        return HALF_DAYS.contains(slot) ? "lab_opening_half_day"
            : slot.equals("S5") ? "lab_opening_extra" : "lab_opening_period";
    }

    private void setOpening(String lab, LocalDate date, String slot, boolean enabled) {
        if (s.db.update("UPDATE lab_opening_half_day SET enabled=? WHERE lab_id=? AND slot_date=? AND period_id=?",
                enabled, lab, date, slot) == 0) {
            s.db.update("INSERT INTO lab_opening_half_day(lab_id,slot_date,period_id,enabled) VALUES(?,?,?,?)",
                lab, date, slot, enabled);
        }
    }

    private boolean approvedUsesOpening(String lab, LocalDate date, String slot) {
        return s.db.queryForObject("SELECT COUNT(*) FROM lab_booking x JOIN lab_booking_cell c ON c.booking_id=x.id "
            + "WHERE x.lab_id=? AND x.status='approved' AND c.slot_date=? AND c.slot_id=?", Long.class, lab, date, slot) > 0;
    }

    @GetMapping("/labs/{id}/availability")
    public Api.Envelope<?> availability(@PathVariable String id, @RequestParam(required=false) LocalDate date,
                                        @RequestParam(required=false) LocalDate endDate, @RequestParam(required=false) LocalDate dateFrom,
                                        @RequestParam(required=false) Integer days, HttpServletRequest request) {
        if (date != null && dateFrom != null && !date.equals(dateFrom)) throw Api.invalid("date与dateFrom不能传不同日期");
        if (days != null && (days < 1 || days > 31)) throw Api.invalid("days须为1至31");
        var lab = b.one("SELECT * FROM lab WHERE id=? AND deleted=FALSE", id);
        LocalDate from = date != null ? date : dateFrom != null ? dateFrom : s.today();
        LocalDate to = endDate == null ? from.plusDays(days == null ? 6 : days - 1) : endDate;
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 30) throw Api.invalid("查询范围为1至31天");
        int capacity = ((Number) lab.get("capacity")).intValue();
        var cells = new ArrayList<Object>();
        var periods = new ArrayList<Object>();
        var halfDays = new ArrayList<Object>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            List<Assignment> occupants = assignments(id, day, "");
            for (String slot : List.of("AM", "PM")) cells.add(availabilityCell(id, day, slot, capacity, occupants));
            for (String slot : PERIODS) periods.add(availabilityCell(id, day, slot, capacity, occupants));
            for (String slot : HALF_DAYS) halfDays.add(availabilityCell(id, day, slot, capacity, occupants));
        }
        return Api.ok(map("labId", id, "cells", cells, "periods", periods, "halfDays", halfDays), request);
    }

    private Map<String,Object> availabilityCell(String lab, LocalDate date, String slot, int capacity, List<Assignment> occupants) {
        int occupied = peakOccupancy(slot, occupants);
        return map("date", date, "slotId", slot, "startTime", start(slot), "endTime", end(slot),
            "enabled", open(lab, date, slot), "capacity", capacity, "occupied", occupied, "available", Math.max(0, capacity - occupied));
    }

    private static final int MAX_BOOKING_SPAN = 7;
    private static final int BOOKING_AHEAD_DAYS = 13;
    static final List<String> PERIODS = List.of("S1", "S2", "S3", "S4", "S5");
    static final List<String> HALF_DAYS = List.of("B1", "B2", "B3");
    private static final List<String> SCHEDULE_PERIODS = List.of("S1", "S2", "S3", "S4", "S5", "B1", "B2", "B3");
    private static final List<String> ALL_SLOTS = List.of("AM", "PM", "ALL", "S1", "S2", "S3", "S4", "S5", "B1", "B2", "B3");

    private record Window(LocalTime start, LocalTime end) {
        boolean contains(LocalTime time) { return !time.isBefore(start) && time.isBefore(end); }
        boolean overlaps(Window other) { return start.isBefore(other.end) && other.start.isBefore(end); }
    }
    private record Cell(LocalDate date, String slotId) {}
    private record Occupant(String bookingId, String account) {}
    private record Assignment(Occupant occupant, String slotId) {}

    private static Window window(String start, String end) { return new Window(LocalTime.parse(start), LocalTime.parse(end)); }
    private static final Map<String,Window> WINDOWS = Map.of(
        "AM", window("08:00","12:00"), "PM", window("14:00","18:00"),
        "S1", window("08:00","09:40"), "S2", window("10:00","11:40"),
        "S3", window("14:00","15:40"), "S4", window("16:00","17:40"), "S5", window("19:00","20:40"),
        "B1", window("08:00","13:00"), "B2", window("13:00","18:00"), "B3", window("18:00","22:00"));
    private static final Comparator<Cell> CELL_ORDER = Comparator.comparing(Cell::date)
        .thenComparing(cell -> windows(cell.slotId()).getFirst().start()).thenComparing(Cell::slotId);

    private static List<Window> windows(String slot) {
        return slot.equals("ALL") ? List.of(WINDOWS.get("AM"), WINDOWS.get("PM")) : List.of(WINDOWS.get(slot));
    }
    private static List<String> periods(String slot) {
        return switch (slot) {
            case "AM" -> List.of("S1", "S2");
            case "PM" -> List.of("S3", "S4");
            case "ALL" -> List.of("S1", "S2", "S3", "S4");
            default -> List.of(slot);
        };
    }
    private static String half(String slot) {
        return List.of("S3", "S4", "S5", "B2", "B3").contains(slot) ? "PM" : "AM";
    }
    private static List<String> halfDaySlots(JsonNode input) {
        if (input == null || !input.isArray() || input.isEmpty() || input.size() > HALF_DAYS.size()) {
            throw Api.invalid("请选择上午、下午或晚上");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode item : input) {
            if (!item.isTextual() || !HALF_DAYS.contains(item.asText()) || !result.add(item.asText())) {
                throw Api.invalid("时段无效或重复");
            }
        }
        return HALF_DAYS.stream().filter(result::contains).toList();
    }

    private boolean open(String lab, LocalDate date, String slot) {
        if (!SCHEDULE_PERIODS.contains(slot)) return periods(slot).stream().allMatch(period -> open(lab, date, period));
        var explicit = s.db.queryForList("SELECT enabled FROM " + openingTable(slot)
            + " WHERE lab_id=? AND slot_date=? AND period_id=?", Boolean.class, lab, date, slot);
        if (!explicit.isEmpty()) return explicit.getFirst();
        return !HALF_DAYS.contains(slot) && !slot.equals("S5") && s.db.queryForObject(
            "SELECT COUNT(*) FROM lab_opening WHERE lab_id=? AND slot_date=? AND slot_id=? AND enabled=TRUE",
            Long.class, lab, date, half(slot)) > 0;
    }
    private static List<String> overlaps(String slot) {
        List<Window> requested = windows(slot);
        return ALL_SLOTS.stream().filter(candidate -> windows(candidate).stream()
            .anyMatch(existing -> requested.stream().anyMatch(existing::overlaps))).toList();
    }
    private static String placeholders(int count) { return String.join(",", Collections.nCopies(count, "?")); }

    private List<Assignment> assignments(String lab, LocalDate date, String exclude) {
        return s.db.query("SELECT x.id,m.account,c.slot_id FROM lab_booking x "
            + "JOIN lab_booking_cell c ON c.booking_id=x.id JOIN lab_booking_member m ON m.booking_id=x.id "
            + "WHERE x.lab_id=? AND x.status='approved' AND c.slot_date=? AND x.id<>?",
            (row, n) -> new Assignment(new Occupant(row.getString(1), row.getString(2)), row.getString(3)), lab, date, exclude);
    }
    private static int peakOccupancy(String slot, List<Assignment> assignments) {
        List<Window> requested = windows(slot);
        // The count can only increase when an occupied window starts.
        Set<LocalTime> boundaries = new HashSet<>();
        requested.forEach(window -> boundaries.add(window.start()));
        assignments.forEach(assignment -> windows(assignment.slotId()).forEach(window -> boundaries.add(window.start())));
        int peak = 0;
        for (LocalTime boundary : boundaries) {
            if (requested.stream().noneMatch(window -> window.contains(boundary))) continue;
            Set<Occupant> atTime = new HashSet<>();
            for (Assignment assignment : assignments) {
                if (windows(assignment.slotId()).stream().anyMatch(window -> window.contains(boundary))) atTime.add(assignment.occupant());
            }
            peak = Math.max(peak, atTime.size());
        }
        return peak;
    }
    private int occupied(String lab, LocalDate date, String slot, String exclude) {
        return peakOccupancy(slot, assignments(lab, date, exclude));
    }

    private static String start(String slot) { return windows(slot).getFirst().start().toString(); }
    private static String end(String slot) { return windows(slot).getLast().end().toString(); }
    static String periodLabel(String slot) { return slot.equals("ALL") ? "全天" : start(slot) + "—" + end(slot); }

    private static List<String> bookingSlots(Map<String,Object> row) {
        Object value = row.get("slotIds");
        if (value instanceof List<?> list) return list.stream().map(Object::toString).toList();
        return value instanceof String text && !text.isBlank() ? List.of(text.split(",")) : List.of((String) row.get("slotId"));
    }
    private List<Cell> bookingCells(Map<String,Object> row) {
        List<Cell> saved = s.db.query("SELECT slot_date,slot_id FROM lab_booking_cell WHERE booking_id=?",
            (r, n) -> new Cell(r.getObject(1, LocalDate.class), r.getString(2)), row.get("id"));
        if (!saved.isEmpty()) return saved.stream().sorted(CELL_ORDER).toList();
        return legacyCells((LocalDate) row.get("date"), (LocalDate) row.get("endDate"), bookingSlots(row));
    }
    private static List<Cell> legacyCells(LocalDate from, LocalDate to, List<String> selected) {
        var cells = new ArrayList<Cell>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            for (String slot : selected) {
                if (slot.equals("ALL")) {
                    cells.add(new Cell(day, "AM"));
                    cells.add(new Cell(day, "PM"));
                } else cells.add(new Cell(day, slot));
            }
        }
        return cells.stream().sorted(CELL_ORDER).toList();
    }

    private List<Cell> requestedCells(Input input, JsonNode body) {
        List<Cell> cells;
        if (body.has("cells")) {
            if (List.of("date", "endDate", "slotId", "slotIds").stream().anyMatch(body::has)) {
                throw Api.invalid("逐日时段与旧预约格式不能同时使用");
            }
            JsonNode raw = body.get("cells");
            if (!raw.isArray() || raw.isEmpty() || raw.size() > (BOOKING_AHEAD_DAYS + 1) * HALF_DAYS.size()) {
                throw Api.invalid("请选择有效的逐日预约时段");
            }
            Set<Cell> unique = new HashSet<>();
            for (JsonNode item : raw) {
                var cell = new Input(item, "date", "slotId");
                if (!unique.add(new Cell(cell.date("date"), cell.choice("slotId", "B1", "B2", "B3")))) {
                    throw Api.invalid("同一日期的预约时段不能重复");
                }
            }
            cells = unique.stream().sorted(CELL_ORDER).toList();
        } else {
            LocalDate from = input.date("date"), to = input.date("endDate");
            validateBookingWindow(from, to);
            if (ChronoUnit.DAYS.between(from, to) >= MAX_BOOKING_SPAN) {
                throw Api.invalid("每次最多连续预约7天");
            }
            List<String> selected = input.has("slotIds") ? halfDaySlots(body.get("slotIds"))
                : List.of(input.choice("slotId", "B1", "B2", "B3"));
            cells = legacyCells(from, to, selected);
        }
        validateBookingWindow(cells.getFirst().date(), cells.getLast().date());
        LocalDate previous = null;
        int consecutive = 0;
        for (Cell cell : cells) {
            LocalDate day = cell.date();
            if (day.equals(previous)) continue;
            consecutive = previous != null && previous.plusDays(1).equals(day) ? consecutive + 1 : 1;
            if (consecutive > MAX_BOOKING_SPAN) throw Api.invalid("每次最多连续预约7天");
            previous = day;
        }
        return cells;
    }
    private void validateBookingWindow(LocalDate from, LocalDate to) {
        LocalDate today = s.today();
        if (from.isBefore(today) || to.isAfter(today.plusDays(BOOKING_AHEAD_DAYS)) || to.isBefore(from)) {
            throw Api.invalid("预约日期须在含今天的14天内");
        }
    }
    private boolean future(Cell cell) {
        return cell.date().atTime(windows(cell.slotId()).getFirst().start()).isAfter(LocalDateTime.now(s.clock));
    }
    private static Map<String,Object> cellData(Cell cell) {
        return map("date", cell.date(), "slotId", cell.slotId(), "startTime", start(cell.slotId()), "endTime", end(cell.slotId()));
    }

    List<Map<String,Object>> members(Api.User actor,JsonNode input){var list=new ArrayList<Map<String,Object>>();list.add(map("account",actor.account(),"name",actor.name(),"internal",true,"affiliation","本院","note",""));var seen=new HashSet<String>();seen.add(actor.account());
        if(input==null||!input.isArray()||input.size()>99)throw Api.invalid("members须为数组，同行最多99人");
        for(var row:input){var p=new Input(row,"account","name");String account=p.text("account",32,true),name=p.text("name",80,true);if(!account.matches("[A-Za-z0-9_.-]{2,32}"))throw Api.invalid("同行人员学号格式无效");if(!seen.add(account))throw Api.invalid("团队成员重复，申请人已自动加入，无须重复填写");
            var found=s.db.query("SELECT * FROM lab_user WHERE account=?",Store.USER,account);boolean internal=!found.isEmpty();if(internal&&(!found.getFirst().name().equals(name)||!found.getFirst().enabled()||!found.getFirst().role().equals("student")))throw Api.invalid("同行人员姓名与学号不匹配或账号不可用");
            list.add(map("account",account,"name",name,"internal",internal,"affiliation",internal?"本院":"外院","note",internal?"":"外院"));
        }return list;
    }
    void check(String lab, List<Cell> cells, List<Map<String,Object>> members, String exclude) {
        int capacity = ((Number) b.one("SELECT capacity FROM lab WHERE id=? AND deleted=FALSE FOR UPDATE", lab).get("capacity")).intValue();
        Map<LocalDate,List<Assignment>> occupantsByDate = new HashMap<>();
        for (Cell cell : cells) {
            LocalDate date = cell.date();
            String slot = cell.slotId();
            if (!future(cell)) throw Api.conflict("预约时段已经开始");
            if (!open(lab, date, slot)) throw Api.conflict(date + " " + slot + "尚未开放");
            List<Assignment> occupants = occupantsByDate.computeIfAbsent(date, d -> assignments(lab, d, exclude));
            if (peakOccupancy(slot, occupants) + members.size() > capacity) {
                throw new Api.Failure(409, "CAPACITY_FULL", date + " " + slot + "剩余容量不足");
            }
            List<String> overlap = overlaps(slot);
            for (var member : members) {
                var args = new ArrayList<Object>(List.of(exclude, member.get("account"), date));
                args.addAll(overlap);
                long conflicts = s.db.queryForObject("SELECT COUNT(*) FROM lab_booking x JOIN lab_booking_cell c ON c.booking_id=x.id "
                    + "JOIN lab_booking_member m ON m.booking_id=x.id WHERE x.status IN ('pending','approved') "
                    + "AND x.id<>? AND m.account=? AND c.slot_date=? AND c.slot_id IN (" + placeholders(overlap.size()) + ")",
                    Long.class, args.toArray());
                if (conflicts > 0) throw new Api.Failure(409, "MEMBER_CONFLICT",
                    "团队成员 " + member.get("name") + " 在 " + date + " " + slot + " 已有预约");
            }
        }
    }
    Map<String,Object> booking(String id, Api.User actor) {
        var row = b.one(BOOK + " WHERE x.id=?", id);
        if (actor.role().equals("student") && !actor.id().equals(row.get("userId"))) throw Api.missing();
        row.remove("firstDate");
        Object period = row.remove("periodId");
        if (period instanceof String value && !value.isBlank()) row.put("slotId", value);
        var members = b.rows("SELECT account,name,internal FROM lab_booking_member WHERE booking_id=? ORDER BY account", id);
        for (var member : members) {
            boolean internal = Boolean.TRUE.equals(member.get("internal"));
            member.put("affiliation", internal ? "本院" : "外院");
            member.put("note", internal ? "" : "外院");
        }
        row.put("members", members);
        row.put("slotIds", bookingSlots(row));
        List<Cell> cells = bookingCells(row);
        row.put("cells", cells.stream().map(Laboratories::cellData).toList());
        boolean future = !cells.isEmpty() && future(cells.getFirst());
        row.put("allowedActions", Store.operator(actor) && row.get("status").equals("pending") ? List.of("approve","reject")
            : actor.id().equals(row.get("userId")) && future && List.of("pending","approved").contains(row.get("status")) ? List.of("cancel") : List.of());
        return row;
    }
    @GetMapping("/lab-bookings")
    public Api.Envelope<?> bookings(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String labId,@RequestParam(required=false) LocalDate date,HttpServletRequest req){String where=" WHERE 1=1";var args=new ArrayList<Object>();if(actor.role().equals("student")){where+=" AND user_id=?";args.add(actor.id());}if(!status.isEmpty()){if(!List.of("pending","approved","rejected","cancelled").contains(status))throw Api.invalid("预约状态无效");where+=" AND status=?";args.add(status);}if(!labId.isEmpty()){where+=" AND lab_id=?";args.add(labId);}if(date!=null){where+=" AND (EXISTS (SELECT 1 FROM lab_booking_cell c WHERE c.booking_id=lab_booking.id AND c.slot_date=?) OR (NOT EXISTS (SELECT 1 FROM lab_booking_cell c WHERE c.booking_id=lab_booking.id) AND first_date<=? AND end_date>=?))";args.add(date);args.add(date);args.add(date);}var result=b.page("SELECT id","FROM lab_booking",where,args,"created_at DESC,id DESC",page,pageSize);return Api.ok(new Api.Page<>(result.items().stream().map(row->booking((String)row.get("id"),actor)).toList(),result.total(),page,pageSize),req);}
    @GetMapping("/lab-bookings/summary")
    public Api.Envelope<?> bookingSummary(@AuthenticationPrincipal Api.User actor,HttpServletRequest req){
        Store.role(actor,"admin");return Api.ok(b.one("SELECT COUNT(*) AS total,COUNT(CASE WHEN status='pending' THEN 1 END) AS pending,COUNT(CASE WHEN status='approved' THEN 1 END) AS approved,COUNT(CASE WHEN status='rejected' THEN 1 END) AS rejected,COUNT(CASE WHEN status='cancelled' THEN 1 END) AS cancelled FROM lab_booking"),req);
    }
    @GetMapping("/lab-bookings/{id}")
    public Api.Envelope<?> detail(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req){return Api.ok(booking(id,actor),req);}
    @PostMapping("/lab-bookings") @Transactional
    public Api.Envelope<Object> createBooking(@AuthenticationPrincipal Api.User actor, @RequestBody JsonNode body,
                                               @RequestHeader("Idempotency-Key") String key, HttpServletRequest request) {
        return Api.ok(b.write(actor, key, "POST /lab-bookings", body, () -> {
            Store.role(actor, "student");
            var input = new Input(body, "labId", "date", "endDate", "slotId", "slotIds", "cells", "teacherName",
                "teacherId", "teacherUserId", "members", "phone", "purpose", "usedEquipment");
            String lab = input.text("labId", 36, true), phone = input.text("phone", 32, false);
            if (!phone.isEmpty() && !phone.matches("1[0-9]{10}")) throw Api.invalid("请填写11位手机号");
            JsonNode purposeValue = body.path("purpose");
            if (!purposeValue.isTextual() || purposeValue.asText().strip().isBlank()) {
                throw Api.invalid("请填写实验内容");
            }
            String purpose = purposeValue.asText().strip();
            if (purpose.length() > 500) throw Api.invalid("实验内容不能超过500字");
            List<Cell> cells = requestedCells(input, body);
            TeacherDirectory.Teacher teacher = teachers.require(input.text("teacherUserId", 36, true));
            var members = members(actor, body.get("members"));
            check(lab, cells, members, "");
            List<String> selected = HALF_DAYS.stream()
                .filter(slot -> cells.stream().anyMatch(cell -> cell.slotId().equals(slot))).toList();
            String id = Store.id(), firstSlot = cells.getFirst().slotId();
            s.db.update("INSERT INTO lab_booking(id,lab_id,user_id,first_date,end_date,slot_id,teacher_name,teacher_id,teacher_user_id,"
                + "phone,purpose,used_equipment,period_id,slot_ids,status,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'pending',?)",
                id, lab, actor.id(), cells.getFirst().date(), cells.getLast().date(), half(firstSlot),
                teacher.name(), teacher.account(), teacher.id(), phone, purpose,
                input.text("usedEquipment", 1000, false), firstSlot, String.join(",", selected), s.now());
            for (var member : members) s.db.update("INSERT INTO lab_booking_member(booking_id,account,name,internal) VALUES(?,?,?,?)",
                id, member.get("account"), member.get("name"), member.get("internal"));
            for (Cell cell : cells) s.db.update("INSERT INTO lab_booking_cell(booking_id,slot_date,slot_id) VALUES(?,?,?)",
                id, cell.date(), cell.slotId());
            return booking(id, actor);
        }), request);
    }

    @PostMapping("/lab-bookings/{id}/{action}") @Transactional
    public Api.Envelope<Object> action(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@PathVariable String action,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){if(List.of("approve","reject").contains(action)&&!Store.operator(actor))throw Api.forbidden();return Api.ok(b.write(actor,key,"POST /lab-bookings/"+id+"/"+action,body,()->{var p=new Input(body,action.equals("reject")?new String[]{"version","reason"}:action.equals("approve")?new String[]{"version","temporaryPassword"}:new String[]{"version"});var row=booking(id,actor);b.version(row,p);String target;
        switch(action){case "approve"->{state(row,"pending");String code=p.text("temporaryPassword",100,false);if(code.isBlank())throw Api.invalid("请填写本次预约临时密码");if(code.chars().anyMatch(Character::isISOControl))throw Api.invalid("临时密码不能包含换行或控制字符");
            var members=b.rows("SELECT account,name,internal FROM lab_booking_member WHERE booking_id=?",id);
            // Revalidate all known identities at approval, including accounts disabled since submission.
            for(var member:members)if(Boolean.TRUE.equals(member.get("internal"))&&s.db.queryForObject("SELECT COUNT(*) FROM lab_user WHERE account=? AND name=? AND role='student' AND enabled=TRUE",Long.class,member.get("account"),member.get("name"))==0)throw Api.conflict("团队成员账号信息已变化，请核对后重新申请");
            if (row.get("teacherUserId") instanceof String teacherId) teachers.require(teacherId);
            check((String)row.get("labId"),bookingCells(row),members,id);target="approved";
        }case "reject"->{state(row,"pending");p.text("reason",500,true);target="rejected";}
        case "cancel"->{Store.role(actor,"student");if(!actor.id().equals(row.get("userId")))throw Api.forbidden();state(row,"pending","approved");if(!future(bookingCells(row).getFirst()))throw Api.conflict("预约已开始，请联系管理员");target="cancelled";}
        default->throw Api.missing();}
        int changed = action.equals("cancel")
            ? s.db.update("UPDATE lab_booking SET status=?,version=version+1 WHERE id=? AND version=?", target, id, row.get("version"))
            : s.db.update("UPDATE lab_booking SET status=?,reason=?,reviewed_at=?,reviewed_by=?,version=version+1 WHERE id=? AND version=?",
                target, action.equals("reject") ? p.text("reason",500,true) : null, s.now(), actor.id(), id, row.get("version"));
        if (changed != 1) throw Api.conflict("预约记录已更新，请刷新后重试");
        String schedule = target.equals("approved") ? String.join("、", bookingCells(row).stream()
            .map(cell -> cell.date() + " " + start(cell.slotId()) + "—" + end(cell.slotId())).toList()) : "";
        if (target.equals("approved") && row.get("teacherUserId") instanceof String teacherId
            && !teacherId.equals(row.get("userId"))) {
            b.notify(teacherId, "指导的实验室预约已通过",
                "实验室：" + row.get("labName") + "。预约时间：" + schedule + "。",
                "lab-advisor:" + id + ":approved", id, null);
        }
        b.notify((String)row.get("userId"),"实验室预约处理结果",target.equals("approved")?"预约已通过。审批人："+actor.name()+"。实验室："+row.get("labName")+"。预约有效时段："+schedule+"。本次临时密码："+p.text("temporaryPassword",100,true)+"。仅限本次预约时段使用，门锁密码更换请联系实验室管理员。":target.equals("rejected")?"审批人："+actor.name()+"。未通过原因："+p.text("reason",500,true):"预约已取消，本次预约的临时密码不再适用。","lab:"+id+":"+(((Number)row.get("version")).longValue()+1),id,null);return booking(id,actor);
    }),req);}
}
