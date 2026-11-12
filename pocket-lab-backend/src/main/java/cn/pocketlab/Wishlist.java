package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

/** Shared member wishes, separate from private purchase requests and inventory. */
@RestController
@RequestMapping("/api/wishlist")
public class Wishlist {
    static final String[] CATEGORIES={"单片机","FPGA","嵌入式","物联网","机器人","人工智能","其他"};
    final Store s;
    final Business b;
    Wishlist(Store s,Business b){this.s=s;this.b=b;}

    // Read both values from the same statement; never expose creator identity or contact details.
    String projection(){return "SELECT w.id,w.name,w.model,w.category,w.description,w.function_description,w.url,w.created_at,w.status,w.reason,w.reviewed_at,w.version,w.procurement_stage,w.stocked_equipment_id,w.stocked_at,"
        +"(SELECT COUNT(*) FROM wish_support v WHERE v.wish_id=w.id) AS support_count,"
        +"(SELECT COUNT(*) FROM wish_support v WHERE v.wish_id=w.id AND v.user_id=?) AS my_support";}
    Map<String,Object> personalize(Map<String,Object> row,Api.User actor){
        row.put("function",row.remove("functionDescription"));
        var links=s.db.queryForList("SELECT url FROM wish_link WHERE wish_id=? ORDER BY position_index",String.class,row.get("id"));
        // Keep older imports/clients readable even if no link relation was written.
        if(links.isEmpty()&&row.get("url") instanceof String legacy&&!legacy.isBlank())links=List.of(legacy);
        row.put("urls",links);
        row.put("supportedByMe",((Number)row.remove("mySupport")).longValue()>0);
        String status=(String)row.get("status");boolean stocked="stocked".equals(row.get("procurementStage"));
        row.put("publicStage",stocked?"stocked":status.equals("approved")?"planned":"");
        row.put("allowedActions",actor.role().equals("admin")?(status.equals("pending")?List.of("approve","reject"):status.equals("approved")&&!stocked?List.of("stocked"):List.of()):List.of());
        return row;
    }
    Map<String,Object> item(String id,Api.User actor){
        return personalize(b.one(projection()+" FROM wish_item w WHERE w.id=?",actor.id(),id),actor);
    }

    @GetMapping
    @Transactional(readOnly=true)
    public Api.Envelope<?> list(@AuthenticationPrincipal Api.User actor,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="9") int pageSize,
        @RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String stage,
        HttpServletRequest req){
        Store.pagination(page,pageSize);
        var filters=new Filters();
        if(!category.isEmpty()&&!List.of(CATEGORIES).contains(category))throw Api.invalid("心愿分类无效");
        filters.eq("w.category",category);
        if(!status.isEmpty()&&!List.of("pending","approved","rejected").contains(status))throw Api.invalid("心愿状态无效");
        filters.eq("w.status",status);
        // Rejections remain auditable for admins; the public catalogue has only unplanned/planned/stocked wishes.
        if(!actor.role().equals("admin"))filters.where+=" AND w.status<>'rejected'";
        if(!stage.isEmpty()&&!List.of("planned","stocked").contains(stage))throw Api.invalid("采购展示状态无效");
        if(stage.equals("planned"))filters.where+=" AND w.status='approved' AND w.procurement_stage='none'";
        if(stage.equals("stocked"))filters.where+=" AND w.procurement_stage='stocked'";
        String match=like(keyword.trim());
        if(!keyword.isBlank()){
            filters.where+=" AND (w.name LIKE ? ESCAPE '!' OR w.model LIKE ? ESCAPE '!' OR w.description LIKE ? ESCAPE '!' OR w.function_description LIKE ? ESCAPE '!')";
            filters.args.addAll(List.of(match,match,match,match));
        }
        long total=s.db.queryForObject("SELECT COUNT(*) FROM wish_item w"+filters.where,Long.class,filters.args.toArray());
        var args=new ArrayList<Object>();args.add(actor.id());args.addAll(filters.args);args.add(pageSize);args.add(((long)page-1)*pageSize);
        var rows=b.rows(projection()+" FROM wish_item w"+filters.where+" ORDER BY w.created_at DESC,w.id DESC LIMIT ? OFFSET ?",args.toArray());
        return Api.ok(new Api.Page<>(rows.stream().map(row->personalize(row,actor)).toList(),total,page,pageSize),req);
    }

    @PostMapping
    @Transactional
    public Api.Envelope<Object> create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"POST /wishlist",body,()->{
            Store.role(actor,"student");
            var p=new Input(body,"name","model","category","description","function","url","urls");
            String id=Store.id();var links=links(body,p);
            s.db.update("INSERT INTO wish_item(id,creator_id,name,model,category,description,function_description,url,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id,actor.id(),p.text("name",160,true),p.text("model",100,false),p.choice("category",CATEGORIES),
                p.text("description",2000,true),p.text("function",2000,false),links.isEmpty()?"":links.getFirst(),s.now());
            for(int i=0;i<links.size();i++)s.db.update("INSERT INTO wish_link(wish_id,position_index,url) VALUES(?,?,?)",id,i,links.get(i));
            return item(id,actor);
        }),req);
    }

    private List<String> links(JsonNode body,Input p){
        String legacy=url(p.text("url",1000,false),false);
        if(!p.has("urls"))return legacy.isEmpty()?List.of():List.of(legacy);
        var values=body.get("urls");if(!values.isArray())throw Api.invalid("urls须为链接数组");
        var links=new ArrayList<String>();
        for(var node:values){
            if(!node.isTextual())throw Api.invalid("每条链接须为字符串");
            String value=node.textValue().trim();
            if(value.isEmpty()||value.length()>1000)throw Api.invalid("链接不能为空且不能超过1000字");
            links.add(url(value,true));
        }
        if(!legacy.isEmpty()&&(links.isEmpty()||!legacy.equals(links.getFirst())))throw Api.invalid("旧url须与urls首条一致");
        return links;
    }

    @PostMapping("/{id}/support")
    @Transactional
    public Api.Envelope<Object> support(@AuthenticationPrincipal Api.User actor,@PathVariable String id,
        @RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        new Input(body);
        return changeSupport(actor,id,true,body,key,req);
    }

    @DeleteMapping("/{id}/support")
    @Transactional
    public Api.Envelope<Object> cancel(@AuthenticationPrincipal Api.User actor,@PathVariable String id,
        @RequestBody(required=false) JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        if(body!=null)new Input(body);
        return changeSupport(actor,id,false,body,key,req);
    }

    private Api.Envelope<Object> changeSupport(Api.User actor,String id,boolean support,JsonNode body,String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,(support?"POST":"DELETE")+" /wishlist/"+id+"/support",body,()->{
            // Reuse the application's actor -> extension gate lock order and persistent replay records.
            var locked=b.one("SELECT id,status FROM wish_item WHERE id=? FOR UPDATE",id);
            if(support&&locked.get("status").equals("rejected"))throw Api.conflict("该心愿未通过审批，不能新增支持");
            if(support){
                if(s.db.queryForObject("SELECT COUNT(*) FROM wish_support WHERE wish_id=? AND user_id=?",Long.class,id,actor.id())==0)
                    s.db.update("INSERT INTO wish_support(wish_id,user_id,created_at) VALUES(?,?,?)",id,actor.id(),s.now());
            }else s.db.update("DELETE FROM wish_support WHERE wish_id=? AND user_id=?",id,actor.id());
            var row=item(id,actor);
            return map("id",id,"supportCount",row.get("supportCount"),"supportedByMe",row.get("supportedByMe"));
        }),req);
    }

    @PostMapping("/{id}/stocked")
    @Transactional
    public Api.Envelope<Object> stocked(@AuthenticationPrincipal Api.User actor,@PathVariable String id,
        @RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        Store.role(actor,"admin");
        return Api.ok(b.write(actor,key,"POST /wishlist/"+id+"/stocked",body,()->{
            var p=new Input(body,"version","equipmentId","confirmed");
            if(!p.bool("confirmed"))throw Api.invalid("请确认已采购到货并完成实际入库");
            var old=b.one("SELECT id,creator_id,status,procurement_stage,version FROM wish_item WHERE id=? FOR UPDATE",id);
            b.version(old,p);state(old,"approved");
            if(!"none".equals(old.get("procurementStage")))throw Api.conflict("此心愿已记录采购上架，请刷新核对");
            String equipment=p.text("equipmentId",36,true);
            var item=b.one("SELECT id,active,stock FROM equipment WHERE id=? FOR UPDATE",equipment);
            if(!Boolean.TRUE.equals(item.get("active"))||((Number)item.get("stock")).longValue()<1)throw Api.conflict("请选择已上架且已完成入库的真实器材");
            s.db.update("UPDATE wish_item SET procurement_stage='stocked',stocked_equipment_id=?,stocked_by=?,stocked_at=?,version=version+1 WHERE id=?",equipment,actor.id(),s.now(),id);
            b.notify((String)old.get("creatorId"),"心愿器材已采购并上架","你申请的心愿器材已由管理员确认采购并完成手动入库，可到器材目录查看借用。","wish-stocked:"+id,id,equipment);
            return item(id,actor);
        }),req);
    }

    @PostMapping("/{id}/{action}")
    @Transactional
    public Api.Envelope<Object> review(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@PathVariable String action,
        @RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        Store.role(actor,"admin");
        if(!List.of("approve","reject").contains(action))throw Api.missing();
        return Api.ok(b.write(actor,key,"POST /wishlist/"+id+"/"+action,body,()->{
            var p=new Input(body,action.equals("reject")?new String[]{"version","reason"}:new String[]{"version"});
            var old=b.one("SELECT id,creator_id,status,version FROM wish_item WHERE id=? FOR UPDATE",id);
            b.version(old,p);state(old,"pending");
            String reason=action.equals("reject")?p.text("reason",500,true):null;
            s.db.update("UPDATE wish_item SET status=?,reason=?,reviewed_by=?,reviewed_at=?,version=version+1 WHERE id=?",
                action.equals("approve")?"approved":"rejected",reason,actor.id(),s.now(),id);
            b.notify((String)old.get("creatorId"),"心愿审批结果",action.equals("approve")?"心愿已批准，等待管理员采购后手动入库；批准不会增加可借库存。":reason,
                "wish:"+id+":"+(((Number)old.get("version")).longValue()+1),null,null);
            return item(id,actor);
        }),req);
    }
}
