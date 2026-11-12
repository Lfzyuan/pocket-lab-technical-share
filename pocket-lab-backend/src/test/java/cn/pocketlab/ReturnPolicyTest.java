package cn.pocketlab;
import java.time.*;import java.util.*;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ReturnPolicyTest {
 @Test void deadlineIsExactlySevenDaysAndOnlyPastTheBoundaryIsOverdue(){var at=Instant.parse("2026-10-30T04:00:00Z");assertFalse(ReturnPolicy.overdue(at,at.plusSeconds(604799)));assertFalse(ReturnPolicy.overdue(at,at.plusSeconds(604800)));assertTrue(ReturnPolicy.overdue(at,at.plusSeconds(604801)));}
 @Test void latestRequestResetsWindowAndIgnoresOtherEvents(){var at=OffsetDateTime.parse("2026-10-30T12:00:00+08:00");var events=List.of(new Api.Event("a","return-request","borrowed","returning","s",null,at),new Api.Event("b","return-reject","returning","borrowed","t",null,at.plusDays(1)),new Api.Event("c","return-request","borrowed","returning","s",null,at.plusDays(2)));assertEquals(at.plusDays(9),ReturnPolicy.deadline(events));}
 @Test void missingRequestDoesNotInventDates(){assertNull(ReturnPolicy.deadline(List.of()));assertNull(ReturnPolicy.latestRequest(null));}
}
