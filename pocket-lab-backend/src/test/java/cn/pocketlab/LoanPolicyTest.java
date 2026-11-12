package cn.pocketlab;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LoanPolicyTest {
 @Test void issuanceUsesThreeCalendarMonths(){assertEquals(LocalDate.of(2026,4,30),LoanPolicy.initialDue(LocalDate.of(2026,1,31)));assertEquals(LocalDate.of(2027,3,31),LoanPolicy.initialDue(LocalDate.of(2026,12,31)));}
 @Test void firstRenewalUsesTwoMonthsAndSecondUsesOneFromSavedDue(){assertEquals(LocalDate.of(2026,3,31),LoanPolicy.renewedDue(LocalDate.of(2026,1,31),0));assertEquals(LocalDate.of(2026,4,30),LoanPolicy.renewedDue(LocalDate.of(2026,3,31),1));assertEquals(2,LoanPolicy.nextRenewMonths(0));assertEquals(1,LoanPolicy.nextRenewMonths(1));assertNull(LoanPolicy.nextRenewMonths(2));}
 @Test void secondRenewalAllowedThirdAndOverdueDenied(){var today=LocalDate.of(2026,10,27);assertTrue(LoanPolicy.canRenew(0,today,today));assertTrue(LoanPolicy.canRenew(1,today,today));assertFalse(LoanPolicy.canRenew(2,today,today));assertFalse(LoanPolicy.canRenew(0,today.minusDays(1),today));assertFalse(LoanPolicy.canRenew(0,null,today));}
}
