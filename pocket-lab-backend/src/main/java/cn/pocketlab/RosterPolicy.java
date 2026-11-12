package cn.pocketlab;

import java.util.*;

/** Versioned roster semantics. Enrollment year never reuses the editable current grade. */
final class RosterPolicy {
 private RosterPolicy(){}
 static String format(String value){if(!Set.of("legacy","unified-v1","unified-v2").contains(value))throw Api.invalid("导入格式无效");return value;}
 static boolean unified(String format){return !format.equals("legacy");}
 static String batchType(String format,String type){
  if(format.equals("unified-v2")){if(!type.equals("mixed"))throw Api.invalid("名单校验方式不匹配，请重新检查当前名单");return type;}
  return AccountPolicy.importType(type);
 }
 // V2 explicitly permits a blank D column. This rule applies only to new V2 rows;
 // stored users and older formats are never reclassified from their account numbers.
 static String mixedType(String account,String yearOrTeacher){
  if(yearOrTeacher.equals("老师"))return "teacher";
  if(yearOrTeacher.isEmpty()&&account.matches("(?:[0-9]{6}|[0-9]{8})"))return "teacher";
  return "student";
 }
 static String normalizedYear(String raw){String value=raw.trim();return value.matches("[0-9]{4}级")?value.substring(0,4):value;}
 static Integer enrollmentYear(String raw){String value=normalizedYear(raw);if(value.isEmpty())return null;if(!value.matches("[0-9]{4}")||Integer.parseInt(value)<1000)throw Api.invalid("入学年份须为空或四位年份，例如2024或2024级");return Integer.valueOf(value);}
 static Integer derivedYear(String account){return account.matches("[0-9]{10}")?2000+Integer.parseInt(account.substring(4,6)):null;}
}
