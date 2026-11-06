package com.supportiq.api;

import com.supportiq.service.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/platform")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformAdminController {
    private final BillingService billing;
    public PlatformAdminController(BillingService billing){this.billing=billing;}
    @GetMapping("/dashboard") public Map<String,Object> dashboard(){return billing.platformDashboard();}
    @GetMapping("/companies") public List<Map<String,Object>> companies(@RequestParam(defaultValue="")String search){return billing.platformCompanies(search);}
    @PutMapping("/companies/{id}/status") public Map<String,Object> status(@PathVariable UUID id,@Valid @RequestBody CompanyStatus body){return billing.setCompanyStatus(id,body.status());}
    @GetMapping("/subscriptions") public List<Map<String,Object>> subscriptions(){return billing.platformSubscriptions();}
    @GetMapping("/plans") public List<Map<String,Object>> plans(){return billing.platformPlans();}
    @PutMapping("/plans/{code}") public Map<String,Object> savePlan(@PathVariable String code,@RequestBody Map<String,Object>body){return billing.savePlan(code,body);}
    @GetMapping("/audit") public List<Map<String,Object>> audit(){return billing.platformAudit();}
    public record CompanyStatus(@NotBlank String status){}
}
