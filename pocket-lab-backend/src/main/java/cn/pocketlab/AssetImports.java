package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.map;

@RestController
@RequestMapping("/api/assets/import")
public class AssetImports {
    private static final Duration PLAN_LIFETIME=Duration.ofMinutes(30);
    private final Store store;
    private final Business business;
    private final AssetLedger ledger;

    AssetImports(Store store,Business business,AssetLedger ledger) {
        this.store=store;this.business=business;this.ledger=ledger;
    }

    private record AssetRow(int row,String equipmentId,String assetNumber) {}

    @PostMapping("/preview")
    @Transactional
    public Api.Envelope<?> preview(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req) {
        Store.role(store.lockUser(actor.id()),"admin");
        new Business.Input(body,"rows");
        JsonNode input=body.get("rows");
        if(input==null||!input.isArray()||input.isEmpty()||input.size()>5000)throw Api.invalid("请提供1至5000行资产编号");
        var rows=new ArrayList<Map<String,Object>>();
        var usable=new ArrayList<AssetRow>();
        Map<String,Integer> frequency=new HashMap<>();
        int line=0;
        for(JsonNode item:input) {
            line++;
            int number=item.path("row").canConvertToInt()&&item.path("row").asInt()>0?item.path("row").asInt():line;
            String equipment=item.path("equipmentId").isTextual()?item.path("equipmentId").asText():"";
            String raw=item.path("assetNumber").isTextual()?item.path("assetNumber").asText():"";
            var result=map("row",number,"equipmentId",equipment,"assetNumber",raw,"status","valid","message","");
            try {
                var fields=new Business.Input(item,"row","equipmentId","assetNumber");
                if(fields.has("row"))fields.number("row",1,Integer.MAX_VALUE);
                equipment=fields.text("equipmentId",36,true);
                String asset=AssetLedger.number(raw);
                if(asset==null)throw Api.invalid("资产编号须为非空文本");
                frequency.merge(asset,1,Integer::sum);
                result.put("equipmentId",equipment);
                result.put("assetNumber",asset);
            } catch(Api.Failure error) {mark(result,"invalid",error.getMessage());}
            rows.add(result);
        }
        Map<String,Long> capacity=new HashMap<>();
        Map<String,String> canonicalIds=new HashMap<>();
        for(var row:rows) {
            if(!"valid".equals(row.get("status")))continue;
            String equipmentId=(String)row.get("equipmentId"),asset=(String)row.get("assetNumber");
            if(frequency.get(asset)>1){mark(row,"duplicate","表内资产编号重复");continue;}
            if(store.db.queryForObject("SELECT COUNT(*) FROM asset_unit WHERE asset_number=?",Long.class,asset)>0){mark(row,"existing","资产编号已存在");continue;}
            equipmentId=canonicalIds.computeIfAbsent(equipmentId,id->store.db.queryForList("SELECT id FROM equipment WHERE id=?",String.class,id)
                .stream().findFirst().orElse(""));
            if(equipmentId.isEmpty()){mark(row,"invalid","器材ID不存在");continue;}
            row.put("equipmentId",equipmentId);
            long remaining=capacity.computeIfAbsent(equipmentId,id->{
                int stock=store.db.queryForObject("SELECT stock FROM equipment WHERE id=?",Integer.class,id);
                return stock-ledger.activeCount(id,false);
            });
            if(remaining<=0){mark(row,"invalid","新增编号超出器材当前库存容量");continue;}
            capacity.put(equipmentId,remaining-1);
            usable.add(new AssetRow((int)row.get("row"),equipmentId,asset));
        }
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("total",rows.size());
        for(String status:List.of("valid","invalid","duplicate","existing"))summary.put(status,rows.stream().filter(row->status.equals(row.get("status"))).count());
        boolean canCommit=usable.size()==rows.size();String planId=null;
        if(canCommit) {
            planId=Store.id();
            store.db.update("INSERT INTO asset_import_plan(id,owner_id,rows_json,created_at) VALUES(?,?,?,?)",planId,actor.id(),business.encode(usable),store.now());
        }
        return Api.ok(map("planId",planId,"rows",rows,"summary",summary,"canCommit",canCommit),req);
    }

    @PostMapping("/commit")
    @Transactional
    public Api.Envelope<?> commit(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest req) {
        Store.role(actor,"admin");
        var fields=new Business.Input(body,"planId");String planId=fields.text("planId",36,true);
        return Api.ok(business.write(actor,key,"POST /assets/import/commit",body,()->{
            Store.role(store.user(actor.id()),"admin");
            var plans=store.db.queryForList("SELECT rows_json,created_at,added_count FROM asset_import_plan WHERE id=? AND owner_id=? FOR UPDATE",planId,actor.id());
            if(plans.isEmpty())throw Api.missing();
            var plan=plans.getFirst();
            if(plan.get("added_count")!=null)return map("added",plan.get("added_count"));
            if(((Timestamp)plan.get("created_at")).toInstant().plus(PLAN_LIFETIME).isBefore(store.clock.instant()))
                throw Api.conflict("资产导入预览已过期，请重新预览");
            AssetRow[] rows;
            try {rows=business.json.readValue((String)plan.get("rows_json"),AssetRow[].class);}
            catch(Exception error) {throw new IllegalStateException("Asset import plan cannot be read",error);}
            Map<String,Integer> needed=new TreeMap<>();
            for(var row:rows)needed.merge(row.equipmentId(),1,Integer::sum);
            // Lock every affected equipment row before touching assets. Single-loan
            // issuance uses the same equipment-before-asset order.
            Map<String,Integer> stocks=new HashMap<>();
            for(String equipmentId:needed.keySet())stocks.put(equipmentId,store.equipment(equipmentId,true).stock());
            for(var entry:needed.entrySet()) {
                if(ledger.activeCount(entry.getKey(),true)+entry.getValue()>stocks.get(entry.getKey()))
                    throw Api.conflict("器材库存容量已变化，请重新预览");
            }
            Arrays.sort(rows,Comparator.comparing(AssetRow::assetNumber));
            try {
                for(var row:rows)store.db.update("INSERT INTO asset_unit(asset_number,equipment_id,status) VALUES(?,?,'available')",row.assetNumber(),row.equipmentId());
            } catch(DuplicateKeyException error) {throw Api.conflict("资产编号已被其他操作录入，请重新预览");}
            store.db.update("UPDATE asset_import_plan SET added_count=? WHERE id=?",rows.length,planId);
            return map("added",rows.length);
        }),req);
    }

    private static void mark(Map<String,Object> row,String status,String message) {row.put("status",status);row.put("message",message);}
}
