package cn.pocketlab;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class PickupExpiry {
    final Store store;final Business business;final TransactionTemplate transactions;
    PickupExpiry(Store store,Business business,TransactionTemplate transactions){this.store=store;this.business=business;this.transactions=transactions;}
    public void cancelExpired(){
        var ids=store.db.queryForList("SELECT id FROM borrow_request WHERE status='approved' AND pickup_deadline_at IS NOT NULL AND pickup_deadline_at<? ORDER BY pickup_deadline_at,id",String.class,store.now());
        for(String id:ids)transactions.executeWithoutResult(status->cancelOne(id));
    }
    private void cancelOne(String id){
        var initial=business.rows("SELECT equipment_id,user_id,pickup_proxy_id FROM borrow_request WHERE id=?",id);if(initial.isEmpty())return;
        var snapshot=initial.getFirst();
        java.util.stream.Stream.of(snapshot.get("userId"),snapshot.get("pickupProxyId")).filter(Objects::nonNull).map(Object::toString).distinct().sorted()
            .forEach(userId->store.db.queryForObject("SELECT id FROM lab_user WHERE id=? FOR UPDATE",String.class,userId));
        var equipment=store.equipment(snapshot.get("equipmentId").toString(),true);
        var rows=business.rows("SELECT id,user_id,status,pickup_proxy_id,pickup_deadline_at FROM borrow_request WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty())return;var row=rows.getFirst();var deadline=(OffsetDateTime)row.get("pickupDeadlineAt");
        if(!Objects.equals(row.get("status"),"approved")||deadline==null||!deadline.toInstant().isBefore(store.clock.instant()))return;
        if(store.db.update("UPDATE equipment SET reserved=reserved-1,version=version+1 WHERE id=? AND reserved>0",equipment.id())!=1)
            throw Api.conflict("预留库存异常，无法自动取消预约");
        store.db.update("UPDATE borrow_request SET status='cancelled',active_slot=NULL,version=version+1 WHERE id=?",id);
        String reason="获批后10个工作日仍未领取，预约已自动取消；如有需要请重新申请。";
        store.event(id,"cancel","approved","cancelled",null,reason);
        if(row.get("pickupProxyId")!=null)business.notify(row.get("pickupProxyId").toString(),"代领预约已逾期取消",reason,"pickup-expiry-proxy:"+id,id,equipment.id());
    }
}

@Component
@ConditionalOnProperty(name="pocket-lab.pickup-expiry.enabled",havingValue="true",matchIfMissing=true)
class PickupExpirySchedule {
    final PickupExpiry expiry;
    PickupExpirySchedule(PickupExpiry expiry){this.expiry=expiry;}
    @Scheduled(initialDelay=60000,fixedDelay=60000) public void run(){expiry.cancelExpired();}
}
