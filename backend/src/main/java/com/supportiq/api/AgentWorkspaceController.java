package com.supportiq.api;

import com.supportiq.security.TenantContext;
import com.supportiq.service.TicketWorkspaceService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ResponseEntity;
import com.supportiq.service.ConversationAttachmentService;

import java.util.*;

@RestController
@RequestMapping("/api/v1/agent/workspace")
@PreAuthorize("hasAnyRole('AGENT','COMPANY_ADMIN')")
public class AgentWorkspaceController {
    private final TicketWorkspaceService workspace;
    private final ConversationAttachmentService attachments;
    public AgentWorkspaceController(TicketWorkspaceService workspace,ConversationAttachmentService attachments){this.workspace=workspace;this.attachments=attachments;}

    @GetMapping("/conversations") public Map<String,Object> conversations(@RequestParam(defaultValue="ALL") String queue,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String priority,
            @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){
        return workspace.conversations(queue,status,priority,search,page,size);
    }
    @GetMapping("/agents") public List<Map<String,Object>> agents(){return workspace.agents();}
    @PutMapping("/availability") public Map<String,Object> availability(@RequestBody Availability body){return workspace.availability(body.availability(),body.workloadLimit());}
    @PostMapping("/conversations/{id}/assign") public Map<String,Object> assign(@PathVariable UUID id,@RequestBody(required=false) Assign body){
        return workspace.assign(id,body==null?null:parseAgent(body.agentId()),body!=null&&Boolean.TRUE.equals(body.automatic()));
    }
    private UUID parseAgent(String value){if(value==null||value.isBlank())return null;try{return UUID.fromString(value);}catch(Exception e){throw new IllegalArgumentException("Agent ID is invalid.");}}
    @GetMapping("/conversations/{id}/messages") public List<Map<String,Object>> messages(@PathVariable UUID id){return workspace.messages(id);}
    @GetMapping("/conversations/{id}/history") public Map<String,Object> history(@PathVariable UUID id){return workspace.history(id);}
    @PostMapping("/conversations/{id}/notes") public Map<String,Object> note(@PathVariable UUID id,@Valid @RequestBody Note body){return workspace.addNote(id,body.content());}
    @PostMapping("/conversations/{id}/attachments") public Map<String,Object> uploadAttachment(@PathVariable UUID id,@RequestPart("file") MultipartFile file){return attachments.staffUpload(id,file);}
    @GetMapping("/attachments/{id}") public ResponseEntity<byte[]> downloadAttachment(@PathVariable String id){return attachments.staffDownload(id);}
    @PatchMapping("/conversations/{id}/ticket") public Map<String,Object> lifecycle(@PathVariable UUID id,@RequestBody Lifecycle body){return workspace.lifecycle(id,body.status(),body.priority(),body.resolutionReason());}
    @GetMapping("/conversations/{id}/sla") public Map<String,Object> sla(@PathVariable UUID id){return workspace.sla(id);}
    @GetMapping("/notifications") public Map<String,Object> notifications(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="30") int size){return workspace.notifications(page,size);}
    @PostMapping("/notifications/{id}/read") public Map<String,Object> readNotification(@PathVariable UUID id){workspace.readNotification(id);return Map.of("read",true);}
    @GetMapping("/support-settings") public Map<String,Object> supportSettings(){return workspace.supportSettings();}
    @PutMapping("/support-settings") @PreAuthorize("hasRole('COMPANY_ADMIN')") public Map<String,Object> updateSupportSettings(@RequestBody Map<String,Object> settings){return workspace.updateSupportSettings(settings);}
    @PostMapping("/tickets/{id}/feedback") public Map<String,Object> feedback(@PathVariable UUID id,@Valid @RequestBody Feedback body){return workspace.feedback(id,body.rating(),body.comment());}

    public record Availability(String availability,@Min(1) @Max(100) Integer workloadLimit){}
    public record Assign(String agentId,Boolean automatic){}
    public record Note(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=4000) String content){}
    public record Lifecycle(String status,String priority,String resolutionReason){}
    public record Feedback(@Min(1) @Max(5) int rating,@jakarta.validation.constraints.Size(max=1000) String comment){}
}
