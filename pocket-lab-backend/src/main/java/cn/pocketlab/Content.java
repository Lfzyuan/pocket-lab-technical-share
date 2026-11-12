package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api")
public class Content {
    final Store s;final Business b;final TeacherDirectory teachers;
    Content(Store s,Business b,TeacherDirectory teachers){this.s=s;this.b=b;this.teachers=teachers;}
    static final String WORK="SELECT id,user_id,author,author_account,author_class AS author_class_name,teacher_name,teacher_user_id,author_contact,contact_public,anonymous,name,description AS `desc`,(SELECT category FROM equipment e WHERE e.id=work_item.equipment_id) AS category,equipment_id,location,media,file_id,status,reason,is_submission,created_at,reviewed_at,version";
    Map<String,Object> privateWork(String id,Api.User actor){
        var row=b.one(WORK+" FROM work_item WHERE id=?",id);
        if(!row.get("status").equals("published")&&!actor.role().equals("admin")&&!actor.id().equals(row.get("userId")))throw Api.missing();
        row.put("allowedActions",actor.role().equals("admin")&&row.get("status").equals("pending")?List.of("approve","reject"):List.of());return row;
    }
    Map<String,Object> publicWork(Map<String,Object> row){
        row.remove("userId");row.remove("teacherUserId");
        if(Boolean.TRUE.equals(row.get("anonymous"))){
            row.put("author","匿名作者");for(String key:List.of("authorAccount","authorClassName","teacherName","authorContact"))row.remove(key);
            row.put("contactPublic",false);
        }else if(!Boolean.TRUE.equals(row.get("contactPublic")))row.remove("authorContact");
        row.put("allowedActions",List.of());return row;
    }
    Map<String,Object> work(String id,Api.User actor){
        var row=privateWork(id,actor);
        return actor.role().equals("admin")||actor.id().equals(row.get("userId"))?row:publicWork(row);
    }
    @GetMapping("/works")
    public Api.Envelope<?> works(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String equipmentId,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String category,HttpServletRequest req){
        String where=" WHERE status='published'";var args=new ArrayList<Object>();if(!equipmentId.isEmpty()){where+=" AND equipment_id=?";args.add(equipmentId);}if(!keyword.isEmpty()){where+=" AND name LIKE ? ESCAPE '!'";args.add(like(keyword));}if(!category.isEmpty()){where+=" AND equipment_id IN (SELECT id FROM equipment WHERE category=?)";args.add(category);}
        var pageData=b.page(WORK,"FROM work_item",where,args,"created_at DESC,id DESC",page,pageSize);
        return Api.ok(new Api.Page<>(pageData.items().stream().map(this::publicWork).toList(),pageData.total(),page,pageSize),req);
    }
    @GetMapping("/works/{id}") public Api.Envelope<?> detail(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req){return Api.ok(work(id,actor),req);}
    @GetMapping("/work-submissions")
    public Api.Envelope<?> submissions(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String status,HttpServletRequest req){Store.role(actor,"student","admin");String where=" WHERE is_submission=TRUE";var args=new ArrayList<Object>();if(actor.role().equals("student")){where+=" AND user_id=?";args.add(actor.id());}if(!status.isEmpty()){if(!List.of("pending","published","rejected").contains(status))throw Api.invalid("作品状态无效");where+=" AND status=?";args.add(status);}var result=b.page("SELECT id","FROM work_item",where,args,"created_at DESC,id DESC",page,pageSize);return Api.ok(new Api.Page<>(result.items().stream().map(row->privateWork((String)row.get("id"),actor)).toList(),result.total(),page,pageSize),req);}
    String equipment(Input p){String id=p.text("equipmentId",36,false);if(!id.isEmpty())s.equipment(id,false);return id.isEmpty()?null:id;}
    String file(Input p,Api.User actor){String id=p.text("fileId",36,false);if(id.isEmpty())return null;var file=b.one("SELECT owner_id FROM file_object WHERE id=?",id);if(!file.get("ownerId").equals(actor.id()))throw Api.forbidden();return id;}
    String limited(Input p,String field,int legacyMax,int limit,Object old){String value=p.text(field,legacyMax,true);if(!value.equals(old)&&value.codePointCount(0,value.length())>limit)throw Api.invalid(field.equals("name")?"作品标题不能超过20字":"作品介绍不能超过200字");return value;}
    String optional(Input p,String field,int max,Map<String,Object> old){return p.has(field)?p.text(field,max,false):Objects.toString(old.get(field),"");}
    boolean option(Input p,String field,Map<String,Object> old){return p.has(field)?p.bool(field):Boolean.TRUE.equals(old.get(field));}
    @PostMapping({"/works","/work-submissions"}) @Transactional
    public Api.Envelope<Object> create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){boolean submission=req.getRequestURI().endsWith("work-submissions");return Api.ok(b.write(actor,key,"POST "+(submission?"/work-submissions":"/works"),body,()->{
        Store.role(actor,submission?"student":"admin");if(submission)Business.submissionPrivacy(body);var p=new Input(body,"name","desc","equipmentId","location","media","fileId","authorClassName","teacherName","teacherUserId","authorContact","contactPublic","anonymous");String id=Store.id();
        var teacher=teachers.optional(p,null);
        s.db.update("INSERT INTO work_item(id,user_id,author,author_account,author_class,teacher_name,teacher_user_id,author_contact,contact_public,anonymous,name,description,equipment_id,location,media,file_id,status,is_submission,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,actor.id(),actor.name(),actor.account(),p.has("authorClassName")?p.text("authorClassName",100,false):actor.className(),teacher.name(),teacher.id(),p.text("authorContact",200,false),p.has("contactPublic")&&p.bool("contactPublic"),p.has("anonymous")&&p.bool("anonymous"),limited(p,"name",100,20,null),limited(p,"desc",2000,200,null),equipment(p),p.text("location",200,false),url(p.text("media",1000,false),false),file(p,actor),submission?"pending":"published",submission,s.now());return privateWork(id,actor);
    }),req);}
    @PutMapping("/works/{id}") @Transactional
    public Api.Envelope<Object> edit(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PUT /works/"+id,body,()->{
        Store.role(actor,"admin");var p=new Input(body,"version","name","desc","equipmentId","location","media","fileId","authorClassName","teacherName","teacherUserId","authorContact","contactPublic","anonymous");var old=privateWork(id,actor);b.version(old,p);state(old,"published");String file=p.has("fileId")&&Objects.equals(old.get("fileId"),p.text("fileId",36,false))?(String)old.get("fileId"):file(p,actor);
        if(Boolean.TRUE.equals(old.get("isSubmission"))&&(body.has("anonymous")||body.has("contactPublic")))Business.submissionPrivacy(body);
        var teacher=teachers.optional(p,old);
        s.db.update("UPDATE work_item SET name=?,description=?,equipment_id=?,location=?,media=?,file_id=?,author_class=?,teacher_name=?,teacher_user_id=?,author_contact=?,contact_public=?,anonymous=?,version=version+1 WHERE id=?",limited(p,"name",100,20,old.get("name")),limited(p,"desc",2000,200,old.get("desc")),equipment(p),p.text("location",200,false),url(p.text("media",1000,false),false),file,optional(p,"authorClassName",100,old),teacher.name(),teacher.id(),optional(p,"authorContact",200,old),option(p,"contactPublic",old),option(p,"anonymous",old),id);return privateWork(id,actor);
    }),req);}
    @PostMapping("/work-submissions/{id}/{action}") @Transactional
    public Api.Envelope<Object> review(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@PathVariable String action,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /work-submissions/"+id+"/"+action,body,()->{Store.role(actor,"admin");if(!List.of("approve","reject").contains(action))throw Api.missing();var p=new Input(body,action.equals("reject")?new String[]{"version","reason"}:new String[]{"version"});var row=privateWork(id,actor);b.version(row,p);state(row,"pending");if(!Boolean.TRUE.equals(row.get("isSubmission")))throw Api.invalid("该记录不是学生投稿");String reason=action.equals("reject")?p.text("reason",500,true):null;s.db.update("UPDATE work_item SET status=?,reason=?,reviewed_at=?,version=version+1 WHERE id=?",action.equals("approve")?"published":"rejected",reason,s.now(),id);b.notify((String)row.get("userId"),"作品审核结果",row.get("name")+"："+(reason==null?"已公开展示":reason),"work:"+id+":"+(((Number)row.get("version")).longValue()+1),id,(String)row.get("equipmentId"));return privateWork(id,actor);}),req);}
    static final String RESOURCE_CATEGORY="COALESCE(NULLIF((SELECT e.category FROM equipment e WHERE e.id=r.equipment_id),''),NULLIF(r.category,''),'通用资料')";
    List<String> resourceWriteCategories(){
        var categories=new LinkedHashSet<>(List.of("单片机","FPGA","物联网","嵌入式","机器人","人工智能"));
        categories.addAll(s.db.queryForList("SELECT DISTINCT category FROM equipment WHERE category<>'' ORDER BY category",String.class));
        categories.add("通用资料");return new ArrayList<>(categories);
    }
    @GetMapping("/resources/categories")
    public Api.Envelope<?> resourceCategories(HttpServletRequest req){
        var writable=resourceWriteCategories();var categories=new LinkedHashSet<>(writable);
        categories.addAll(s.db.queryForList("SELECT DISTINCT "+RESOURCE_CATEGORY+" FROM resource_item r",String.class));
        return Api.ok(map("items",new ArrayList<>(categories),"writableItems",writable),req);
    }
    String resourceCategory(Input p,String equipmentId,String previous){
        String category=p.text("category",80,false);
        if(equipmentId!=null){
            String expected=s.equipment(equipmentId,false).category();
            if(!category.isEmpty()&&!category.equals(expected))throw Api.invalid("资料分类须与关联器材分类一致");
            return expected.isBlank()?"通用资料":expected;
        }
        if(category.isEmpty())return "通用资料";
        if(!resourceWriteCategories().contains(category)&&!category.equals(previous))throw Api.invalid("请选择器材分类或通用资料");
        return category;
    }
    static final String RESOURCE="SELECT r.id,r.name,"+RESOURCE_CATEGORY+" AS category,r.description,r.equipment_id,r.file_id,r.created_at AS date,r.version,f.content_type AS format FROM resource_item r LEFT JOIN file_object f ON f.id=r.file_id";
    @GetMapping("/resources")
    public Api.Envelope<?> resources(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String equipmentId,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,HttpServletRequest req){keyword=Business.search(keyword,q);String where=" WHERE 1=1";var args=new ArrayList<Object>();if(!category.isEmpty()){where+=" AND "+RESOURCE_CATEGORY+"=?";args.add(category);}if(!equipmentId.isEmpty()){where+=" AND r.equipment_id=?";args.add(equipmentId);}if(!keyword.isEmpty()){where+=" AND r.name LIKE ? ESCAPE '!'";args.add(like(keyword));}return Api.ok(b.page("SELECT r.id,r.name,"+RESOURCE_CATEGORY+" AS category,r.description,r.equipment_id,r.file_id,r.created_at AS date,r.version,f.content_type AS format","FROM resource_item r LEFT JOIN file_object f ON f.id=r.file_id",where,args,"r.created_at DESC,r.id DESC",page,pageSize),req);}
    @PostMapping("/resources") @Transactional
    public Api.Envelope<Object> resource(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"POST /resources",body,()->{Store.role(actor,"admin");var p=new Input(body,"name","category","description","fileId","equipmentId");String id=Store.id();String equipmentId=equipment(p);s.db.update("INSERT INTO resource_item(id,name,category,description,equipment_id,file_id,created_at) VALUES(?,?,?,?,?,?,?)",id,p.text("name",160,true),resourceCategory(p,equipmentId,null),p.text("description",2000,true),equipmentId,file(p,actor),s.now());return b.one(RESOURCE+" WHERE r.id=?",id);}),req);}
    @PutMapping("/resources/{id}") @Transactional
    public Api.Envelope<Object> editResource(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req){return Api.ok(b.write(actor,key,"PUT /resources/"+id,body,()->{Store.role(actor,"admin");var p=new Input(body,"version","name","category","description","fileId","equipmentId");var old=b.one(RESOURCE+" WHERE r.id=?",id);b.version(old,p);String file=p.has("fileId")&&Objects.equals(old.get("fileId"),p.text("fileId",36,false))?(String)old.get("fileId"):file(p,actor);
        var teacher=teachers.optional(p,old);String equipmentId=equipment(p);s.db.update("UPDATE resource_item SET name=?,category=?,description=?,equipment_id=?,file_id=?,version=version+1 WHERE id=?",p.text("name",160,true),resourceCategory(p,equipmentId,(String)old.get("category")),p.text("description",2000,true),equipmentId,file,id);return b.one(RESOURCE+" WHERE r.id=?",id);}),req);}
    @GetMapping("/resources/{id}/download")
    public Api.Envelope<?> download(@PathVariable String id,HttpServletRequest req){var row=b.one("SELECT f.id,f.name,f.content_type,f.size_bytes FROM resource_item r JOIN file_object f ON f.id=r.file_id WHERE r.id=?",id);return Api.ok(map("fileId",row.get("id"),"name",row.get("name"),"contentType",row.get("contentType"),"size",row.get("sizeBytes"),"url","/api/files/"+row.get("id")+"/content","requiresAuthorization",true),req);}
}
