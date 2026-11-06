package com.supportiq.config;

import com.supportiq.security.TenantContext;
import com.supportiq.service.BillingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;

@Component
public class SubscriptionAccessFilter extends OncePerRequestFilter {
    private final BillingService billing;
    public SubscriptionAccessFilter(BillingService billing){this.billing=billing;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI();
        return !(path.startsWith("/api/v1/")||path.startsWith("/api/company/")||path.startsWith("/api/users/")||path.startsWith("/api/invitations"))
                ||path.startsWith("/api/v1/billing/")||path.startsWith("/api/platform/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(TenantContext.companyIdOrNull()==null||"SUPER_ADMIN".equals(TenantContext.role())){chain.doFilter(request,response);return;}
        try{
            billing.assertCompanyActive(TenantContext.companyIdOrNull());
            String method=request.getMethod();
            if(!method.equals("GET")&&!method.equals("HEAD")&&!method.equals("OPTIONS"))billing.assertWritableForRequest(TenantContext.companyIdOrNull());
            chain.doFilter(request,response);
        }catch(ResponseStatusException ex){
            response.setStatus(ex.getStatusCode().value());response.setContentType("application/json");
            response.getWriter().write("{\"error\":\""+ex.getReason().replace("\\","\\\\").replace("\"","\\\"")+"\"}");
        }
    }
}
