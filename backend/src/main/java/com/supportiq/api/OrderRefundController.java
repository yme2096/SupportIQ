package com.supportiq.api;

import com.supportiq.service.OrderRefundService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
public class OrderRefundController {
    private final OrderRefundService service;
    public OrderRefundController(OrderRefundService service) { this.service=service; }

    @GetMapping("/api/v1/orders") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public List<Map<String,Object>> orders(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){return service.orders(search,page,size);}
    @PostMapping("/api/v1/orders") @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> createOrder(@RequestBody Map<String,Object> body){return service.createOrder(body);}
    @GetMapping("/api/v1/orders/{id}") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public Map<String,Object> order(@PathVariable UUID id){return service.order(id);}
    @PatchMapping("/api/v1/orders/{id}") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public Map<String,Object> updateOrder(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.updateOrder(id,body);}

    @GetMapping("/api/v1/refunds") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public List<Map<String,Object>> refunds(@RequestParam(defaultValue="") String status){return service.refunds(status);}
    @GetMapping("/api/v1/refunds/{id}") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public Map<String,Object> refund(@PathVariable UUID id){return service.refund(id);}
    @GetMapping("/api/v1/refunds/{id}/history") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public List<Map<String,Object>> refundHistory(@PathVariable UUID id){return service.history(id);}
    @PostMapping("/api/v1/refunds/{id}/review") @PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
    public Map<String,Object> review(@PathVariable UUID id,@RequestBody Map<String,Object> body){return service.review(id,Objects.toString(body.get("note"),""));}
    @PostMapping("/api/v1/refunds/{id}/approve") @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> approve(@PathVariable UUID id,@RequestBody(required=false) Map<String,Object> body){return service.approve(id,body==null||body.get("approvedAmount")==null?null:new BigDecimal(body.get("approvedAmount").toString()),Objects.toString(body==null?null:body.get("note"),""));}
    @PostMapping("/api/v1/refunds/{id}/reject") @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> reject(@PathVariable UUID id,@RequestBody(required=false) Map<String,Object> body){return service.reject(id,Objects.toString(body==null?null:body.get("reason"),""));}
    @PostMapping("/api/v1/refunds/{id}/initiate") @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> initiate(@PathVariable UUID id){return service.initiate(id);}
    @GetMapping("/api/v1/refund-policy") @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> policy(){return service.refundPolicy();}
    @PutMapping("/api/v1/refund-policy") @PreAuthorize("hasRole('COMPANY_ADMIN')")
    public Map<String,Object> updatePolicy(@RequestBody Map<String,Object> body){return service.updateRefundPolicy(body);}

    @PostMapping("/api/public/orders/verify")
    public Map<String,Object> verify(@Valid @RequestBody VerifyOrder body,HttpServletRequest request){return service.verifyCustomer(body.publicCompanyId(),body.orderNumber(),body.email(),body.postalCode(),request.getRemoteAddr());}
    @GetMapping("/api/public/orders/current")
    public Map<String,Object> publicOrder(@RequestHeader("X-Order-Access-Token") String token){return service.publicOrder(token);}
    @GetMapping("/api/public/orders/current/refunds")
    public List<Map<String,Object>> publicRefunds(@RequestHeader("X-Order-Access-Token") String token){return service.publicRefunds(token);}
    @GetMapping("/api/public/orders/current/history")
    public List<Map<String,Object>> publicOrderHistory(@RequestHeader("X-Order-Access-Token") String token){return service.publicOrderHistory(token);}
    @GetMapping("/api/public/orders/current/refunds/{id}/history")
    public List<Map<String,Object>> publicRefundHistory(@RequestHeader("X-Order-Access-Token") String token,@PathVariable UUID id){return service.publicRefundHistory(token,id);}
    @PostMapping("/api/public/orders/current/refunds")
    public Map<String,Object> requestRefund(@RequestHeader("X-Order-Access-Token") String token,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body){return service.requestRefund(token,body,key);}
    @PostMapping("/api/payments/webhooks/demo")
    public Map<String,Object> webhook(@RequestBody String raw,@RequestHeader(value="X-Provider-Signature",required=false) String signature){return service.webhook(raw,signature);}

    public record VerifyOrder(@NotBlank String publicCompanyId,@NotBlank @Size(max=64) String orderNumber,@NotBlank @Email @Size(max=320) String email,@NotBlank @Size(min=3,max=16) String postalCode) {}
}
