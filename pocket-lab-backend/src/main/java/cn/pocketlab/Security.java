package cn.pocketlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

@Configuration
public class Security {
    @Bean PasswordEncoder passwords() { return new PasswordEncoder() {
        final BCryptPasswordEncoder bcrypt=new BCryptPasswordEncoder(12);
        public String encode(CharSequence raw){return "{sha256}"+bcrypt.encode(digest(raw.toString()));}
        public boolean matches(CharSequence raw,String encoded){return encoded.startsWith("{sha256}")?bcrypt.matches(digest(raw.toString()),encoded.substring(8)):raw.toString().getBytes(StandardCharsets.UTF_8).length<=72 && bcrypt.matches(raw,encoded);}
    }; }
    @Bean SecurityFilterChain chain(HttpSecurity http, Store store, ObjectMapper json,
        @Value("${app.cors-origins}") String origins) throws Exception {
        var cors=new CorsConfiguration();
        cors.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList());
        cors.setAllowedMethods(List.of("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
        cors.setAllowedHeaders(List.of("Content-Type","Authorization","Idempotency-Key","If-None-Match"));
        cors.setExposedHeaders(List.of("X-Request-Id","ETag","Cache-Control"));
        cors.setAllowCredentials(false);
        var source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/api/**",cors);
        http.csrf(c->c.disable()).cors(c->c.configurationSource(source))
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(c->c.disable()).formLogin(c->c.disable()).httpBasic(c->c.disable())
            .authorizeHttpRequests(c->c.requestMatchers(org.springframework.http.HttpMethod.GET,
                "/api/public/equipment","/api/public/equipment/*","/api/public/labs","/api/public/labs/*","/api/public/labs/*/availability",
                "/api/public/works","/api/public/works/*","/api/public/files/*/content","/api/files/*/image",
                "/api/public/resources","/api/public/resources/categories","/api/public/wishlist","/api/public/announcements","/api/public/duty-slots","/api/public/regulations","/api/public/regulations/*/download","/api/public/regulations/*/content").permitAll().requestMatchers("/api/auth/preview-login","/api/auth/quick-login-config","/api/auth/login","/actuator/health","/actuator/health/readiness","/actuator/health/liveness").permitAll().anyRequest().authenticated())
            .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->write(json,req,res,Api.unauthorized()))
                .accessDeniedHandler((req,res,e)->write(json,req,res,Api.forbidden())))
            .addFilterBefore(new BearerFilter(store,json),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void write(ObjectMapper json,HttpServletRequest req,HttpServletResponse res,Api.Failure f) throws IOException {
        res.setStatus(f.status);res.setContentType("application/json;charset=UTF-8");
        json.writeValue(res.getWriter(),new Api.Envelope<>(f.code,f.getMessage(),null,(String)req.getAttribute("requestId")));
    }
    static class BearerFilter extends OncePerRequestFilter {
        final Store s; final ObjectMapper json;
        BearerFilter(Store s,ObjectMapper json){this.s=s;this.json=json;}
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
            String auth=req.getHeader("Authorization");
            if(auth==null && req.getMethod().equals("GET") && req.getRequestURI().matches("/api/showcase/files/[A-Za-z0-9-]+")) {
                String access=req.getParameter("access"),file=req.getRequestURI().substring(req.getRequestURI().lastIndexOf('/')+1);
                if(access!=null&&access.matches("[A-Za-z0-9_-]{43}")){
                    var permitted=s.db.query("SELECT u.*,a.view_mode FROM media_ticket t JOIN auth_session a ON a.token_hash=t.session_hash JOIN lab_user u ON u.id=a.user_id WHERE t.token_hash=? AND t.file_id=? AND t.expires_at>? AND a.expires_at>? AND u.enabled=TRUE AND u.deleted_at IS NULL",(r,n)->Store.USER.mapRow(r,n).view(r.getString("view_mode")),digest(access),file,s.now(),s.now());
                    if(!permitted.isEmpty()){var u=permitted.getFirst();SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority(u.role()))));}
                }
            }
            if(auth!=null&&!req.getRequestURI().equals("/api/auth/login")) {
                if(!auth.matches("Bearer [A-Za-z0-9_-]{43}")) { write(json,req,res,Api.unauthorized());return; }
                var users=s.db.query("SELECT u.*,a.view_mode FROM lab_user u JOIN auth_session a ON a.user_id=u.id WHERE a.token_hash=? AND a.expires_at>? AND u.enabled=TRUE AND u.deleted_at IS NULL",(r,n)->Store.USER.mapRow(r,n).view(r.getString("view_mode")),digest(auth.substring(7)),s.now());
                if(users.isEmpty()){write(json,req,res,Api.unauthorized());return;}
                var u=users.getFirst();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority(u.role()))));
            }
            chain.doFilter(req,res);
        }
    }
    @Component @Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    static class RequestIdFilter extends OncePerRequestFilter {
        @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
            String id=UUID.randomUUID().toString();req.setAttribute("requestId",id);res.setHeader("X-Request-Id",id);
            res.setHeader("Cache-Control","no-store");
            org.slf4j.MDC.put("requestId",id);
            try{chain.doFilter(req,res);}finally{org.slf4j.MDC.remove("requestId");}
        }
    }
}
