package com.supportiq.service;

import com.supportiq.realtime.RealtimeChatService;
import com.supportiq.security.TenantContext;
import org.apache.tika.Tika;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.util.*;

@Service
public class ConversationAttachmentService {
    private final JdbcTemplate db;private final SupportService support;private final RealtimeChatService realtime;private final BillingService billing;
    public ConversationAttachmentService(JdbcTemplate db,SupportService support,RealtimeChatService realtime,BillingService billing){this.db=db;this.support=support;this.realtime=realtime;this.billing=billing;}
    public Map<String,Object> customerUpload(String publicId,String conversationId,String token,MultipartFile file){
        UUID company=support.authorizeWidgetConversation(publicId,conversationId,token),conversation=parse(conversationId);
        return store(company,conversation,"CUSTOMER",file,()->realtime.publishCustomerSnapshot(publicId,conversation,token));
    }
    public Map<String,Object> staffUpload(UUID conversationId,MultipartFile file){
        UUID company=TenantContext.requireCompanyId();if(db.queryForList("select id from conversations where id=? and company_id=?",conversationId,company).isEmpty())throw notFound();
        return store(company,conversationId,"AGENT",file,()->realtime.publishAgentSnapshot(conversationId,company));
    }
    private Map<String,Object> store(UUID company,UUID conversation,String sender,MultipartFile file,Runnable after){
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024)throw new IllegalArgumentException("Choose a file smaller than 5 MB.");
        billing.assertStorageCapacity(company,file.getSize());
        String path=Objects.toString(file.getOriginalFilename(),"attachment").replace('\\','/');String name=StringUtils.getFilename(path);
        if(name==null||name.isBlank()||name.length()>180||name.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Attachment filename is invalid.");
        String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!List.of("png","jpg","jpeg","pdf","txt").contains(extension))throw new IllegalArgumentException("Attachments must be PNG, JPEG, PDF, or TXT.");
        byte[] bytes;try{bytes=file.getBytes();}catch(Exception e){throw new IllegalArgumentException("Attachment could not be read.");}
        if(bytes.length==0)throw new IllegalArgumentException("The attachment is empty.");
        String mime=new Tika().detect(bytes,name);
        if(extension.equals("png")&&!(bytes.length>=8&&Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})))throw new IllegalArgumentException("The PNG signature is invalid.");
        if((extension.equals("jpg")||extension.equals("jpeg"))&&!(bytes.length>=3&&(bytes[0]&255)==255&&(bytes[1]&255)==216&&(bytes[2]&255)==255))throw new IllegalArgumentException("The JPEG signature is invalid.");
        if(extension.equals("pdf")&&(bytes.length<5||!new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")))throw new IllegalArgumentException("The PDF signature is invalid.");
        if(extension.equals("txt")){try{String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();if(text.indexOf('\u0000')>=0||!mime.startsWith("text/"))throw new IllegalArgumentException("The text attachment is not valid UTF-8 text.");}catch(java.nio.charset.CharacterCodingException e){throw new IllegalArgumentException("The text attachment is not valid UTF-8 text.");}}
        if(!List.of("image/png","image/jpeg","application/pdf").contains(mime)&&!extension.equals("txt"))throw new IllegalArgumentException("The attachment content does not match its file type.");
        UUID author="AGENT".equals(sender)?TenantContext.userIdOrNull():null;
        UUID message=db.queryForObject("insert into messages(company_id,conversation_id,sender_type,content,author_id,delivery_status) values(?,?,?,?,?,'SENT') returning id",UUID.class,company,conversation,sender,"[Attachment: "+name+"]",author);
        UUID id=db.queryForObject("insert into conversation_attachments(company_id,conversation_id,message_id,uploaded_by_type,file_name,mime_type,file_size,file_data) values(?,?,?,?,?,?,?,?) returning id",UUID.class,company,conversation,message,sender,name,mime,(long)bytes.length,bytes);
        db.update("update conversations set updated_at=now() where id=? and company_id=?",conversation,company);after.run();
        return Map.of("id",id.toString(),"messageId",message.toString(),"fileName",name,"mimeType",mime,"fileSize",bytes.length);
    }
    public ResponseEntity<byte[]> customerDownload(String publicId,String attachmentId,String token){
        UUID company=support.companyIdForPublicId(publicId),id=parse(attachmentId);List<Map<String,Object>> rows=db.queryForList("select conversation_id from conversation_attachments where id=? and company_id=?",id,company);if(rows.isEmpty())throw notFound();UUID conversation=(UUID)rows.getFirst().get("conversation_id");support.authorizeWidgetConversation(publicId,conversation.toString(),token);return download(id,company);
    }
    public ResponseEntity<byte[]> staffDownload(String attachmentId){return download(parse(attachmentId),TenantContext.requireCompanyId());}
    private ResponseEntity<byte[]> download(UUID id,UUID company){List<Map<String,Object>> rows=db.queryForList("select file_name,mime_type,file_data from conversation_attachments where id=? and company_id=?",id,company);if(rows.isEmpty())throw notFound();Map<String,Object> row=rows.getFirst();String name=Objects.toString(row.get("file_name"),"attachment");MediaType type;try{type=MediaType.parseMediaType(Objects.toString(row.get("mime_type")));}catch(Exception e){type=MediaType.APPLICATION_OCTET_STREAM;}return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name.replace("\"","_")+"\"").contentType(type).body((byte[])row.get("file_data"));}
    private UUID parse(String value){try{return UUID.fromString(value);}catch(Exception e){throw notFound();}}
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Attachment not found.");}
}
