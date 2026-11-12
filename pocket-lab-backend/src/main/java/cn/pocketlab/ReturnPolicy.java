package cn.pocketlab;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
final class ReturnPolicy {
    static final Duration WINDOW=Duration.ofDays(7);
    static Api.Event latestRequest(List<Api.Event> events){
        if(events==null)return null;
        return events.stream().filter(e->"return-request".equals(e.action())&&e.at()!=null)
            .max(Comparator.comparing(Api.Event::at).thenComparing(Api.Event::id)).orElse(null);
    }
    static OffsetDateTime deadline(List<Api.Event> events){var event=latestRequest(events);return event==null?null:event.at().plus(WINDOW);}
    static boolean overdue(Instant requestedAt,Instant now){return now.isAfter(requestedAt.plus(WINDOW));}
    static String format(OffsetDateTime value){return value.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));}
    private ReturnPolicy(){}
}
