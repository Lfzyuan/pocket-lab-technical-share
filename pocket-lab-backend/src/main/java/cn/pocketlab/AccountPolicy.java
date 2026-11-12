package cn.pocketlab;

import java.util.Set;

/** Explicit identity is separate from authorization. No account-number-based permission grants. */
final class AccountPolicy {
    private AccountPolicy() {}
    static final String TYPE_SQL="CASE WHEN role='admin' THEN 'admin' WHEN account_type='teacher' THEN 'teacher' ELSE 'student' END";
    static String identity(String role,String stored){return "admin".equals(role)?"admin":"teacher".equals(stored)?"teacher":"student";}
    static String creationType(String type){if(!Set.of("student","teacher","admin").contains(type))throw Api.invalid("账号类型无效");return type;}
    static String importType(String type){if(!Set.of("student","teacher").contains(type))throw Api.invalid("批量导入仅支持学生或老师账号");return type;}
    static void account(String type,String account){
        if("teacher".equals(type)&&!account.matches("(?:[0-9]{6}|[0-9]{8})"))throw Api.invalid("教师工号须为6位或8位数字，请保留前导0");
        if(!"teacher".equals(type)&&!account.matches("[A-Za-z0-9_-]{2,32}"))throw Api.invalid("账号格式无效");
    }
    static void rosterAccount(String type,String account){account(type,account);if("student".equals(type)&&!account.matches("[0-9]{10}"))throw Api.invalid("学号必须是10位数字");}
    static void creationPassword(String type,String password){int minimum="teacher".equals(type)?6:12;if(password==null||password.codePointCount(0,password.length())<minimum)throw Api.invalid("新建"+("teacher".equals(type)?"老师":"学生或管理员")+"账号密码至少"+minimum+"位");}
    static String initialPassword(String type,String account){rosterAccount(importType(type),account);return account.substring(account.length()-6);}
    static String trustedClass(String type,String displayClass){return "teacher".equals(type)||"admin".equals(type)?"":displayClass;}
}
