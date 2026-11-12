package cn.pocketlab;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
public class Contract {
    @GetMapping(value="/api/openapi.json",produces=MediaType.APPLICATION_JSON_VALUE)
    public byte[] openapi() throws IOException {
        try(var stream=new ClassPathResource("openapi.json").getInputStream()){return stream.readAllBytes();}
    }
}
