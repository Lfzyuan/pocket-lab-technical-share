package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static cn.pocketlab.Business.*;

@RestController @RequestMapping("/api/statistics")
public class Statistics {
    final Store s;final Business b;
    Statistics(Store s,Business b){this.s=s;this.b=b;}
    LocalDate[] range(String period,LocalDate from,LocalDate to){var today=s.today();if(!List.of("year","semester","custom").contains(period))throw Api.invalid("统计周期须为year、semester或custom");if(from!=null||to!=null)period="custom";
        if(period.equals("year")){from=LocalDate.of(today.getYear(),1,1);to=today;}
        if(period.equals("semester")){from=today.getMonthValue()>=8?LocalDate.of(today.getYear(),8,1):today.getMonthValue()>=2?LocalDate.of(today.getYear(),2,1):LocalDate.of(today.getYear()-1,8,1);to=today;}
        if(from==null||to==null||to.isBefore(from)||from.getYear()<2000||to.getYear()>2200)throw Api.invalid("统计起止日期无效");return new LocalDate[]{from,to};
    }
    Object[] timestamps(LocalDate[] range){return new Object[]{Timestamp.from(range[0].atStartOfDay(s.clock.getZone()).toInstant()),Timestamp.from(range[1].plusDays(1).atStartOfDay(s.clock.getZone()).toInstant())};}
    long sum(String table,String column,String time,String condition,Object[] args){return s.db.queryForObject("SELECT COALESCE(SUM("+column+"),0) FROM "+table+" WHERE "+time+">=? AND "+time+"<?"+condition,Long.class,args);}
    @GetMapping
    public Api.Envelope<?> summary(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="year") String period,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,HttpServletRequest req){Store.role(actor,"admin");var range=range(period,from,to);var args=timestamps(range);var result=map("from",range[0],"to",range[1],"borrowers",s.db.queryForObject("SELECT COUNT(DISTINCT user_id) FROM borrow_request WHERE issued_at>=? AND issued_at<?",Long.class,args),"issued",sum("borrow_request","quantity","issued_at","",args),"returned",sum("borrow_request","quantity","returned_at","",args),"added",sum("stock_log","quantity","occurred_at"," AND mode='add'",args),"removed",sum("stock_log","quantity","occurred_at"," AND mode='remove'",args));
        for(String kind:List.of("damaged","lost","retired","discrepancy"))result.put(kind,sum("incident","quantity","created_at"," AND kind='"+kind+"'",args));
        result.put("writtenOff",sum("incident","quantity","created_at"," AND kind<>'damaged'",args));result.put("repair",s.db.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM incident WHERE status='repair'",Long.class));return Api.ok(result,req);
    }
    @GetMapping("/equipment-heat")
    public Api.Envelope<?> heat(@AuthenticationPrincipal Api.User actor,@RequestParam(defaultValue="year") String period,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,HttpServletRequest req){Store.role(actor,"admin");Store.pagination(page,pageSize);var dates=range(period,from,to);var args=new ArrayList<>(List.of(timestamps(dates)));args.add(pageSize);args.add((page-1)*pageSize);
        String sql="SELECT e.id,e.name,e.model,e.category,(SELECT COUNT(*) FROM favorite f WHERE f.equipment_id=e.id) AS favorite_count,(SELECT COALESCE(SUM(r.quantity),0) FROM borrow_request r WHERE r.equipment_id=e.id AND r.issued_at>=? AND r.issued_at<?) AS issued_count FROM equipment e ORDER BY issued_count DESC,favorite_count DESC,e.id LIMIT ? OFFSET ?";
        return Api.ok(new Api.Page<>(b.rows(sql,args.toArray()),s.db.queryForObject("SELECT COUNT(*) FROM equipment",Long.class),page,pageSize),req);
    }
}
