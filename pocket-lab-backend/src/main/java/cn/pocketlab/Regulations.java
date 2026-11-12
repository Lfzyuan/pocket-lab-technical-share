package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

/** Two formal documents; titles exist before an administrator uploads the actual files. */
@RestController @RequestMapping("/api/regulations")
public class Regulations {
    final Store s; final Business b; final MediaStore media;
    Regulations(Store s,Business b,MediaStore media){this.s=s;this.b=b;this.media=media;}
    static final Set<String> KINDS=Set.of("management","usage");
    void kind(String kind){if(!KINDS.contains(kind))throw Api.missing();}
    String select(){return "SELECT d.kind,d.title,d.file_id,d.updated_at,d.version,f.name AS file_name,f.content_type,f.size_bytes AS size,CASE WHEN t.body IS NULL THEN 0 ELSE 1 END AS text_available,COALESCE(t.version,0) AS text_version,t.updated_at AS text_updated_at FROM regulation_document d LEFT JOIN file_object f ON f.id=d.file_id LEFT JOIN regulation_text t ON t.kind=d.kind";}
    Map<String,Object> item(String kind,Api.User actor){
        var row=b.one(select()+" WHERE d.kind=?",kind);
        row.put("available",row.get("fileId")!=null);
        row.put("textAvailable",((Number)row.get("textAvailable")).intValue()!=0);
        row.put("downloadUrl",row.get("fileId")==null?null:"/api/regulations/"+kind+"/download");
        row.put("allowedActions",actor.role().equals("admin")?List.of("replace"):List.of());
        return row;
    }
    @GetMapping @Transactional(readOnly=true)
    public Api.Envelope<?> list(@AuthenticationPrincipal Api.User actor,HttpServletRequest req){
        return Api.ok(map("items",List.of(item("management",actor),item("usage",actor))),req);
    }
    @PutMapping("/{kind}") @Transactional
    public Api.Envelope<Object> replace(@AuthenticationPrincipal Api.User actor,@PathVariable String kind,@RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest req){
        Store.role(actor,"admin");kind(kind);
        return Api.ok(b.write(actor,key,"PUT /regulations/"+kind,body,()->{
            var p=new Input(body,"fileId","version");
            var old=b.one("SELECT version FROM regulation_document WHERE kind=? FOR UPDATE",kind);b.version(old,p);
            String fileId=p.text("fileId",36,true);
            var file=media.file(actor,fileId);
            if(!"application/pdf".equals(file.get("contentType")))throw Api.invalid("正式规定正文必须为PDF文件");
            long administrator=s.db.queryForObject("SELECT COUNT(*) FROM lab_user WHERE id=? AND role='admin'",Long.class,file.get("ownerId"));
            if(administrator!=1)throw Api.invalid("请由管理员上传正式规定文件后再发布");
            s.db.update("UPDATE regulation_document SET file_id=?,updated_by=?,updated_at=?,version=version+1 WHERE kind=?",fileId,actor.id(),s.now(),kind);
            return item(kind,actor);
        }),req);
    }
    @GetMapping("/{kind}/download") @Transactional(readOnly=true)
    public ResponseEntity<Resource> download(@AuthenticationPrincipal Api.User actor,@PathVariable String kind){
        kind(kind);
        // Authorization is confined to the linked document, not arbitrary private file IDs.
        var row=b.one("SELECT f.id,f.owner_id,f.name,f.content_type,f.size_bytes,f.storage_path FROM regulation_document d JOIN file_object f ON f.id=d.file_id WHERE d.kind=?",kind);
        return media.response(row,false);
    }
}
