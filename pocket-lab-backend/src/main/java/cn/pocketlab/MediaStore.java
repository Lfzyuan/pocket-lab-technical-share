package cn.pocketlab;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import static cn.pocketlab.Business.map;

@Component
public class MediaStore implements ApplicationRunner {
 final Store s;final Business b;final Path root;
 static final long IMAGE_LIMIT=30L*1024*1024,LARGE_LIMIT=5L*1024*1024*1024;
 MediaStore(Store s,Business b,@Value("${app.storage-root:${java.io.tmpdir}/pocket-lab-media}")String directory){this.s=s;this.b=b;this.root=Path.of(directory).toAbsolutePath().normalize();}
 public void run(ApplicationArguments args)throws Exception{
  java.nio.file.Files.createDirectories(root);
  // Migrate legacy blobs once, retaining file IDs and all existing references.
  for(String id:s.db.queryForList("SELECT id FROM file_object WHERE storage_path IS NULL",String.class)){
   byte[] data=s.db.queryForObject("SELECT file_data FROM file_object WHERE id=?",byte[].class,id);if(data==null)throw new IllegalStateException("Missing legacy file "+id);
   Path destination=root.resolve(id);java.nio.file.Files.write(destination,data);
   s.db.update("UPDATE file_object SET storage_path=?,file_data=NULL WHERE id=?",id,id);
  }
 }
 String type(String name,byte[] head){String lower=name.toLowerCase(Locale.ROOT);String hex=HexFormat.of().formatHex(head);
  if(lower.endsWith(".webm")&&hex.startsWith("1a45dfa3"))return "video/webm";
  if(lower.endsWith(".mov")&&hex.length()>=16&&hex.substring(8,16).equals("66747970"))return "video/quicktime";
  if((lower.endsWith(".docx")||lower.endsWith(".pptx"))&&hex.startsWith("504b0304"))return lower.endsWith(".docx")?"application/vnd.openxmlformats-officedocument.wordprocessingml.document":"application/vnd.openxmlformats-officedocument.presentationml.presentation";
  if((lower.endsWith(".doc")||lower.endsWith(".ppt"))&&hex.startsWith("d0cf11e0a1b11ae1"))return lower.endsWith(".doc")?"application/msword":"application/vnd.ms-powerpoint";
  return Files.type(name,head);
 }
 Map<String,Object> upload(Api.User actor,String name,String kind,long declared,InputStream source)throws Exception{
  if(name==null||name.isBlank()||name.length()>180||name.contains("/")||name.contains("\\")||name.chars().anyMatch(Character::isISOControl))throw Api.invalid("文件名无效");
  if(!Set.of("cover","media","attachment","legacy").contains(kind))throw Api.invalid("文件分类无效");
  boolean imageName=name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)$");
  long advertisedLimit=imageName?IMAGE_LIMIT:LARGE_LIMIT;
  if(declared>=advertisedLimit)throw new Api.Failure(413,"FILE_TOO_LARGE",imageName?"图片须小于30MB":"视频和附件须小于5GB");
  java.nio.file.Files.createDirectories(root);String id=Store.id();Path temporary=root.resolve(id+".part"),destination=root.resolve(id);long size=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");String type;
  try(InputStream in=source){byte[] first=in.readNBytes(12);type=type(name,first);boolean image=type.startsWith("image/"),video=type.startsWith("video/");
   if(kind.equals("cover")&&!image||kind.equals("media")&&!image&&!video||kind.equals("attachment")&&(image||video))throw Api.invalid("请按照片/视频和附件分类上传");
   long limit=image?IMAGE_LIMIT:LARGE_LIMIT;if(declared>=limit)throw new Api.Failure(413,"FILE_TOO_LARGE",image?"图片须小于30MB":"视频和附件须小于5GB");
   try(OutputStream out=java.nio.file.Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW)){out.write(first);digest.update(first);size=first.length;byte[] block=new byte[128*1024];int n;while((n=in.read(block))!=-1){size+=n;if(size>=limit)throw new Api.Failure(413,"FILE_TOO_LARGE",image?"图片须小于30MB":"视频和附件须小于5GB");out.write(block,0,n);digest.update(block,0,n);}}
   if(size==0)throw Api.invalid("空文件不可上传");java.nio.file.Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE);
   s.db.update("INSERT INTO file_object(id,owner_id,name,content_type,size_bytes,sha256,storage_path,file_data,created_at,media_kind) VALUES(?,?,?,?,?,?,?,NULL,?,?)",id,actor.id(),name,type,size,HexFormat.of().formatHex(digest.digest()),id,s.now(),kind);
  }catch(Exception e){java.nio.file.Files.deleteIfExists(temporary);java.nio.file.Files.deleteIfExists(destination);throw e;}
  return map("id",id,"name",name,"kind",kind,"type",type,"contentType",type,"size",size,"url","/api/files/"+id+"/content","requiresAuthorization",true);
 }
 Map<String,Object> file(Api.User actor,String id){var row=b.one("SELECT id,owner_id,name,content_type,size_bytes,storage_path FROM file_object WHERE id=?",id);
  if(!actor.id().equals(row.get("ownerId"))&&!actor.accountRole().equals("admin")){
   long refs=s.db.queryForObject("SELECT COUNT(*) FROM resource_item WHERE file_id=?",Long.class,id)+s.db.queryForObject("SELECT COUNT(*) FROM work_item WHERE (file_id=? OR cover_id=? OR id IN(SELECT work_id FROM work_file WHERE file_id=?)) AND (status='published' OR user_id=?)",Long.class,id,id,id,actor.id());if(refs==0)throw Api.missing();
  }return row;
 }
 ResponseEntity<Resource> response(Map<String,Object> row,boolean inline){Path p=root.resolve(Objects.toString(row.get("storagePath"),"")).normalize();if(!p.startsWith(root)||!java.nio.file.Files.isRegularFile(p))throw Api.missing();return ResponseEntity.ok().contentType(MediaType.parseMediaType(row.get("contentType").toString())).contentLength(((Number)row.get("sizeBytes")).longValue()).header("Content-Disposition",(inline?ContentDisposition.inline():ContentDisposition.attachment()).filename(row.get("name").toString(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).header("Accept-Ranges","bytes").header("X-Content-Type-Options","nosniff").header("Cache-Control","private, no-store").header("Referrer-Policy","no-referrer").body(new FileSystemResource(p));}
}
