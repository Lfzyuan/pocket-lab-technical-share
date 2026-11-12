package cn.pocketlab;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class Accounts {
    private final Store s;
    private final PasswordEncoder passwords;
    private final long ttl;
    private final LoginClientAddress clientAddress;
    private final String dummyHash;
    private final LinkedHashMap<String,long[]> attempts=new LinkedHashMap<>();
    Accounts(Store s,PasswordEncoder passwords,@Value("${app.token-seconds}") long ttl,LoginClientAddress clientAddress) {
        this.s=s;this.passwords=passwords;this.ttl=ttl;this.clientAddress=clientAddress;this.dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    // Single-instance, bounded limiter; do not trust X-Forwarded-For from callers.
    synchronized void limit(String address) {
        long minute=s.clock.instant().getEpochSecond()/60;
        long[] a=attempts.computeIfAbsent(address,k->new long[]{minute,0});
        if(a[0]!=minute){a[0]=minute;a[1]=0;}
        if(++a[1]>20) throw new Api.Failure(429,"RATE_LIMITED","登录请求过于频繁，请稍后重试");
        while(attempts.size()>10000) attempts.remove(attempts.keySet().iterator().next());
    }
    @PostMapping("/auth/login") @Transactional
    public Api.Envelope<Api.LoginResult> login(@Valid @RequestBody Api.Login input,HttpServletRequest req) {
        limit(clientAddress.resolve(req));
        var rows=s.db.query("SELECT * FROM lab_user WHERE account=? FOR UPDATE",Store.USER,input.account());
        String hash=rows.isEmpty()?dummyHash:s.db.queryForObject("SELECT password_hash FROM lab_user WHERE id=?",String.class,rows.getFirst().id());
        boolean correct=passwords.matches(input.password(),hash);
        if(!correct||rows.isEmpty()||!rows.getFirst().enabled())throw new Api.Failure(401,"UNAUTHORIZED","账号或密码错误，或账号已停用");
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var u=rows.getFirst();
        s.db.update("INSERT INTO auth_session(token_hash,user_id,expires_at,created_at) VALUES(?,?,?,?)",Security.digest(token),u.id(),Timestamp.from(s.clock.instant().plusSeconds(ttl)),s.now());
        return Api.ok(new Api.LoginResult(token,ttl,u),req);
    }
    @PostMapping("/auth/logout")
    public Api.Envelope<Void> logout(HttpServletRequest req) {
        s.db.update("DELETE FROM auth_session WHERE token_hash=?",Security.digest(req.getHeader("Authorization").substring(7)));
        return Api.ok(null,req);
    }
    @GetMapping("/me")
    public Api.Envelope<Api.User> me(@AuthenticationPrincipal Api.User user,HttpServletRequest req) { return Api.ok(user,req); }
    @PatchMapping("/me") @Transactional
    public Api.Envelope<Api.User> profile(@AuthenticationPrincipal Api.User user,@Valid @RequestBody Api.Profile p,HttpServletRequest req) {
        var current=s.lockUser(user.id());
        s.db.update("UPDATE lab_user SET class_name=?,phone=?,email=?,grade=? WHERE id=?",p.className()==null?current.className():p.className().trim(),p.phone()==null?current.phone():p.phone().trim(),p.email()==null?current.email():p.email().trim(),p.grade()==null?current.grade():p.grade().trim(),user.id());
        return Api.ok(s.user(user.id()),req);
    }
    @PostMapping("/users") @Transactional
    public Api.Envelope<Api.User> create(@AuthenticationPrincipal Api.User actor,@Valid @RequestBody Api.UserCreate p,HttpServletRequest req) {
        Store.role(s.lockUser(actor.id()),"admin");
        AccountCohorts.lock(s);
        String type=AccountPolicy.creationType(p.role());AccountPolicy.account(type,p.account());AccountPolicy.creationPassword(type,p.password());
        String id=Store.id(),displayClass=p.className()==null?"":p.className().trim();
        s.db.update("INSERT INTO lab_user(id,account,name,class_name,pickup_class_name,role,account_type,can_manage,password_hash,created_at,phone) VALUES(?,?,?,?,?,?,?,FALSE,?,?,?)",id,p.account(),p.name().trim(),displayClass,AccountPolicy.trustedClass(type,displayClass),type.equals("admin")?"admin":"student",type,passwords.encode(p.password()),s.now(),p.phone()==null?"":p.phone());
        return Api.ok(s.user(id),req);
    }
    @GetMapping("/users")
    public Api.Envelope<Api.Page<AdminUser>> users(@AuthenticationPrincipal Api.User actor,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,
        @RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="") String role,@RequestParam(defaultValue="") String q,@RequestParam(required=false) Boolean enabled,@RequestParam(defaultValue="") String accountType,HttpServletRequest req) {
        Store.role(actor,"admin");Store.pagination(page,pageSize);
        keyword=Business.search(keyword,q);
        if(keyword.length()>100||(!role.isEmpty()&&!List.of("student","reviewer","admin").contains(role)))throw Api.invalid("账号筛选条件无效");
        String where=" WHERE deleted_at IS NULL";List<Object> args=new ArrayList<>();
        if(!keyword.isBlank()) {
            String match="%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
            where+=" AND (account LIKE ? ESCAPE '!' OR name LIKE ? ESCAPE '!')";args.add(match);args.add(match);
        }
        if(!role.isEmpty()){where+=" AND role=?";args.add(role);}
        if(!accountType.isEmpty()){AccountPolicy.creationType(accountType);where+=" AND ("+AccountPolicy.TYPE_SQL+")=?";args.add(accountType);}
        if(enabled!=null){where+=" AND enabled=?";args.add(enabled);}
        long total=s.db.queryForObject("SELECT COUNT(*) FROM lab_user"+where,Long.class,args.toArray());
        args.add(pageSize);args.add((page-1)*pageSize);
        var items=s.db.query("SELECT * FROM lab_user"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(r,n)->adminUser(Store.USER.mapRow(r,n),r.getString("pickup_class_name")),args.toArray());
        return Api.ok(new Api.Page<>(items,total,page,pageSize),req);
    }
    record AdminUser(@JsonUnwrapped Api.User user,String officialClassName,String classCohort) {}
    private static AdminUser adminUser(Api.User user,String trustedClass){
        String officialClass="student".equals(user.accountType())?AccountCohorts.official(trustedClass):"";
        return new AdminUser(user,officialClass,AccountCohorts.year(officialClass));
    }
    record AdminProfile(@NotBlank @Size(max=80) String name,
                        @NotNull @Size(max=100) String className,
                        @NotNull @Size(max=32) @Pattern(regexp="[0-9+() -]*") String phone,
                        @NotNull @Size(max=200) @Email String email) {}

    @PatchMapping("/users/{id}/profile") @Transactional
    public Api.Envelope<AdminUser> editProfile(@AuthenticationPrincipal Api.User actor,
        @PathVariable String id,@Valid @RequestBody AdminProfile input,HttpServletRequest req){
        Store.role(s.lockUser(actor.id()),"admin");
        AccountCohorts.lock(s);
        var targets=s.db.query("SELECT * FROM lab_user WHERE id=? AND deleted_at IS NULL FOR UPDATE",Store.USER,id);
        var target=targets.stream().findFirst().orElseThrow(Api::missing);
        if("admin".equals(target.accountRole()))throw Api.invalid("管理员账号受保护");
        String group=input.className().strip(),officialClass=AccountPolicy.trustedClass(target.accountType(),group);
        s.db.update("UPDATE lab_user SET name=?,class_name=?,pickup_class_name=?,phone=?,email=? WHERE id=?",
            input.name().strip(),group,officialClass,input.phone().strip(),input.email().strip(),id);
        return Api.ok(adminUser(s.user(id),officialClass),req);
    }

    @RequestMapping(value="/users/{id}/enabled",method={RequestMethod.PATCH,RequestMethod.POST}) @Transactional
    public Api.Envelope<Api.User> enabled(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@Valid @RequestBody Api.Enabled p,HttpServletRequest req) {
        Store.role(s.lockUser(actor.id()),"admin");
        if(s.user(id).accountRole().equals("admin"))throw Api.invalid("管理员账号受保护");
        if(s.db.update("UPDATE lab_user SET enabled=? WHERE id=? AND deleted_at IS NULL",p.enabled(),id)==0)throw Api.missing();
        if(!p.enabled())s.db.update("DELETE FROM auth_session WHERE user_id=?",id);
        return Api.ok(s.user(id),req);
    }
    @Scheduled(fixedDelay=3600000)
    public void cleanup() {
        s.db.update("DELETE FROM auth_session WHERE expires_at<?",s.now());
        s.db.update("DELETE FROM idempotency_record WHERE expires_at<?",s.now());
    }
    @Component
    static class Bootstrap implements ApplicationRunner {
        final Store s; final PasswordEncoder passwords;final String account,password;
        Bootstrap(Store s,PasswordEncoder passwords,@Value("${app.bootstrap.account}") String account,@Value("${app.bootstrap.password}") String password){this.s=s;this.passwords=passwords;this.account=account;this.password=password;}
        @Override @Transactional public void run(ApplicationArguments args) {
            if(account.isBlank()&&password.isBlank())return;
            if(!account.matches("[A-Za-z0-9_-]{2,32}")||password.length()<12||password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)throw new IllegalStateException("Bootstrap admin configuration is invalid");
            if(s.db.queryForObject("SELECT COUNT(*) FROM lab_user",Long.class)>0)return;
            s.db.update("INSERT INTO lab_user(id,account,name,role,account_type,password_hash,created_at) VALUES(?,?,?,?,'admin',?,?)",Store.id(),account,"实验室管理员","admin",passwords.encode(password),s.now());
        }
    }
}
