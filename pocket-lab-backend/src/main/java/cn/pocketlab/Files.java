package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static cn.pocketlab.Business.*;

/** File metadata lives in MySQL; binary content is streamed from the data disk. */
@RestController @RequestMapping("/api/files")
public class Files {
    final Store s;final Business b;final MediaStore media;
    Files(Store s,Business b,MediaStore media){this.s=s;this.b=b;this.media=media;}
    static final long MAX=10*1024*1024;
    static final Set<String> IMAGE_TYPES=Set.of("image/png","image/jpeg","image/webp");
    /** Only images explicitly published as equipment photos are accessible without a bearer token. */
    @GetMapping("/{id}/image")
    public ResponseEntity<org.springframework.core.io.Resource> equipmentImage(@PathVariable String id,@AuthenticationPrincipal Api.User actor){
        var row=b.one("SELECT f.* FROM file_object f WHERE f.id=? AND EXISTS(SELECT 1 FROM equipment e WHERE e.image=?"+(actor==null?" AND e.active=TRUE":"")+")",id,"/api/files/"+id+"/image");
        if(actor==null)row.put("name",PublicBrowse.filename(row.get("contentType").toString()));
        if(!IMAGE_TYPES.contains(row.get("contentType")))throw Api.missing();return media.response(row,true);
    }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Api.Envelope<Object> upload(@AuthenticationPrincipal Api.User actor,@RequestParam("file") MultipartFile file,@RequestHeader("Idempotency-Key") String key,HttpServletRequest req) throws Exception {
        return Api.ok(media.upload(actor,file.getOriginalFilename(),"legacy",file.getSize(),file.getInputStream()),req);
    }
    static String type(String name,byte[] bytes){String lower=name.toLowerCase(Locale.ROOT),magic=HexFormat.of().formatHex(Arrays.copyOf(bytes,Math.min(12,bytes.length)));
        if(lower.endsWith(".pdf")&&magic.startsWith("255044462d"))return "application/pdf";
        if(lower.endsWith(".png")&&magic.startsWith("89504e470d0a1a0a"))return "image/png";
        if((lower.endsWith(".jpg")||lower.endsWith(".jpeg"))&&magic.startsWith("ffd8ff"))return "image/jpeg";
        if(lower.endsWith(".webp")&&magic.startsWith("52494646")&&magic.substring(16).equals("57454250"))return "image/webp";
        if(lower.endsWith(".zip")&&(magic.startsWith("504b0304")||magic.startsWith("504b0506")))return "application/zip";
        if(lower.endsWith(".mp4")&&magic.length()>=16&&magic.substring(8,16).equals("66747970"))return "video/mp4";
        if(lower.endsWith(".txt")||lower.endsWith(".csv")){try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(text.indexOf(0)>=0)throw new Exception();return lower.endsWith(".csv")?"text/csv":"text/plain";}catch(Exception e){throw Api.invalid("文本文件须为UTF-8编码");}}
        throw Api.invalid("文件类型或内容不符合约定，支持PDF、PNG、JPEG、WebP、ZIP、MP4、UTF-8 TXT/CSV");
    }
    @GetMapping("/{id}/content")
    public ResponseEntity<org.springframework.core.io.Resource> download(@AuthenticationPrincipal Api.User actor,@PathVariable String id){return media.response(media.file(actor,id),false);}
}
