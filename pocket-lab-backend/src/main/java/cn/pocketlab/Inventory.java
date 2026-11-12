package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api")
public class Inventory {
    static final String OTHER_EQUIPMENT_CATEGORY = "其他器材";
    static boolean approvalRequired(String equipmentId) { return !Set.of("CAT01","CAT03").contains(equipmentId); }
    final Store s;final Business b;final AssetLedger assets;
    Inventory(Store s,Business b,AssetLedger assets){this.s=s;this.b=b;this.assets=assets;}
    @PostMapping("/equipment") @Transactional
    public Api.Envelope<Object> create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"POST /equipment",body,()->{Store.role(actor,"admin");
            var p=equipmentInput(body,true);String id=Store.id();
            var classification=classification(p,id);
            s.db.update("INSERT INTO equipment(id,name,brand,model,category,specification,description,accessories,location,image,active,requires_approval,requires_asset,stock,source_total,source_available,source_row) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,0,0,0,0)",
                id,p.text("name",160,true),p.text("brand",160,false),p.text("model",160,false),classification.category(),p.text("spec",5000,false),p.text("desc",5000,false),p.text("accessories",500,false),p.text("location",200,true),image(p,actor,null),!p.has("active")||p.bool("active"),classification.requiresApproval(),classification.requiresAsset());
            int stock=(int)p.number("stock",0,100000);if(stock>0)b.stock(actor,id,"add",stock,"新建器材初始入库");return s.equipment(id,false);
        }),req);
    }
    @PutMapping("/equipment/{id}") @Transactional
    public Api.Envelope<Object> edit(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"PUT /equipment/"+id,body,()->{Store.role(actor,"admin");var p=equipmentInput(body,false);var e=s.equipment(id,true);
            var classification=classification(p,id);
            if(e.version()!=p.number("version",0,Long.MAX_VALUE))throw Api.conflict("器材已更新，请刷新");
            if(e.requiresAsset()!=classification.requiresAsset()&&(e.reserved()>0||e.borrowed()>0||
                (OTHER_EQUIPMENT_CATEGORY.equals(classification.category())&&e.pending()>0)||s.db.queryForObject("SELECT COUNT(*) FROM asset_unit WHERE equipment_id=?",Long.class,id)>0))throw Api.conflict("仍有待处理借用或已有资产台账，不能切换资产管理方式");
            s.db.update("UPDATE equipment SET name=?,brand=?,model=?,category=?,specification=?,description=?,accessories=?,location=?,image=?,active=?,requires_approval=?,requires_asset=?,version=version+1 WHERE id=?",p.text("name",160,true),p.text("brand",160,false),p.text("model",160,false),classification.category(),p.text("spec",5000,false),p.text("desc",5000,false),p.text("accessories",500,false),p.text("location",200,true),image(p,actor,e.image()),p.bool("active"),classification.requiresApproval(),classification.requiresAsset(),id);return s.equipment(id,false);
        }),req);
    }
    private record Classification(String category, boolean requiresApproval, boolean requiresAsset) {}
    private Classification classification(Input input,String equipmentId) {
        var value = new Classification(input.text("category",80,false), approvalRequired(equipmentId), input.bool("requiresAsset"));
        if (OTHER_EQUIPMENT_CATEGORY.equals(value.category()) && !value.requiresApproval()) {
            throw Api.invalid("其他器材须启用借用审批");
        }
        return value;
    }
    Input equipmentInput(JsonNode body,boolean create){return create?new Input(body,"name","brand","model","category","spec","desc","accessories","location","image","active","requiresApproval","requiresAsset","stock"):new Input(body,"version","name","brand","model","category","spec","desc","accessories","location","image","active","requiresApproval","requiresAsset");}
    String image(Input p,Api.User actor,String previous){
        String image=p.text("image",500,false);
        if(image.matches("/api/files/[a-f0-9-]{36}/image")){
            var file=b.one("SELECT owner_id,content_type FROM file_object WHERE id=?",image.split("/")[3]);
            if(!Files.IMAGE_TYPES.contains(file.get("contentType")))throw Api.invalid("器材图片仅支持PNG、JPEG或WebP");
            if(!image.equals(previous)&&!actor.id().equals(file.get("ownerId")))throw Api.forbidden();
        }else if(!image.isEmpty()&&!image.matches("/static/[A-Za-z0-9_./-]+"))url(image,true);
        return image.isEmpty()?null:image;
    }
    @PostMapping("/equipment/{id}/stock-adjustments") @Transactional
    public Api.Envelope<Object> adjust(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"POST /equipment/"+id+"/stock-adjustments",body,()->{Store.role(actor,"admin");var p=new Input(body,"version","mode","count","reason");var e=s.equipment(id,true);if(e.version()!=p.number("version",0,Long.MAX_VALUE))throw Api.conflict("器材已更新，请刷新");b.stock(actor,id,p.choice("mode","add","remove"),(int)p.number("count",1,100000),p.text("reason",500,true));return s.equipment(id,false);}),req);
    }
    @GetMapping("/equipment/{id}/assets")
    public Api.Envelope<?> assets(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){Store.role(actor,"reviewer","admin");s.equipment(id,false);return Api.ok(b.page("SELECT asset_number,equipment_id,status,borrow_id,other_borrow_id","FROM asset_unit"," WHERE equipment_id=?",List.of(id),"asset_number",page,pageSize),req);}
    @GetMapping("/stock-logs")
    public Api.Envelope<?> logs(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String equipmentId,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to,HttpServletRequest req){Store.role(actor,"admin");var f=new Filters();f.eq("l.equipment_id",equipmentId);f.dates("l.occurred_at",from,to,s.clock.getZone());return Api.ok(b.page("SELECT l.id,l.equipment_id,l.mode,l.quantity,l.after_stock AS after,l.reason,l.actor_id,l.occurred_at AS at,u.name AS by_name","FROM stock_log l JOIN lab_user u ON u.id=l.actor_id",f.where,f.args,"l.occurred_at DESC,l.id DESC",page,pageSize),req);}
    @GetMapping("/incidents")
    public Api.Envelope<?> incidents(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String equipmentId,@RequestParam(defaultValue="") String kind,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to,HttpServletRequest req){Store.role(actor,"reviewer","admin");if(!status.isEmpty()&&!List.of("repair","writtenOff","resolved").contains(status))throw Api.invalid("异常状态无效");if(!kind.isEmpty()&&!List.of("damaged","lost","retired","discrepancy").contains(kind))throw Api.invalid("异常类型无效");var f=new Filters();f.eq("status",status);f.eq("equipment_id",equipmentId);f.eq("kind",kind);f.dates("created_at",from,to,s.clock.getZone());return Api.ok(b.page("SELECT *","FROM incident",f.where,f.args,"created_at DESC,id DESC",page,pageSize),req);}
    @PostMapping("/incidents") @Transactional
    public Api.Envelope<Object> incident(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        return Api.ok(b.write(actor,key,"POST /incidents",body,()->{Store.role(actor,"reviewer","admin");var p=new Input(body,"equipmentId","requestId","kind","quantity","assetNumber","reason");String eq=p.text("equipmentId",36,true),request=p.text("requestId",36,false),kind=p.choice("kind","damaged","lost","retired","discrepancy"),reason=p.text("reason",500,true),asset=p.text("assetNumber",100,false);int quantity=(int)p.number("quantity",1,100000);var e=s.equipment(eq,true);String user=null;
            if(!request.isEmpty()){
                Store.role(actor,"reviewer");var r=s.borrow(request,actor,true);if(!eq.equals(r.equipmentId())||!List.of("borrowed","returning").contains(r.status())||quantity!=r.quantity())throw Api.conflict("只能异常结单借用中或归还待验收的同一器材记录");
                if(!List.of("damaged","lost").contains(kind))throw Api.invalid("借用异常仅能登记损坏或丢失");
                if(!Objects.equals(r.assetNumber(),asset.isEmpty()?null:asset))throw Api.invalid("异常资产编号与借用不匹配");user=r.userId();
                s.db.update("UPDATE equipment SET borrowed=borrowed-?,version=version+1 WHERE id=?",quantity,eq);
                s.db.update("UPDATE borrow_request SET status='closed',active_slot=NULL,closed_at=?,version=version+1 WHERE id=?",s.now(),request);s.event(request,"incident",r.status(),"closed",actor.id(),reason);
            }else{
                Store.role(actor,"admin");if(quantity>e.available())throw new Api.Failure(409,"STOCK_INSUFFICIENT","盘点异常不能占用已预留或借出的器材");
            }
            if(!asset.isEmpty()) {
                if(quantity!=1)throw Api.invalid("带资产编号的异常登记每次限1件");
                assets.markIncident(AssetLedger.number(asset),eq,request.isEmpty()?null:request,kind.equals("damaged")?"repair":"retired");
            }
            b.stock(actor,eq,"remove",quantity,"异常登记："+kind+"；"+reason);
            String id=Store.id();s.db.update("INSERT INTO incident(id,equipment_id,request_id,kind,quantity,asset_number,user_id,reason,status,actor_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,eq,request.isEmpty()?null:request,kind,quantity,asset.isEmpty()?null:asset,user,reason,kind.equals("damaged")?"repair":"writtenOff",actor.id(),s.now());
            if(user!=null)b.notify(user,"借用异常登记",reason,"incident:"+id,request,eq);return b.one("SELECT * FROM incident WHERE id=?",id);
        }),req);
    }
    @PostMapping("/incidents/{id}/repair") @Transactional
    public Api.Envelope<Object> repair(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /incidents/"+id+"/repair",body,()->{Store.role(actor,"admin");var p=new Input(body,"version");var row=b.one("SELECT * FROM incident WHERE id=? FOR UPDATE",id);b.version(row,p);state(row,"repair");b.stock(actor,(String)row.get("equipmentId"),"add",((Number)row.get("quantity")).intValue(),"维修验收恢复 "+id);s.db.update("UPDATE incident SET status='resolved',resolved_at=?,version=version+1 WHERE id=?",s.now(),id);if(row.get("assetNumber")!=null)s.db.update("UPDATE asset_unit SET status='available' WHERE asset_number=? AND status='repair'",row.get("assetNumber"));return b.one("SELECT * FROM incident WHERE id=?",id);}),req);}
}
