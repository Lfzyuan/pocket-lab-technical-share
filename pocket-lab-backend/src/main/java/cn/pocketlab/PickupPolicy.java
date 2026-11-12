package cn.pocketlab;

import java.time.*;
import java.util.function.Predicate;

/** The approval date is excluded. Holidays are currently treated as ordinary weekdays. */
public final class PickupPolicy {
    private PickupPolicy() {}
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public static boolean weekday(LocalDate date){return date.getDayOfWeek()!=DayOfWeek.SATURDAY&&date.getDayOfWeek()!=DayOfWeek.SUNDAY;}
    public static OffsetDateTime deadline(OffsetDateTime approvedAt){return deadline(approvedAt,PickupPolicy::weekday);}
    public static OffsetDateTime deadline(OffsetDateTime approvedAt,Predicate<LocalDate> workingDay){
        LocalDate date=approvedAt.atZoneSameInstant(ZONE).toLocalDate();int remaining=10;
        while(remaining>0){date=date.plusDays(1);if(workingDay.test(date))remaining--;}
        return date.plusDays(1).atStartOfDay(ZONE).minusNanos(1000).toOffsetDateTime();
    }
}
