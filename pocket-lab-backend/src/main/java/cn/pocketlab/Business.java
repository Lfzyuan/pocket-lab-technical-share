package cn.pocketlab;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** Shared validation, paging and persistent idempotency for the remaining modules. */
@Component
public class Business {
    final Store s; final ObjectMapper json;
    Business(Store s,ObjectMapper json){this.s=s;this.json=json;}
    static Map<String,Object> map(Object... entries){var m=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)m.put((String)entries[i],entries[i+1]);return m;}
    static final RowMapper<Map<String,Object>> ROW=(r,n)->{
        var m=new LinkedHashMap<String,Object>();var meta=r.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++){
            String col=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);StringBuilder key=new StringBuilder();boolean upper=false;
            for(char c:col.toCharArray()){if(c=='_')upper=true;else{key.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object v=r.getObject(i);
            if(v instanceof Timestamp t)v=t.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toOffsetDateTime();
            if(v instanceof java.sql.Date d)v=d.toLocalDate();if(v instanceof java.sql.Time t)v=t.toLocalTime();
            if(v instanceof java.math.BigDecimal d)v=d.toPlainString();
            if(Set.of("enabled","internal","read","isRead","isSubmission","active","requiresApproval","requiresAsset","anonymous","contactPublic").contains(key.toString())&&v instanceof Number num)v=num.intValue()!=0;
            m.put(key.toString(),v);
        }return m;
    };
    Map<String,Object> one(String sql,Object... args){return s.db.query(sql,ROW,args).stream().findFirst().orElseThrow(Api::missing);}
    List<Map<String,Object>> rows(String sql,Object... args){return s.db.query(sql,ROW,args);}
    Api.Page<Map<String,Object>> page(String select,String from,String where,List<Object> arguments,String order,int page,int size){
        Store.pagination(page,size);var args=new ArrayList<>(arguments);
        long total=s.db.queryForObject("SELECT COUNT(*) "+from+where,Long.class,args.toArray());args.add(size);args.add((page-1)*size);
        return new Api.Page<>(rows(select+" "+from+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray()),total,page,size);
    }
    Object write(Api.User actor,String key,String scope,JsonNode input,Supplier<?> operation){
        if(key==null||!key.matches("[A-Za-z0-9_-]{8,100}"))throw Api.invalid("Idempotency-Key须为8至100位字母数字、下划线或连字符");
        var current=s.lockUser(actor.id());if(!current.role().equals(actor.role()) && !(current.canManage() && actor.role().equals("reviewer")))throw Api.forbidden();
        // Small single-campus deployment: serialize extension writes, including configuration and all booking approvals.
        // Actor -> extension gate -> equipment is the fixed lock order; the existing loan path uses actor -> equipment.
        s.db.queryForObject("SELECT name FROM business_lock WHERE name='extensions' FOR UPDATE",String.class);
        String hash=Security.digest(encode(input));
        var found=s.db.queryForList("SELECT * FROM idempotency_record WHERE user_id=? AND key_value=?",actor.id(),key);
        if(!found.isEmpty()){
            var old=found.getFirst();
            if(((Timestamp)old.get("expires_at")).toInstant().isAfter(s.clock.instant())){
                if(!scope.equals(old.get("scope"))||!hash.equals(old.get("request_hash")))throw new Api.Failure(409,"IDEMPOTENCY_CONFLICT","此幂等键已用于不同请求");
                try{return json.readTree((String)old.get("response_json"));}catch(Exception e){throw new IllegalStateException(e);}
            }s.db.update("DELETE FROM idempotency_record WHERE user_id=? AND key_value=?",actor.id(),key);
        }
        Object result=operation.get();
        s.db.update("INSERT INTO idempotency_record(user_id,key_value,scope,request_hash,response_json,expires_at) VALUES(?,?,?,?,?,?)",actor.id(),key,scope,hash,encode(result),Timestamp.from(s.clock.instant().plusSeconds(86400)));
        return result;
    }
    String encode(Object o){try{return json.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException(e);}}
    void version(Map<String,Object> row,Input p){if(((Number)row.get("version")).longValue()!=p.number("version",0,Long.MAX_VALUE))throw Api.conflict("记录已更新，请刷新后重试");}
    void notify(String user,String title,String body,String dedupe,String request,String equipment){
        if(s.db.queryForObject("SELECT COUNT(*) FROM user_notification WHERE dedupe_key=?",Long.class,dedupe)>0)return;
        s.db.update("INSERT INTO user_notification(id,user_id,title,body,dedupe_key,request_id,equipment_id,created_at) VALUES(?,?,?,?,?,?,?,?)",Store.id(),user,title,body,dedupe,request,equipment,s.now());
    }
    void stock(Api.User actor,String equipment,String mode,int quantity,String reason){
        var e=s.equipment(equipment,true);if(quantity<1||quantity>100000)throw Api.invalid("数量须为1至100000");
        if(mode.equals("remove")&&quantity>e.available())throw new Api.Failure(409,"STOCK_INSUFFICIENT","不能移出已预留或借出的库存");
        int after=e.stock()+(mode.equals("add")?quantity:-quantity);
        s.db.update("UPDATE equipment SET stock=?,version=version+1 WHERE id=?",after,equipment);
        s.db.update("INSERT INTO stock_log(id,equipment_id,mode,quantity,after_stock,reason,actor_id,occurred_at) VALUES(?,?,?,?,?,?,?,?)",Store.id(),equipment,mode,quantity,after,reason,actor.id(),s.now());
    }
    static String like(String s){if(s.length()>100)throw Api.invalid("搜索条件过长");return "%"+s.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    static String search(String keyword,String q){if(!keyword.isEmpty()&&!q.isEmpty()&&!keyword.equals(q))throw Api.invalid("q与keyword不能同时传入不同搜索内容");String result=keyword.isEmpty()?q:keyword;if(result.length()>100)throw Api.invalid("搜索条件过长");return result;}
    static class Filters {
        String where=" WHERE 1=1";final List<Object> args=new ArrayList<>();
        void eq(String column,String value){if(value!=null&&!value.isEmpty()){if(value.length()>100)throw Api.invalid("筛选值过长");where+=" AND "+column+"=?";args.add(value);}}
        void dates(String column,LocalDate from,LocalDate to,ZoneId zone){if(from!=null&&to!=null&&to.isBefore(from))throw Api.invalid("结束日期不能早于开始日期");if(from!=null){where+=" AND "+column+">=?";args.add(Timestamp.from(from.atStartOfDay(zone).toInstant()));}if(to!=null){where+=" AND "+column+"<?";args.add(Timestamp.from(to.plusDays(1).atStartOfDay(zone).toInstant()));}}
    }
    static String url(String value,boolean required){if(value.isEmpty()&&!required)return value;try{var u=URI.create(value);if(!Set.of("http","https").contains(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null)throw new Exception();return value;}catch(Exception e){throw Api.invalid("链接须为有效的http或https地址");}}
    static void state(Map<String,Object> row,String... states){if(!List.of(states).contains(row.get("status")))throw Api.conflict("当前状态不允许此操作");}
    static void submissionPrivacy(JsonNode body) {
        if(!body.path("anonymous").isBoolean() || !body.path("contactPublic").isBoolean()
            || body.path("anonymous").booleanValue()==body.path("contactPublic").booleanValue())
            throw Api.invalid("请选择匿名展示或公开署名及联系方式");
    }

    static final class Input {
        final JsonNode node;
        Input(JsonNode n,String... allowed){node=n;if(n==null||!n.isObject())throw Api.invalid("请求体须为对象");var names=Set.of(allowed);n.fieldNames().forEachRemaining(k->{if(!names.contains(k))throw Api.invalid("不支持字段："+k);});}
        boolean has(String key){return node.hasNonNull(key);}
        String text(String k,int max,boolean required){var v=node.get(k);if(v==null||v.isNull()){if(required)throw Api.invalid("请填写"+k);return "";}if(!v.isTextual())throw Api.invalid(k+"须为字符串");String value=v.textValue().trim();if(value.length()>max||required&&value.isEmpty()||value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw Api.invalid(k+"内容无效或过长");return value;}
        String choice(String key,String... values){String v=text(key,32,true);if(!List.of(values).contains(v))throw Api.invalid(key+"不在允许范围");return v;}
        long number(String k,long min,long max){var n=node.get(k);if(n==null||!n.isIntegralNumber()||!n.canConvertToLong()||n.longValue()<min||n.longValue()>max)throw Api.invalid(k+"数值无效");return n.longValue();}
        boolean bool(String k){var v=node.get(k);if(v==null||!v.isBoolean())throw Api.invalid(k+"须为布尔值");return v.booleanValue();}
        LocalDate date(String key){try{return LocalDate.parse(text(key,10,true));}catch(Exception e){throw Api.invalid(key+"日期无效");}}
        LocalTime time(String key){try{return LocalTime.parse(text(key,8,true));}catch(Exception e){throw Api.invalid(key+"时间无效");}}
    }
}
