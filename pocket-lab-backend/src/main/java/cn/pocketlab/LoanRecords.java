package cn.pocketlab;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.ZoneId;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** A read projection across both live loan flows; writes retain their original state machines. */
@RestController
@RequestMapping("/api/loan-records")
@Transactional(readOnly=true)
public class LoanRecords {
    private static final String UNION = " FROM ("
        + "SELECT 'normal' AS source_type,br.id,br.user_id,br.status,br.equipment_id,e.name AS equipment_name,e.model AS equipment_model,"
        + "br.user_name,u.account AS user_account,br.asset_number,br.purpose,br.created_at FROM borrow_request br "
        + "JOIN equipment e ON e.id=br.equipment_id JOIN lab_user u ON u.id=br.user_id UNION ALL "
        + "SELECT 'other' AS source_type,id,user_id,status,NULL AS equipment_id,name AS equipment_name,model AS equipment_model,"
        + "user_name,user_account,asset_number,purpose,created_at FROM other_borrow_request WHERE workflow_mode IN ('checkout','legacy')"
        + ") lr";
    private static final String ORDER = "CASE WHEN lr.status='returning' THEN 0 WHEN lr.status='approved' THEN 1 "
        + "WHEN lr.status='pending' THEN 2 WHEN lr.status='borrowed' THEN 3 ELSE 4 END,lr.created_at DESC,lr.source_type,lr.id DESC";
    private static final List<String> STATES = List.of("pending","approved","borrowed","returning","returned","rejected","cancelled","closed");
    private static final String[] FILTER_FIELDS = {"status","statuses","q","keyword","from","to","borrowerId","equipmentId","sourceType"};
    private final Store store;
    private final Business business;
    private final OtherBorrows others;
    private final ObjectMapper json;
    private record Filter(String where,List<Object> args) {}

    LoanRecords(Store store,Business business,OtherBorrows others,ObjectMapper json) {
        this.store=store;this.business=business;this.others=others;this.json=json;
    }

    private JsonNode queryFilters(Map<String,String> query) {
        var result=json.createObjectNode();
        for(String field:FILTER_FIELDS) if(query.containsKey(field))result.put(field,query.get(field));
        return result;
    }

    private Filter filter(Api.User actor,String scope,JsonNode raw) {
        if(!Set.of("mine","management").contains(scope))throw Api.invalid("台账范围无效");
        if(scope.equals("management")&&!Store.operator(actor))throw Api.forbidden();
        var p=new Business.Input(raw,FILTER_FIELDS);var args=new ArrayList<Object>();String where=" WHERE 1=1";
        if(scope.equals("mine")){where+=" AND lr.user_id=?";args.add(actor.id());}
        if(p.has("status")&&p.has("statuses"))throw Api.invalid("status与statuses不能同时使用");
        if(p.has("status")||p.has("statuses")) {
            var selected=p.has("statuses")?Arrays.stream(p.text("statuses",200,true).split(",",-1)).map(String::trim).distinct().toList():List.of(p.text("status",32,true));
            if(selected.isEmpty()||selected.stream().anyMatch(status->!STATES.contains(status)))throw Api.invalid("未知借用状态组合");
            where+=" AND lr.status IN ("+String.join(",",Collections.nCopies(selected.size(),"?"))+")";args.addAll(selected);
        }
        String source=p.text("sourceType",16,false);
        if(!source.isEmpty()) {
            if(!Set.of("normal","other").contains(source))throw Api.invalid("借用来源无效");
            where+=" AND lr.source_type=?";args.add(source);
        }
        String equipment=p.text("equipmentId",36,false),borrower=p.text("borrowerId",36,false);
        if(!equipment.isEmpty()){where+=" AND lr.equipment_id=?";args.add(equipment);}
        if(!borrower.isEmpty()){
            if(scope.equals("mine")&&!actor.id().equals(borrower))throw Api.forbidden();
            where+=" AND lr.user_id=?";args.add(borrower);
        }
        String keyword=Business.search(p.text("keyword",100,false),p.text("q",100,false));
        if(!keyword.isBlank()) {
            String like=Business.like(keyword.trim());
            where+=" AND (lr.user_name LIKE ? ESCAPE '!' OR lr.user_account LIKE ? ESCAPE '!' OR lr.equipment_name LIKE ? ESCAPE '!'"
                + " OR lr.equipment_model LIKE ? ESCAPE '!' OR lr.asset_number LIKE ? ESCAPE '!' OR lr.id LIKE ? ESCAPE '!' OR lr.purpose LIKE ? ESCAPE '!')";
            args.addAll(Collections.nCopies(7,like));
        }
        var dates=new Business.Filters();dates.dates("lr.created_at",p.has("from")?p.date("from"):null,p.has("to")?p.date("to"):null,ZoneId.of("Asia/Shanghai"));
        where+=dates.where.replace(" WHERE 1=1","");args.addAll(dates.args);
        return new Filter(where,args);
    }

    private long count(Filter f){return store.db.queryForObject("SELECT COUNT(*)"+UNION+f.where(),Long.class,f.args().toArray());}

    private Map<String,Object> present(Map<String,Object> reference,Api.User actor) {
        String type=reference.get("sourceType").toString(),id=reference.get("id").toString();
        Map<String,Object> row;
        if(type.equals("normal"))row=json.convertValue(store.borrow(id,actor,false),new TypeReference<LinkedHashMap<String,Object>>(){});
        else {
            row=new LinkedHashMap<>(others.record(id,actor));
            row.put("equipmentName",row.get("name"));row.put("equipmentModel",row.get("model"));
            row.put("requiresAsset",false);
        }
        row.put("equipmentModel",reference.get("equipmentModel"));
        row.put("sourceType",type);row.put("recordKey",type+":"+id);
        return row;
    }

    @GetMapping
    public Object list(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="mine") String scope,
        @RequestParam(defaultValue="1") int page,@RequestParam(required=false) Integer pageSize,
        @RequestParam Map<String,String> query,HttpServletRequest request) {
        int size=pageSize==null?(scope.equals("mine")?5:10):pageSize;Store.pagination(page,size);
        var f=filter(actor,scope,queryFilters(query));long total=count(f);
        var args=new ArrayList<>(f.args());args.add(size);args.add((page-1)*size);
        var references=business.rows("SELECT lr.source_type,lr.id,lr.equipment_model"+UNION+f.where()+" ORDER BY "+ORDER+" LIMIT ? OFFSET ?",args.toArray());
        return Api.ok(new Api.Page<>(references.stream().map(row->present(row,actor)).toList(),total,page,size),request);
    }

    @GetMapping("/asset-scan")
    public Object assetScan(@AuthenticationPrincipal Api.User actor,@RequestParam String assetNumber,
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="10") int pageSize,HttpServletRequest request) {
        if(!Store.operator(actor))throw Api.forbidden();
        String number=AssetLedger.number(assetNumber);
        if(number==null)throw Api.invalid("请输入资产编号");
        Store.pagination(page,pageSize);
        var units=business.rows("SELECT asset_number,equipment_id,status,borrow_id,other_borrow_id FROM asset_unit WHERE asset_number=?",number);
        var unit=units.isEmpty()?Map.<String,Object>of():units.getFirst();
        String status=(String)unit.getOrDefault("status","unregistered");
        String equipmentId=(String)unit.get("equipmentId");
        String normalId=(String)unit.get("borrowId"),otherId=(String)unit.get("otherBorrowId");
        var equipment=equipmentId==null?null:store.equipment(equipmentId,false);
        var result=Business.map("assetNumber",number,"assetStatus",status,"equipmentId",equipmentId,
            "equipmentName",equipment==null?null:equipment.name(),"action",null,"record",null,"candidates",null,"reason",null);
        if(!unit.isEmpty()&&!number.equals(unit.get("assetNumber"))) {
            result.put("reason","资产编号大小写或字符不一致，请核对实物");
        } else if(status.equals("borrowed")) {
            scanBorrowed(actor,number,equipmentId,normalId,otherId,result);
        } else if(!Set.of("available","unregistered").contains(status)) {
            result.put("reason",switch(status) {
                case "repair" -> "资产正在维修";
                case "retired" -> "资产已报废";
                default -> "资产状态异常，请核对台账";
            });
        } else if(normalId!=null||otherId!=null) {
            result.put("reason","资产状态与关联借用记录不一致，请核对台账");
        } else {
            var filters=json.createObjectNode().put("status","approved").put("q",q);
            if(equipmentId!=null)filters.put("equipmentId",equipmentId);
            Filter base=filter(actor,"management",filters);
            var args=new ArrayList<>(base.args());
            args.add(actor.id());
            args.add(Inventory.OTHER_EQUIPMENT_CATEGORY);
            args.add(store.now());
            args.add(actor.id());
            String where=base.where()+" AND ((lr.source_type='normal' AND EXISTS (SELECT 1 FROM borrow_request br"
                + " JOIN equipment e ON e.id=br.equipment_id WHERE br.id=lr.id AND NOT (br.user_id=? AND e.category=?)"
                + " AND (br.pickup_deadline_at IS NULL OR br.pickup_deadline_at>=?) AND (br.pickup_proxy_id IS NULL OR br.slot_id IS NOT NULL)))"
                + " OR (lr.source_type='other' AND lr.user_id<>? AND EXISTS (SELECT 1 FROM other_borrow_request obr"
                + " WHERE obr.id=lr.id AND obr.workflow_mode='checkout')))";
            long total=count(new Filter(where,args));
            args.add(pageSize);
            args.add((page-1)*pageSize);
            var rows=business.rows("SELECT lr.source_type,lr.id,lr.equipment_model"+UNION+where+" ORDER BY "+ORDER+" LIMIT ? OFFSET ?",args.toArray());
            result.put("action","issue");
            result.put("candidates",new Api.Page<>(rows.stream().map(row->present(row,actor)).toList(),total,page,pageSize));
            if(total==0)result.put("reason",q.isBlank()?"没有可发放的借用记录":"没有符合搜索条件的可发放记录");
        }
        return Api.ok(result,request);
    }

    private void scanBorrowed(Api.User actor,String number,String equipmentId,String normalId,String otherId,Map<String,Object> result) {
        if((normalId==null)==(otherId==null)) {
            result.put("reason","资产没有唯一关联的借用记录，请核对台账");
            return;
        }
        String type=normalId==null?"other":"normal",id=normalId==null?otherId:normalId;
        var matches=business.rows("SELECT lr.source_type,lr.id,lr.equipment_model"+UNION+" WHERE lr.source_type=? AND lr.id=?",type,id);
        if(!matches.isEmpty()) {
            var row=present(matches.getFirst(),actor);
            boolean normal=normalId!=null;
            boolean consistent=number.equals(row.get("assetNumber"))
                && (normal ? equipmentId!=null&&equipmentId.equals(row.get("equipmentId")) : "checkout".equals(row.get("workflowMode")));
            boolean staffAllowed=!actor.id().equals(row.get("userId"))||(normal&&equipmentId!=null&&!Inventory.OTHER_EQUIPMENT_CATEGORY.equals(
                store.db.queryForObject("SELECT category FROM equipment WHERE id=?",String.class,equipmentId)));
            if(consistent&&staffAllowed&&Set.of("returning","borrowed").contains(row.get("status"))) {
                result.put("record",row);
                result.put("action","returning".equals(row.get("status"))?"return-confirm":"return-confirm-direct");
                return;
            }
            if(consistent&&!staffAllowed) {
                result.put("reason","请由其他工作人员核验这笔借用");
                return;
            }
        }
        result.put("reason","资产编号、器材或借用状态不一致，请核对台账");
    }

    @GetMapping("/summary")
    public Object summary(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="mine") String scope,
        @RequestParam Map<String,String> query,HttpServletRequest request) {
        var f=filter(actor,scope,queryFilters(query));var counts=new LinkedHashMap<String,Long>();STATES.forEach(status->counts.put(status,0L));
        for(var row:business.rows("SELECT lr.status,COUNT(*) AS count"+UNION+f.where()+" GROUP BY lr.status",f.args().toArray()))
            counts.put(row.get("status").toString(),((Number)row.get("count")).longValue());
        var result=new LinkedHashMap<String,Object>();result.putAll(counts);result.put("counts",counts);result.put("total",counts.values().stream().mapToLong(Long::longValue).sum());
        return Api.ok(result,request);
    }

    @PostMapping("/bulk-preview")
    public Object bulkPreview(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest request) {
        if(!Store.operator(actor))throw Api.forbidden();
        var p=new Business.Input(body,"action","scope","filters");String scope=p.has("scope")?p.text("scope",16,true):"management";
        String action=p.choice("action","approve","reject","issue","return-confirm");
        var f=filter(actor,scope,body.has("filters")?body.get("filters"):json.createObjectNode());long total=count(f);
        if(total>5000)throw Api.invalid("筛选结果超过5000条，请缩小条件后再批量处理；本次未截取或处理任何记录");
        var items=new ArrayList<Map<String,Object>>();
        for(var reference:business.rows("SELECT lr.source_type,lr.id,lr.equipment_model"+UNION+f.where()+" ORDER BY "+ORDER,f.args().toArray())) {
            var row=present(reference,actor);
            if(!(row.get("allowedActions") instanceof List<?> actions)||!actions.contains(action))continue;
            row.put("action",action);row.put("needsAsset","return-confirm".equals(action)&&row.get("assetNumber")!=null);items.add(row);
        }
        return Api.ok(Business.map("action",action,"total",total,"eligible",items.size(),"skipped",total-items.size(),"items",items),request);
    }
}
