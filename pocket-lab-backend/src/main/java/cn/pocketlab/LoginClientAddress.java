package cn.pocketlab;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Only a configured, directly connected proxy may provide the single client address. */
@Component
final class LoginClientAddress {
    private final Set<String> trusted=new HashSet<>();

    LoginClientAddress(@Value("${app.trusted-proxies:}") String addresses){
        for(String entry:addresses.split(",")){
            if(entry.isBlank())continue;
            String address=numeric(entry.trim());
            if(address==null)throw new IllegalArgumentException("TRUSTED_PROXIES must contain literal IP addresses");
            trusted.add(address);
        }
    }

    String resolve(HttpServletRequest request){
        String peer=request.getRemoteAddr();
        if(!trusted.contains(numeric(peer)))return peer;
        var headers=request.getHeaders("X-Real-IP");
        if(headers==null||!headers.hasMoreElements())return peer;
        String client=numeric(headers.nextElement());
        // A chain, duplicate header, host name or malformed value falls back to the peer limiter.
        return client!=null&&!headers.hasMoreElements()?client:peer;
    }

    private static String numeric(String input){
        if(input==null||input.length()>45)return null;
        boolean ipv6=input.contains(":")&&input.matches("[0-9a-fA-F:.]+");
        if(!ipv6){
            if(!input.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return null;
            for(String part:input.split("\\."))if(Integer.parseInt(part)>255||part.length()>1&&part.startsWith("0"))return null;
        }
        try{return InetAddress.getByName(input).getHostAddress();}
        catch(Exception e){return null;}
    }
}
