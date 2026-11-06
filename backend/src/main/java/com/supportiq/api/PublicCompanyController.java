package com.supportiq.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.supportiq.service.SupportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import com.supportiq.service.ConversationAttachmentService;

@RestController
@RequestMapping("/api/public")
public class PublicCompanyController {
    private final JdbcTemplate db;
    private final SupportService support;
    private final ConversationAttachmentService attachments;
    private final ObjectMapper json = new ObjectMapper();

    public PublicCompanyController(JdbcTemplate db, SupportService support,ConversationAttachmentService attachments) {
        this.db = db;
        this.support = support;this.attachments=attachments;
    }

    @GetMapping("/companies/{slug}")
    public Map<String,Object> lookup(@PathVariable String slug) {
        Map<String,Object> company = db.queryForMap("select id,name,slug,public_id from companies where slug=?", slug.toLowerCase());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("name", company.get("name"));
        result.put("slug", company.get("slug"));
        result.put("publicId", company.get("public_id"));
        result.putAll(widgetSettings(Objects.toString(company.get("id"))));
        return result;
    }

    @GetMapping("/widget/{publicId}/config")
    public Map<String,Object> widgetConfig(@PathVariable String publicId) {
        support.companyIdForPublicId(publicId);
        Map<String,Object> company = db.queryForMap("select id,name,slug,public_id from companies where public_id=?", UUID.fromString(publicId));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("companyName", company.get("name"));
        result.put("companySlug", company.get("slug"));
        result.put("publicId", company.get("public_id"));
        result.put("publicChatUrl", "/chat/" + company.get("slug"));
        result.putAll(widgetSettings(Objects.toString(company.get("id"))));
        return result;
    }

    @PostMapping("/widget/{publicId}/chat")
    public Map<String,Object> widgetChat(@PathVariable String publicId, @RequestBody Map<String,Object> body,
            @RequestHeader(value="X-Conversation-Token", required=false) String token) {
        return support.widgetChat(publicId, body, token);
    }

    @GetMapping("/widget/{publicId}/conversations/{conversationId}/messages")
    public List<Map<String,Object>> widgetMessages(@PathVariable String publicId, @PathVariable String conversationId,
            @RequestHeader(value="X-Conversation-Token", required=false) String token) {
        return support.widgetMessages(publicId, conversationId, token);
    }

    @PostMapping("/widget/{publicId}/conversations/{conversationId}/attachments")
    public Map<String,Object> widgetAttachment(@PathVariable String publicId,@PathVariable String conversationId,
            @RequestHeader(value="X-Conversation-Token",required=false) String token,@RequestPart("file") MultipartFile file){return attachments.customerUpload(publicId,conversationId,token,file);}

    @GetMapping("/widget/{publicId}/attachments/{attachmentId}")
    public ResponseEntity<byte[]> downloadWidgetAttachment(@PathVariable String publicId,@PathVariable String attachmentId,
            @RequestHeader(value="X-Conversation-Token",required=false) String token){return attachments.customerDownload(publicId,attachmentId,token);}

    @PostMapping("/widget/{publicId}/conversations/{conversationId}/feedback")
    public Map<String,Object> feedback(@PathVariable String publicId,@PathVariable String conversationId,
            @RequestHeader(value="X-Conversation-Token",required=false) String token,@RequestBody Map<String,Object> body){
        UUID companyId=support.authorizeWidgetConversation(publicId,conversationId,token);
        int rating;try{rating=((Number)body.get("rating")).intValue();}catch(Exception ex){throw new IllegalArgumentException("Rating must be from 1 to 5.");}
        if(rating<1||rating>5)throw new IllegalArgumentException("Rating must be from 1 to 5.");
        String comment=Objects.toString(body.get("comment"),"").trim();if(comment.length()>1000)throw new IllegalArgumentException("Feedback is limited to 1000 characters.");
        List<UUID> tickets=db.query("select id from support_tickets where company_id=? and conversation_id=? and status in ('RESOLVED','CLOSED') order by updated_at desc limit 1",(rs,n)->rs.getObject(1,UUID.class),companyId,UUID.fromString(conversationId));
        if(tickets.isEmpty())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Feedback is available after a ticket is resolved.");
        UUID feedback=db.queryForObject("insert into customer_feedback(company_id,ticket_id,rating,comment) values(?,?,?,?) on conflict(ticket_id) do update set rating=excluded.rating,comment=excluded.comment,created_at=now() returning id",UUID.class,companyId,tickets.getFirst(),rating,comment.isBlank()?null:comment);
        return Map.of("id",feedback.toString(),"rating",rating);
    }

    private Map<String,Object> widgetSettings(String companyId) {
        try {
            String encoded = db.queryForObject("select settings::text from company_settings where company_id=?", String.class, java.util.UUID.fromString(companyId));
            Map<String,Object> settings = json.readValue(encoded, new TypeReference<>() {});
            Object rawWidget = settings.get("widget");
            @SuppressWarnings("unchecked") Map<String,Object> widget = rawWidget instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("widgetName", Objects.toString(widget.getOrDefault("name", "")));
            result.put("logoUrl", Objects.toString(widget.getOrDefault("logoUrl", "")));
            result.put("welcomeMessage", Objects.toString(widget.getOrDefault("welcomeMessage", "Hello! How can we help?")));
            result.put("primaryColor", Objects.toString(widget.getOrDefault("primaryColor", "#6859cf")));
            return result;
        } catch (Exception ex) {
            return Map.of("widgetName", "", "logoUrl", "", "welcomeMessage", "Hello! How can we help?", "primaryColor", "#6859cf");
        }
    }
}
