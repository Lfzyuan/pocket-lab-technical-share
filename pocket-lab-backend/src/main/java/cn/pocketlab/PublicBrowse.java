package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

/** Explicit anonymous projections. No user principal, session, write, or media-ticket creation. */
@RestController @RequestMapping("/api/public")
@Transactional(readOnly=true)
public class PublicBrowse {
    final Store store;final Business business;final MediaStore media;final Laboratories laboratories;
    PublicBrowse(Store store,Business business,MediaStore media,Laboratories laboratories){this.store=store;this.business=business;this.media=media;this.laboratories=laboratories;}
    static final String EQUIPMENT="SELECT id,name,brand,model,category,specification AS spec,description AS `desc`,accessories,location,image,stock-reserved-borrowed AS available,requires_approval,requires_asset,version";
    static final String LAB="SELECT id,name,location,description AS `desc`,capacity,major_equipment,opening_date_from,opening_date_to,opening_slot_ids,version";
    static final String WORK="SELECT id,name,description,category,equipment_id,location,created_at,version,cover_id,file_id";
    String search(String keyword,String q){String value=Business.search(keyword,q);if(value.length()>100)throw Api.invalid("搜索条件过长");return value;}
    String publicImage(Object value){
        if(!(value instanceof String image))return null;
        if(image.matches("/api/files/[a-f0-9-]{36}/image"))return "/api/public/files/"+image.split("/")[3]+"/content";
        return image.matches("/static/[A-Za-z0-9_./-]+")&&!image.contains("..")?image:null;
    }
    Map<String,Object> equipment(Map<String,Object> row){row.put("image",publicImage(row.get("image")));return row;}
    @GetMapping("/equipment") public Object equipment(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="9") int pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String category,HttpServletRequest request){
        String text=search(keyword,q);if(category.length()>80)throw Api.invalid("分类过长");String where=" WHERE active=TRUE";var args=new ArrayList<Object>();
        if(!text.isBlank()){where+=" AND (name LIKE ? ESCAPE '!' OR model LIKE ? ESCAPE '!')";args.addAll(List.of(like(text),like(text)));}
        if(!category.isBlank()){where+=" AND category=?";args.add(category);}
        var result=business.page(EQUIPMENT,"FROM equipment",where,args,"CASE WHEN id='CAT01' THEN 0 WHEN id='CAT03' THEN 1 ELSE 2 END,id",page,pageSize);
        return Api.ok(new Api.Page<>(result.items().stream().map(this::equipment).toList(),result.total(),page,pageSize),request);
    }
    @GetMapping("/equipment/{id}") public Object equipment(@PathVariable String id,HttpServletRequest request){return Api.ok(equipment(business.one(EQUIPMENT+" FROM equipment WHERE id=? AND active=TRUE",id)),request);}
    Map<String,Object> laboratory(Map<String,Object> row){Object csv=row.get("openingSlotIds");row.put("openingSlotIds",csv instanceof String text&&!text.isBlank()?Arrays.stream(text.split(",")).filter(Laboratories.PERIODS::contains).toList():List.of());return row;}
    @GetMapping("/labs") public Object labs(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,HttpServletRequest request){
        String text=search(keyword,q);String where=" WHERE deleted=FALSE";var args=new ArrayList<Object>();
        if(!text.isBlank()){where+=" AND (name LIKE ? ESCAPE '!' OR location LIKE ? ESCAPE '!')";args.addAll(List.of(like(text),like(text)));}
        var result=business.page(LAB,"FROM lab",where,args,"name,id",page,pageSize);
        return Api.ok(new Api.Page<>(result.items().stream().map(this::laboratory).toList(),result.total(),page,pageSize),request);
    }
    @GetMapping("/labs/{id}") public Object lab(@PathVariable String id,HttpServletRequest request){return Api.ok(laboratory(business.one(LAB+" FROM lab WHERE id=? AND deleted=FALSE",id)),request);}
    @GetMapping("/labs/{id}/availability") public Object availability(@PathVariable String id,@RequestParam(required=false) LocalDate date,
        @RequestParam(required=false) LocalDate endDate,@RequestParam(required=false) LocalDate dateFrom,@RequestParam(required=false) Integer days,HttpServletRequest request){
        return laboratories.availability(id,date,endDate,dateFrom,days,request);
    }
    static String extension(String type){return switch(type){
        case "image/png"->"png";case "image/jpeg"->"jpg";case "image/webp"->"webp";case "video/mp4"->"mp4";case "video/webm"->"webm";case "video/quicktime"->"mov";
        case "application/pdf"->"pdf";case "text/plain"->"txt";case "text/csv"->"csv";case "application/zip"->"zip";
        case "application/vnd.openxmlformats-officedocument.wordprocessingml.document"->"docx";case "application/msword"->"doc";
        case "application/vnd.openxmlformats-officedocument.presentationml.presentation"->"pptx";case "application/vnd.ms-powerpoint"->"ppt";default->"bin";};}
    static String filename(String type){return "public-file."+extension(type);}
    Map<String,Object> fileItem(Map<String,Object> row){String id=row.get("id").toString(),type=row.get("type").toString();return map("id",id,"name",filename(type),"type",type,"size",row.get("size"),"url","/api/public/files/"+id+"/content");}
    Map<String,Object> work(Map<String,Object> row){
        String id=row.get("id").toString();var result=map("id",id,"name",row.get("name"),"desc",row.get("description"),"category",row.get("category"),"equipmentId",row.get("equipmentId"),"location",row.get("location"),"createdAt",row.get("createdAt"),"version",row.get("version"));
        String cover=row.get("coverId")==null?null:"/api/public/files/"+row.get("coverId")+"/content";result.put("coverUrl",cover);
        String image=cover;if(image==null&&row.get("equipmentId")!=null){var images=store.db.queryForList("SELECT image FROM equipment WHERE id=? AND active=TRUE",String.class,row.get("equipmentId"));if(!images.isEmpty())image=publicImage(images.getFirst());}result.put("image",image);
        var files=business.rows("SELECT f.id,f.content_type AS type,f.size_bytes AS size,w.kind FROM work_file w JOIN file_object f ON f.id=w.file_id WHERE w.work_id=? ORDER BY w.position,f.id",id);
        result.put("mediaFiles",files.stream().filter(file->"media".equals(file.get("kind"))).map(this::fileItem).toList());
        var attachments=new ArrayList<>(files.stream().filter(file->"attachment".equals(file.get("kind"))).map(this::fileItem).toList());
        if(row.get("fileId")!=null&&attachments.stream().noneMatch(file->file.get("id").equals(row.get("fileId"))))attachments.add(fileItem(business.one("SELECT id,content_type AS type,size_bytes AS size FROM file_object WHERE id=?",row.get("fileId"))));
        result.put("attachments",attachments);return result;
    }
    @GetMapping("/works") public Object works(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String equipmentId,HttpServletRequest request){
        String text=search(keyword,q);if(category.length()>80||equipmentId.length()>36)throw Api.invalid("筛选条件过长");String where=" WHERE status='published'";var args=new ArrayList<Object>();
        if(!equipmentId.isBlank()){where+=" AND equipment_id=?";args.add(equipmentId);}
        if(!text.isBlank()){where+=" AND (name LIKE ? ESCAPE '!' OR description LIKE ? ESCAPE '!')";args.addAll(List.of(like(text),like(text)));}
        if(!category.isBlank()){where+=" AND category=?";args.add(category);}
        var result=business.page(WORK,"FROM work_item",where,args,"created_at DESC,id DESC",page,pageSize);
        return Api.ok(new Api.Page<>(result.items().stream().map(this::work).toList(),result.total(),page,pageSize),request);
    }
    @GetMapping("/works/{id}") public Object work(@PathVariable String id,HttpServletRequest request){return Api.ok(work(business.one(WORK+" FROM work_item WHERE id=? AND status='published'",id)),request);}
    @GetMapping("/files/{id}/content") public ResponseEntity<Resource> file(@PathVariable String id){
        var row=business.one("SELECT f.id,f.name,f.content_type,f.size_bytes,f.storage_path FROM file_object f WHERE f.id=? AND (EXISTS(SELECT 1 FROM equipment e WHERE e.active=TRUE AND e.image=?) OR EXISTS(SELECT 1 FROM work_item w WHERE w.status='published' AND (w.cover_id=f.id OR w.file_id=f.id OR EXISTS(SELECT 1 FROM work_file wf WHERE wf.work_id=w.id AND wf.file_id=f.id))))",id,"/api/files/"+id+"/image");
        row.put("name",filename(row.get("contentType").toString()));return media.response(row,row.get("contentType").toString().matches("^(image|video)/.*"));
    }

    @GetMapping("/resources") public Object resources(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String category,@RequestParam(defaultValue="") String equipmentId,HttpServletRequest request){
        String text=search(keyword,q);if(category.length()>80||equipmentId.length()>36)throw Api.invalid("筛选条件过长");String where=" WHERE 1=1";var args=new ArrayList<Object>();
        if(!text.isBlank()){where+=" AND r.name LIKE ? ESCAPE '!'";args.add(like(text));}
        if(!category.isBlank()){where+=" AND "+Content.RESOURCE_CATEGORY+"=?";args.add(category);}
        if(!equipmentId.isBlank()){where+=" AND r.equipment_id=?";args.add(equipmentId);}
        var result=business.page("SELECT r.id,r.name,"+Content.RESOURCE_CATEGORY+" AS category,r.description,r.equipment_id,r.created_at AS date,r.version,f.content_type AS format,CASE WHEN f.id IS NULL THEN 0 ELSE 1 END AS has_file",
            "FROM resource_item r LEFT JOIN file_object f ON f.id=r.file_id",where,args,"r.created_at DESC,r.id DESC",page,pageSize);
        var items=result.items().stream().map(row->{row.put("hasFile",((Number)row.get("hasFile")).intValue()!=0);row.put("requiresAuthorization",true);return row;}).toList();
        return Api.ok(new Api.Page<>(items,result.total(),page,pageSize),request);
    }
    @GetMapping("/resources/categories") public Object resourceCategories(HttpServletRequest request){
        var categories=new LinkedHashSet<>(List.of("单片机","FPGA","物联网","嵌入式","机器人","人工智能","通用资料"));
        categories.addAll(store.db.queryForList("SELECT DISTINCT category FROM equipment WHERE active=TRUE AND category<>'' ORDER BY category",String.class));
        categories.addAll(store.db.queryForList("SELECT DISTINCT "+Content.RESOURCE_CATEGORY+" FROM resource_item r ORDER BY 1",String.class));
        return Api.ok(map("items",new ArrayList<>(categories)),request);
    }
    @GetMapping("/wishlist") public Object wishlist(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="9") int pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String category,
        @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String stage,HttpServletRequest request){
        String text=search(keyword,q);if(!category.isEmpty()&&!List.of(Wishlist.CATEGORIES).contains(category))throw Api.invalid("心愿分类无效");
        if(!status.isEmpty()&&!List.of("pending","approved").contains(status))throw Api.invalid("游客仅浏览待采购及已计划的公开心愿");
        if(!stage.isEmpty()&&!List.of("planned","stocked").contains(stage))throw Api.invalid("采购展示状态无效");
        String where=" WHERE w.status IN ('pending','approved')";var args=new ArrayList<Object>();
        if(!category.isEmpty()){where+=" AND w.category=?";args.add(category);}if(!status.isEmpty()){where+=" AND w.status=?";args.add(status);}
        if(stage.equals("planned"))where+=" AND w.status='approved' AND w.procurement_stage='none'";
        if(stage.equals("stocked"))where+=" AND w.procurement_stage='stocked'";
        if(!text.isBlank()){where+=" AND (w.name LIKE ? ESCAPE '!' OR w.model LIKE ? ESCAPE '!' OR w.description LIKE ? ESCAPE '!' OR w.function_description LIKE ? ESCAPE '!')";args.addAll(List.of(like(text),like(text),like(text),like(text)));}
        var result=business.page("SELECT w.id,w.name,w.model,w.category,w.description,w.function_description AS `function`,w.status,w.created_at,w.version,w.procurement_stage,w.stocked_equipment_id,w.stocked_at,(SELECT COUNT(*) FROM wish_support v WHERE v.wish_id=w.id) AS support_count",
            "FROM wish_item w",where,args,"w.created_at DESC,w.id DESC",page,pageSize);
        var items=result.items().stream().map(row->{row.put("publicStage","stocked".equals(row.get("procurementStage"))?"stocked":"approved".equals(row.get("status"))?"planned":"");return row;}).toList();
        return Api.ok(new Api.Page<>(items,result.total(),page,pageSize),request);
    }
    @GetMapping("/announcements") public Object announcements(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest request){
        return Api.ok(business.page("SELECT id,content,updated_at,version","FROM announcement"," WHERE enabled=TRUE",List.of(),"updated_at DESC,id DESC",page,pageSize),request);
    }
    @GetMapping("/duty-slots") public Object duties(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
        @RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,HttpServletRequest request){
        var snapshot=store.clock.instant().atZone(java.time.ZoneId.of("Asia/Shanghai"));var today=snapshot.toLocalDate();
        String where=" WHERE enabled=TRUE AND slot_date>=?";var args=new ArrayList<Object>();args.add(from==null?today:from);
        if(from==null&&to==null){where+=" AND (slot_date>? OR (slot_date=? AND end_time>?))";args.add(today);args.add(today);args.add(snapshot.toLocalTime());}
        if(to!=null){if(from!=null&&to.isBefore(from))throw Api.invalid("日期范围无效");where+=" AND slot_date<=?";args.add(to);}
        return Api.ok(business.page("SELECT id,slot_date AS date,start_time,end_time,location,enabled,version","FROM duty_slot",where,args,"slot_date,start_time,id",page,pageSize),request);
    }
    Map<String,Object> regulation(Map<String,Object> row){
        boolean available="application/pdf".equals(row.get("contentType"));String kind=row.get("kind").toString();
        return map("kind",kind,"title",row.get("title"),"available",available,"fileName",available?"public-regulation-"+kind+".pdf":null,
            "contentType",available?"application/pdf":null,"size",available?row.get("size"):null,"downloadUrl",available?"/api/public/regulations/"+kind+"/download":null,
            "updatedAt",row.get("updatedAt"),"version",row.get("version"),"allowedActions",List.of(),"textAvailable",((Number)row.get("textAvailable")).intValue()!=0,"textVersion",row.get("textVersion"),"textUpdatedAt",row.get("textUpdatedAt"));
    }
    @GetMapping("/regulations") public Object regulations(HttpServletRequest request){
        var rows=business.rows("SELECT d.kind,d.title,d.updated_at,d.version,f.content_type,f.size_bytes AS size,CASE WHEN t.body IS NULL THEN 0 ELSE 1 END AS text_available,COALESCE(t.version,0) AS text_version,t.updated_at AS text_updated_at FROM regulation_document d LEFT JOIN file_object f ON f.id=d.file_id LEFT JOIN regulation_text t ON t.kind=d.kind WHERE d.kind IN ('management','usage') ORDER BY d.kind");
        return Api.ok(map("items",rows.stream().map(this::regulation).toList()),request);
    }
    @GetMapping("/regulations/{kind}/download") public ResponseEntity<Resource> regulationFile(@PathVariable String kind){
        if(!Regulations.KINDS.contains(kind))throw Api.missing();
        var row=business.one("SELECT f.id,f.content_type,f.size_bytes,f.storage_path FROM regulation_document d JOIN file_object f ON f.id=d.file_id WHERE d.kind=? AND f.content_type='application/pdf'",kind);
        row.put("name","public-regulation-"+kind+".pdf");return media.response(row,false);
    }
}
