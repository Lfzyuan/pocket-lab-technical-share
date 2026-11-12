package cn.pocketlab;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api/showcase")
public class Showcase {
 final Store s;final Business b;final MediaStore media;final TeacherDirectory teachers;
 Showcase(Store s,Business b,MediaStore media,TeacherDirectory teachers){this.s=s;this.b=b;this.media=media;this.teachers=teachers;}
 Map<String,Object> present(Map<String,Object> row,Api.User actor,boolean audit){String id=row.get("id").toString();boolean anon=Boolean.TRUE.equals(row.get("anonymous"));
  var result=map("id",id,"name",row.get("name"),"desc",row.get("description"),"category",row.get("category"),"equipmentId",row.get("equipmentId"),"coverId",row.get("coverId"),"author",anon?"匿名作者":row.get("author"),"className",anon?"":row.get("authorClass"),"anonymous",anon,"status",row.get("status"),"reason",row.get("reason"),"createdAt",row.get("createdAt"),"version",row.get("version"),"externalMedia",row.get("media"));
  result.put("location",row.get("location"));result.put("contactPublic",!anon&&Boolean.TRUE.equals(row.get("contactPublic")));
  if(!anon){result.put("authorAccount",Objects.toString(row.get("authorAccount"),""));result.put("teacherName",Objects.toString(row.get("teacherName"),""));if(Boolean.TRUE.equals(row.get("contactPublic")))result.put("authorContact",Objects.toString(row.get("authorContact"),""));}
  if(audit&&(actor.id().equals(row.get("userId"))||actor.accountRole().equals("admin")))result.put("teacherUserId",row.get("teacherUserId"));
  if(audit&&actor.accountRole().equals("admin"))result.put("submissionIdentity",map("name",row.get("author"),"account",Objects.toString(row.get("authorAccount"),""),"className",row.get("authorClass"),"teacherName",Objects.toString(row.get("teacherName"),""),"contact",Objects.toString(row.get("authorContact"),"")));
  var files=b.rows("SELECT f.id,f.name,f.content_type AS type,f.size_bytes AS size,w.kind FROM work_file w JOIN file_object f ON f.id=w.file_id WHERE w.work_id=? ORDER BY w.position",id);
  result.put("mediaFiles",files.stream().filter(f->f.get("kind").equals("media")).toList());var attachments=new ArrayList<>(files.stream().filter(f->f.get("kind").equals("attachment")).toList());
  if(row.get("fileId")!=null)attachments.addAll(b.rows("SELECT id,name,content_type AS type,size_bytes AS size FROM file_object WHERE id=?",row.get("fileId")));result.put("attachments",attachments);
  if(row.get("coverId")==null&&row.get("equipmentId")!=null){var images=s.db.queryForList("SELECT image FROM equipment WHERE id=?",String.class,row.get("equipmentId"));if(!images.isEmpty())result.put("image",images.getFirst());}
  return result;
 }
 @GetMapping public Object list(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="false") boolean mine,HttpServletRequest req){String where=mine?(actor.accountRole().equals("admin")?" WHERE is_submission=TRUE":" WHERE user_id=?"):" WHERE status='published'";return Api.ok(b.rows("SELECT * FROM work_item"+where+" ORDER BY created_at DESC,id DESC",mine&&!actor.accountRole().equals("admin")?new Object[]{actor.id()}:new Object[]{}).stream().map(row->present(row,actor,mine)).toList(),req);}
 @PostMapping("/files") public Object upload(@AuthenticationPrincipal Api.User actor,@RequestParam String name,@RequestParam String kind,HttpServletRequest req)throws Exception{return Api.ok(media.upload(actor,name,kind,req.getContentLengthLong(),req.getInputStream()),req);}
 @GetMapping("/files/{id}/access") public Object access(@AuthenticationPrincipal Api.User actor,@PathVariable String id,HttpServletRequest req){media.file(actor,id);byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  s.db.update("DELETE FROM media_ticket WHERE expires_at<?",s.now());s.db.update("INSERT INTO media_ticket(token_hash,file_id,session_hash,expires_at) VALUES(?,?,?,?)",Security.digest(token),id,Security.digest(req.getHeader("Authorization").substring(7)),Timestamp.from(s.clock.instant().plusSeconds(8*3600)));return Api.ok(map("url","/api/showcase/files/"+id+"?access="+token),req);
 }
 @GetMapping("/files/{id}") public ResponseEntity<Resource> file(@AuthenticationPrincipal Api.User actor,@PathVariable String id){var row=media.file(actor,id);return media.response(row,row.get("contentType").toString().matches("^(image|video)/.*"));}
 String owned(Api.User actor,String id,String kind){var file=b.one("SELECT owner_id,media_kind FROM file_object WHERE id=?",id);if(!actor.id().equals(file.get("ownerId"))||!kind.equals(file.get("mediaKind")))throw Api.invalid("上传文件无效或分类不符");return id;}
 @PostMapping @Transactional public Object create(@AuthenticationPrincipal Api.User actor,@RequestBody JsonNode body,HttpServletRequest req){
  s.lockUser(actor.id());var p=new Input(body,"name","desc","category","equipmentId","anonymous","coverId","mediaIds","attachmentIds","teacherName","teacherUserId","authorClassName","authorContact","contactPublic","location");String name=p.text("name",20,true),desc=p.text("desc",200,true),category=p.text("category",80,false),equipment=p.text("equipmentId",36,false);if(!equipment.isBlank())s.equipment(equipment,false);
  Business.submissionPrivacy(body);boolean anon=body.path("anonymous").booleanValue();
  var photos=body.path("mediaIds");var attachments=body.path("attachmentIds");if(!photos.isArray()||photos.isEmpty()||photos.size()>12||!attachments.isArray()||attachments.size()>5)throw Api.invalid("需上传1–12个照片或视频，附件最多5个");String cover=owned(actor,p.text("coverId",36,true),"cover");
  var teacher=teachers.optional(p,null);
  String id=Store.id();s.db.update("INSERT INTO work_item(id,user_id,author,author_class,author_account,teacher_name,teacher_user_id,author_contact,contact_public,anonymous,name,description,category,equipment_id,location,media,cover_id,status,is_submission,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'',?,'pending',TRUE,?)",id,actor.id(),actor.name(),p.has("authorClassName")?p.text("authorClassName",100,false):actor.className(),actor.account(),teacher.name(),teacher.id(),p.text("authorContact",200,false),p.has("contactPublic")&&p.bool("contactPublic"),anon,name,desc,category,equipment.isEmpty()?null:equipment,p.text("location",200,false),cover,s.now());
  int pos=0;Set<String> distinct=new HashSet<>();for(String kind:List.of("media","attachment"))for(JsonNode n:body.path(kind.equals("media")?"mediaIds":"attachmentIds")){String file=owned(actor,n.asText(),kind);if(!distinct.add(file))throw Api.invalid("请勿重复选择同一文件");s.db.update("INSERT INTO work_file(work_id,file_id,kind,position) VALUES(?,?,?,?)",id,file,kind,pos++);}
  var result=present(b.one("SELECT * FROM work_item WHERE id=?",id),actor,false);
  result.put("teacherUserId",teacher.id());return Api.ok(result,req);
 }
 @PostMapping("/{id}/review") @Transactional public Object review(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,HttpServletRequest req){Store.role(s.lockUser(actor.id()),"admin");var row=b.one("SELECT * FROM work_item WHERE id=? FOR UPDATE",id);if(!row.get("status").equals("pending"))throw Api.conflict("作品已处理，请刷新");var p=new Input(body,"status","reason");String status=p.choice("status","published","rejected"),reason=p.text("reason",500,status.equals("rejected"));s.db.update("UPDATE work_item SET status=?,reason=?,reviewed_at=?,version=version+1 WHERE id=?",status,reason,s.now(),id);b.notify(row.get("userId").toString(),"作品审核结果",row.get("name")+"："+(status.equals("published")?"已公开展示":reason),"showcase:"+id,id,null);return Api.ok(present(b.one("SELECT * FROM work_item WHERE id=?",id),actor,true),req);}
}
