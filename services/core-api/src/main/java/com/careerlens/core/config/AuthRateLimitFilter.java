package com.careerlens.core.config;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {
 private record Bucket(long minute,AtomicInteger count){}
 private final ConcurrentHashMap<String,Bucket> buckets=new ConcurrentHashMap<>();
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException{
   String path=request.getRequestURI();
   if("POST".equals(request.getMethod()) && (path.equals("/api/v1/auth/login")||path.equals("/api/v1/auth/register"))){
     long minute=System.currentTimeMillis()/60_000;
     if(buckets.size()>10000)buckets.entrySet().removeIf(e->e.getValue().minute()<minute);
     Bucket bucket=buckets.compute(request.getRemoteAddr(),(key,old)->old==null||old.minute()!=minute?new Bucket(minute,new AtomicInteger()):old);
     if(bucket.count().incrementAndGet()>30){
       response.setStatus(429);response.setHeader("Retry-After","60");response.setContentType("application/problem+json");
       response.getWriter().write("{\"title\":\"AUTH_RATE_LIMIT\",\"status\":429,\"detail\":\"Too many authentication requests. Retry later.\"}");return;
     }
   }
   chain.doFilter(request,response);
 }
}
