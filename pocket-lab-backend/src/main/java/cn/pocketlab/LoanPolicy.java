package cn.pocketlab;

import java.time.LocalDate;

public final class LoanPolicy {
    private LoanPolicy() {}
    public static final int MAX_RENEWALS=2;
    public static boolean canRenew(int count,LocalDate due,LocalDate today){return count<MAX_RENEWALS&&due!=null&&!today.isAfter(due);}
    public static LocalDate initialDue(LocalDate issuedDate){return issuedDate.plusMonths(3);}
    public static Integer nextRenewMonths(int renewCount){return renewCount>=MAX_RENEWALS?null:renewCount==0?2:1;}
    public static LocalDate renewedDue(LocalDate currentDue,int renewCount){
        Integer months=nextRenewMonths(renewCount);
        if(months==null)throw new IllegalArgumentException("Renewal limit reached");
        return currentDue.plusMonths(months);
    }
    public static LocalDate renewedDue(LocalDate currentDue){return renewedDue(currentDue,0);}
}
