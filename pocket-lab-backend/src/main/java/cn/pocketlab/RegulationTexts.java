package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

/** Independently versioned published plain text; existing PDF documents are never rewritten. */
@RestController @RequestMapping("/api")
public class RegulationTexts {
    final Store store; final Business business;
    RegulationTexts(Store store,Business business){this.store=store;this.business=business;}
    void kind(String kind){if(!Regulations.KINDS.contains(kind))throw Api.missing();}
    Map<String,Object> item(String kind,boolean administrator){
        kind(kind);
        var rows=store.db.query("SELECT t.kind,d.title,t.body,t.version,t.updated_at FROM regulation_text t JOIN regulation_document d ON d.kind=t.kind WHERE t.kind=?",(r,n)->{
            var row=Business.ROW.mapRow(r,n);String body=r.getString("body");
            row.put("format","plain");row.put("body",body);row.put("published",body!=null);
            row.put("allowedActions",administrator?List.of("edit"):List.of());return row;
        },kind);
        return rows.stream().findFirst().orElseThrow(Api::missing);
    }
    static String plain(JsonNode raw){
        if(raw==null||!raw.isTextual())throw Api.invalid("请填写纯文本正文");
        String value=raw.textValue().replace("\r\n","\n");
        if(value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c)||c==0xfeff)||value.length()>100000)throw Api.invalid("正文不能为空或超过100000个UTF-16字符");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isISOControl(c)&&c!='\n'&&c!='\t')throw Api.invalid("正文含不支持的控制字符");
            if(Character.isHighSurrogate(c)){
                if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw Api.invalid("正文含无效Unicode字符");i++;
            }else if(Character.isLowSurrogate(c))throw Api.invalid("正文含无效Unicode字符");
        }
        return value;
    }
    @GetMapping("/regulations/{kind}/content") @Transactional(readOnly=true)
    public Object content(@AuthenticationPrincipal Api.User actor,@PathVariable String kind,HttpServletRequest request){
        return Api.ok(item(kind,"admin".equals(actor.role())&&"admin".equals(actor.accountRole())),request);
    }
    @GetMapping("/public/regulations/{kind}/content") @Transactional(readOnly=true)
    public Object publicContent(@PathVariable String kind,HttpServletRequest request){return Api.ok(item(kind,false),request);}
    @PutMapping("/regulations/{kind}/content") @Transactional
    public Object publish(@AuthenticationPrincipal Api.User actor,@PathVariable String kind,@RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        Store.role(actor,"admin");if(!actor.accountRole().equals("admin"))throw Api.forbidden();kind(kind);
        return Api.ok(business.write(actor,key,"PUT /regulations/"+kind+"/content",body,()->{
            var input=new Input(body,"body","version");String value=plain(body.get("body"));
            var old=business.one("SELECT version FROM regulation_text WHERE kind=? FOR UPDATE",kind);business.version(old,input);
            store.db.update("UPDATE regulation_text SET body=?,updated_by=?,updated_at=?,version=version+1 WHERE kind=?",value,actor.id(),store.now(),kind);
            return item(kind,true);
        }),request);
    }
}
