package com.supportiq.api;

import com.supportiq.service.BillingService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
public class BillingController {
    private final BillingService billing;
    public BillingController(BillingService billing){this.billing=billing;}

    @GetMapping("/api/public/billing/plans") public List<Map<String,Object>> plans(){return billing.publicPlans();}
    @GetMapping("/api/v1/billing/subscription") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> subscription(){return billing.subscription();}
    @GetMapping("/api/v1/billing/usage") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> usage(){return billing.usage();}
    @GetMapping("/api/v1/billing/payments") @PreAuthorize("hasRole('COMPANY_ADMIN')") public List<Map<String,Object>> payments(){return billing.payments();}
    @GetMapping("/api/v1/billing/invoices") @PreAuthorize("hasRole('COMPANY_ADMIN')") public List<Map<String,Object>> invoices(){return billing.invoices();}
    @GetMapping("/api/v1/billing/invoices/{id}") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> invoice(@PathVariable UUID id){return billing.invoice(id);}
    @GetMapping("/api/v1/billing/history") @PreAuthorize("hasRole('COMPANY_ADMIN')") public List<Map<String,Object>> history(){return billing.subscriptionHistory();}
    @PostMapping("/api/v1/billing/checkout") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> checkout(@RequestBody Map<String,Object> body,@RequestHeader(value="Idempotency-Key",required=false)String key){return billing.startCheckout(Objects.toString(body.get("planCode"),""),Objects.toString(body.getOrDefault("billingInterval","MONTHLY")),key);}
    @PostMapping("/api/v1/billing/payments/{id}/demo-result") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> demoResult(@PathVariable UUID id,@RequestBody Map<String,Object> body){return billing.simulateDemoPayment(id,Objects.toString(body.getOrDefault("outcome","SUCCEEDED")));}
    @PostMapping("/api/v1/billing/payments/{id}/refund") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> refund(@PathVariable UUID id,@RequestBody Map<String,Object>body){return billing.refundPayment(id,Objects.toString(body.get("reason"),""));}
    @PostMapping("/api/v1/billing/cancel") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> cancel(@RequestBody(required=false)Map<String,Object>body){return billing.cancel(body==null||!Boolean.FALSE.equals(body.get("atPeriodEnd")));}
    @PostMapping("/api/v1/billing/reactivate") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> reactivate(){return billing.reactivate();}
    @PostMapping("/api/public/billing/webhook/{provider}") public Map<String,Object> webhook(@PathVariable String provider,@RequestBody String raw,@RequestHeader(value="X-Billing-Signature",required=false)String signature){return billing.processWebhook(provider,raw,signature,false);}

    @GetMapping(value="/api/v1/analytics/report",produces=MediaType.APPLICATION_JSON_VALUE) @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> analytics(@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(defaultValue="DAY")String interval){return billing.companyAnalytics(from,to,interval);}
    @GetMapping(value="/api/v1/analytics/export.csv",produces="text/csv") @PreAuthorize("hasRole('COMPANY_ADMIN')") public ResponseEntity<String> csv(@RequestParam(required=false)String from,@RequestParam(required=false)String to,@RequestParam(defaultValue="DAY")String interval){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=supportiq-analytics.csv").contentType(MediaType.parseMediaType("text/csv; charset=utf-8")).body(billing.analyticsCsv(from,to,interval));}
}
