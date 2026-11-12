package cn.pocketlab;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

final class AccountCohorts {
    private static final Pattern YEAR_LABEL=Pattern.compile("(?<![0-9])(?:20)?([0-9]{2})级");
    private static final Pattern CLASS_CODE=Pattern.compile("(?<![0-9])([0-9]{2})[0-9]{2}(?:班)?$");

    static String official(String value){return Objects.toString(value,"").strip();}

    static String year(String value){
        String name=Normalizer.normalize(official(value),Normalizer.Form.NFKC);
        var years=new HashSet<String>();
        var labels=YEAR_LABEL.matcher(name);
        while(labels.find())years.add(labels.group(1));
        var code=CLASS_CODE.matcher(name);
        if(code.find())years.add(code.group(1));
        return years.size()==1?years.iterator().next():null;
    }

    // Only roster writers take this lock. Self-edited display class is not a roster write.
    static void lock(Store s){
        s.db.queryForObject("SELECT name FROM business_lock WHERE name='account_roster' FOR UPDATE",String.class);
    }

    record Group(String kind,String value) {
        boolean includes(String className){
            String officialClass=official(className);
            return kind.equals("class")?value.equals(officialClass):value.equals(year(officialClass));
        }
    }

    static Group group(com.fasterxml.jackson.databind.JsonNode body){
        if(body.has("cohort"))throw Api.conflict("账号分组已更新，请刷新页面后按官方班级选择");
        if(!body.has("classGroup"))return null;
        var input=new Business.Input(body.get("classGroup"),"kind","value");
        String kind=input.choice("kind","year","class"),value=input.text("value",100,true);
        if(kind.equals("year")&&!value.matches("[0-9]{2}"))throw Api.invalid("年级无效");
        return new Group(kind,value);
    }
}
