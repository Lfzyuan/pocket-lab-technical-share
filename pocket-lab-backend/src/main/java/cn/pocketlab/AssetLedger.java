package cn.pocketlab;

import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** One asset number can belong to at most one active checkout, across both loan flows. */
@Component
final class AssetLedger {
    private final Store store;

    AssetLedger(Store store) { this.store=store; }

    static String number(String raw) {
        if(raw==null||raw.isBlank())return null;
        if(raw.length()>100||!raw.equals(raw.trim())||raw.chars().anyMatch(Character::isISOControl))
            throw Api.invalid("资产编号最多100个字符，且不能包含首尾空格或控制字符");
        return raw;
    }

    void claim(String number,String equipmentId,String borrowId,String otherBorrowId) {
        if(number==null)return;
        // Insert or lock the unique key in one statement. An absent-row locking read alone
        // can race with a simultaneous checkout that first sees the same barcode.
        store.db.update("INSERT INTO asset_unit(asset_number,status) VALUES(?,'available') ON DUPLICATE KEY UPDATE asset_number=asset_number",number);
        var row=locked(number);
        requireAvailable(row,equipmentId);
        if(equipmentId!=null&&row.get("equipment_id")==null) {
            var equipment=store.equipment(equipmentId,true);
            if(activeCount(equipmentId,true)>=equipment.stock())throw Api.conflict("当前库存已登记足够的资产编号，请核对器材或编号");
        }
        store.db.update("UPDATE asset_unit SET equipment_id=COALESCE(equipment_id,?),status='borrowed',borrow_id=?,other_borrow_id=? WHERE asset_number=?",
            equipmentId,borrowId,otherBorrowId,number);
    }

    void release(String expected,String supplied,String borrowId,String otherBorrowId) {
        release(expected,supplied,borrowId,otherBorrowId,null);
    }

    void releaseDirect(String expected,String supplied,String equipmentId,String borrowId,String otherBorrowId) {
        if(expected==null||supplied==null)throw Api.invalid("现场直接归还须核对发放时绑定的资产编号");
        release(expected,supplied,borrowId,otherBorrowId,equipmentId);
    }

    private void release(String expected,String supplied,String borrowId,String otherBorrowId,String equipmentId) {
        if(!Objects.equals(expected,supplied))throw Api.invalid("归还资产编号与发放记录不一致");
        if(expected==null)return;
        var row=locked(expected);
        requireCheckout(row,borrowId,otherBorrowId);
        if(equipmentId!=null&&!equipmentId.equals(row.get("equipment_id")))throw Api.conflict("资产归属与借用器材不一致");
        store.db.update("UPDATE asset_unit SET status='available',borrow_id=NULL,other_borrow_id=NULL WHERE asset_number=?",expected);
    }

    void markIncident(String number,String equipmentId,String borrowId,String status) {
        if(borrowId==null) {
            store.db.update("INSERT INTO asset_unit(asset_number,status) VALUES(?,'available') ON DUPLICATE KEY UPDATE asset_number=asset_number",number);
            requireAvailable(locked(number),equipmentId);
        } else {
            requireCheckout(locked(number),borrowId,null);
        }
        store.db.update("UPDATE asset_unit SET equipment_id=COALESCE(equipment_id,?),status=?,borrow_id=NULL,other_borrow_id=NULL WHERE asset_number=?",
            equipmentId,status,number);
    }

    long activeCount(String equipmentId,boolean lock) {
        String condition=" FROM asset_unit WHERE equipment_id=? AND status IN ('available','borrowed')";
        if(lock)return store.db.queryForList("SELECT asset_number"+condition+" ORDER BY asset_number FOR UPDATE",String.class,equipmentId).size();
        return store.db.queryForObject("SELECT COUNT(*)"+condition,Long.class,equipmentId);
    }

    private Map<String,Object> locked(String number) {
        var rows=store.db.queryForList("SELECT equipment_id,status,borrow_id,other_borrow_id FROM asset_unit WHERE asset_number=? FOR UPDATE",number);
        if(rows.isEmpty())throw Api.conflict("资产台账不存在，请核对实际编号");
        return rows.getFirst();
    }

    private void requireAvailable(Map<String,Object> row,String equipmentId) {
        if(!"available".equals(row.get("status"))||row.get("borrow_id")!=null||row.get("other_borrow_id")!=null)
            throw new Api.Failure(409,"ASSET_UNAVAILABLE","资产编号已占用、维修中或已报废");
        if(equipmentId!=null&&row.get("equipment_id")!=null&&!equipmentId.equals(row.get("equipment_id")))
            throw new Api.Failure(409,"ASSET_UNAVAILABLE","资产编号属于其他器材，请核对器材和编号");
    }

    private void requireCheckout(Map<String,Object> row,String borrowId,String otherBorrowId) {
        if(!"borrowed".equals(row.get("status"))||!Objects.equals(borrowId,row.get("borrow_id"))||!Objects.equals(otherBorrowId,row.get("other_borrow_id")))
            throw Api.conflict("资产状态与借用记录不一致，请核对台账");
    }
}
