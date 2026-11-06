package com.supportiq.api;
import com.supportiq.service.SupportService;import org.springframework.web.bind.annotation.*;import java.util.*;
@RestController @RequestMapping("/api/v1") public class ApiController {
 private final SupportService s;public ApiController(SupportService s){this.s=s;}
 @GetMapping("/health") public Map health(){return s.health();}
 @PostMapping("/chat/message") public Map chat(@RequestBody Map b,@RequestHeader(value="X-Conversation-Token",required=false) String token){return s.chat(b,token);}
 @GetMapping("/chat/conversations") public List conversations(){return s.conversations();}
 @GetMapping("/chat/{id}/messages") public List messages(@PathVariable String id,@RequestHeader(value="X-Conversation-Token",required=false) String token){return s.messages(id,token);}
 @GetMapping("/agent/tickets") public List tickets(){return s.tickets();}
 @PostMapping("/agent/conversations/{id}/reply") public Map reply(@PathVariable String id,@RequestBody Map b){return s.reply(id,b);}
 @PatchMapping("/agent/tickets/{id}") public Map update(@PathVariable String id,@RequestBody Map b){return s.update(id,b);}
 @GetMapping("/analytics") public Map analytics(){return s.analytics();}
}
