package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Retired in every profile. Existing accounts/passwords are deliberately left unchanged. */
@RestController @RequestMapping("/api/auth")
public class QuickLogin {
    @GetMapping("/quick-login-config") public Object config(HttpServletRequest request){return Api.ok(Business.map("enabled",false),request);}
    @PostMapping("/preview-login") public Object login(){throw Api.missing();}
}
