package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api/purchase-requests")
public class Purchases {
    final Store s;final Business b;
    Purchases(Store s,Business b){this.s=s;this.b=b;}
    Map<String,Object> purchase(String id,Api.User actor){var row=b.one("SELECT p.*,u.name AS `user` FROM purchase_request p JOIN lab_user u ON u.id=p.user_id WHERE p.id=?",id);if(actor.role().equals("student")&&!actor.id().equals(row.get("userId")))throw Api.missing();row.put("allowedActions",actor.role().equals("reviewer")&&row.get("status").equals("pending")?List.of("approve","reject"):actor.role().equals("admin")&&row.get("status").equals("approved")?List.of("receive"):List.of());return row;}
    @GetMapping
    public Api.Envelope<?> list(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,HttpServletRequest req){String where=" WHERE 1=1";var args=new ArrayList<Object>();if(actor.role().equals("student")){where+=" AND user_id=?";args.add(actor.id());}if(!status.isEmpty()){if(!List.of("pending","approved","rejected","received").contains(status))throw Api.invalid("购置状态无效");where+=" AND status=?";args.add(status);}var result=b.page("SELECT id","FROM purchase_request",where,args,"created_at DESC,id DESC",page,pageSize);return Api.ok(new Api.Page<>(result.items().stream().map(row->purchase((String)row.get("id"),actor)).toList(),result.total(),page,pageSize),req);}
    @GetMapping("/{id}") public Api.Envelope<?> detail(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req){return Api.ok(purchase(id,actor),req);}
    @PostMapping @Transactional
    public Api.Envelope<Object> create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /purchase-requests",body,()->{Store.role(actor,"student");var p=new Input(body,"name","url","price","purpose");String price=p.text("price",20,true);if(!price.matches("[0-9]{1,10}(\\.[0-9]{1,2})?"))throw Api.invalid("价格须为最多两位小数的十进制字符串");var amount=new BigDecimal(price);if(amount.signum()<=0||amount.compareTo(new BigDecimal("9999999999.99"))>0)throw Api.invalid("预估单价无效");String id=Store.id();s.db.update("INSERT INTO purchase_request(id,user_id,name,url,price,purpose,status,created_at) VALUES(?,?,?,?,?,?,'pending',?)",id,actor.id(),p.text("name",100,true),url(p.text("url",1000,true),true),amount,p.text("purpose",500,true),s.now());return purchase(id,actor);}),req);}
    @PostMapping("/{id}/{action}") @Transactional
    public Api.Envelope<Object> action(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@PathVariable String action,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /purchase-requests/"+id+"/"+action,body,()->{var p=new Input(body,action.equals("receive")?new String[]{"version","equipmentId","quantity"}:action.equals("reject")?new String[]{"version","reason"}:new String[]{"version"});var row=purchase(id,actor);b.version(row,p);String target;
        switch(action){case "approve"->{Store.role(actor,"reviewer");state(row,"pending");target="approved";}case "reject"->{Store.role(actor,"reviewer");state(row,"pending");p.text("reason",500,true);target="rejected";}case "receive"->{Store.role(actor,"admin");state(row,"approved");String equipment=p.text("equipmentId",36,true);int quantity=(int)p.number("quantity",1,100);b.stock(actor,equipment,"add",quantity,"购置申请入库 "+id);s.db.update("UPDATE purchase_request SET equipment_id=?,received_quantity=?,received_at=? WHERE id=?",equipment,quantity,s.now(),id);target="received";}default->throw Api.missing();}
        s.db.update("UPDATE purchase_request SET status=?,reason=?,reviewed_at=?,version=version+1 WHERE id=?",target,action.equals("reject")?p.text("reason",500,true):null,s.now(),id);b.notify((String)row.get("userId"),"购置申请处理结果",row.get("name")+"："+(target.equals("approved")?"已批准，等待统一采购；请勿自行购买或报销。":target.equals("received")?"已验收到货并入库。":p.text("reason",500,true)),"purchase:"+id+":"+(((Number)row.get("version")).longValue()+1),id,(String)row.get("equipmentId"));return purchase(id,actor);
    }),req);}
}
