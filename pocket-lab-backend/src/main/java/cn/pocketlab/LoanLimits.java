package cn.pocketlab;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api")
public class LoanLimits {
    final Store store;final Business business;final boolean mysql;
    LoanLimits(Store store,Business business){
        this.store=store;this.business=business;
        this.mysql=Boolean.TRUE.equals(store.db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection->{
            String product=connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT);
            return product.contains("mysql")||product.contains("mariadb");
        }));
    }
    static void lockUsers(Store store,String... ids){Arrays.stream(ids).distinct().sorted().forEach(id->{
        if(store.db.queryForList("SELECT id FROM lab_user WHERE id=? FOR UPDATE",String.class,id).isEmpty())throw Api.missing();
    });}
    long active(String userId){
        // A normal SELECT/COUNT can retain a pre-lock REPEATABLE READ snapshot on MySQL.
        // Current locking reads are required even after the borrower's user row was locked.
        // Keep scans within that owner's existing index range rather than other borrowers' loans.
        String normalHint=mysql?" FORCE INDEX (ix_borrow_user)":"";
        String otherHint=mysql?" FORCE INDEX (ix_other_borrow_owner)":"";
        var normal=store.db.queryForList("SELECT id FROM borrow_request"+normalHint+" WHERE user_id=? AND status IN ('borrowed','returning') ORDER BY id FOR UPDATE",String.class,userId);
        var legacy=store.db.queryForList("SELECT id FROM other_borrow_request"+otherHint+" WHERE user_id=? AND workflow_mode IN ('legacy','checkout') AND status IN ('borrowed','returning') ORDER BY id FOR UPDATE",String.class,userId);
        return (long)normal.size()+legacy.size();
    }
    void requireCapacity(String userId){
        var user=store.db.query("SELECT * FROM lab_user WHERE id=? FOR UPDATE",Store.USER,userId).stream().findFirst().orElseThrow(Api::missing);
        if(!user.enabled())throw Api.conflict("借用人账号已停用，无法办理新的借出");
        int limit=user.borrowLimit();long active=active(userId);
        if(active>=limit)throw new Api.Failure(409,"BORROW_LIMIT","当前有"+active+"件未归还器材，已达到借用限额"+limit+"件；特殊需求请联系管理员调整");
    }
    @PatchMapping("/users/{id}/borrow-limit") @Transactional
    public Api.Envelope<?> change(@AuthenticationPrincipal Api.User actor,@PathVariable String id,@RequestBody JsonNode body,
        @RequestHeader("Idempotency-Key") String key,HttpServletRequest request){
        Store.role(actor,"admin");lockUsers(store,actor.id(),id);
        return Api.ok(business.write(actor,key,"PATCH /users/"+id+"/borrow-limit",body,()->{
            Store.role(store.user(actor.id()),"admin");var input=new Business.Input(body,"limit","reason");
            int limit=(int)input.number("limit",1,100);String reason=input.text("reason",500,true);var user=store.user(id);
            store.db.update("UPDATE lab_user SET borrow_limit=? WHERE id=?",limit,id);
            store.db.update("INSERT INTO borrow_limit_event(id,user_id,old_limit,new_limit,reason,actor_id,created_at) VALUES(?,?,?,?,?,?,?)",
                Store.id(),id,user.borrowLimit(),limit,reason,actor.id(),store.now());return store.user(id);
        }),request);
    }
}
