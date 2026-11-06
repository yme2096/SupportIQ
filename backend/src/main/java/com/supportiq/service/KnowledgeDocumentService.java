package com.supportiq.service;

import com.supportiq.rag.DocumentTextExtractor;
import com.supportiq.rag.EmbeddingProvider;
import com.supportiq.rag.KnowledgeChunker;
import com.supportiq.security.TenantContext;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.Executor;

@Service
public class KnowledgeDocumentService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate db;
    private final DocumentTextExtractor extractor;
    private final KnowledgeChunker chunker;
    private final EmbeddingProvider embeddings;
    private final TransactionTemplate transaction;
    private final Executor executor;
    private final BillingService billing;
    @Value("${app.ai.processing.dispatch-locally:true}") private boolean dispatchLocally;
    @Value("${app.ai.max-upload-bytes:10485760}") private long maxUploadBytes;

    public KnowledgeDocumentService(JdbcTemplate db, DocumentTextExtractor extractor, KnowledgeChunker chunker,
                                    EmbeddingProvider embeddings, PlatformTransactionManager transactionManager,
                                    @Qualifier("knowledgeTaskExecutor") Executor executor, BillingService billing) {
        this.db=db; this.extractor=extractor; this.chunker=chunker; this.embeddings=embeddings;
        this.transaction=new TransactionTemplate(transactionManager);
        this.executor=executor; this.billing=billing;
    }

    public List<Map<String,Object>> list(String search, String status) {
        UUID companyId=TenantContext.requireCompanyId();
        String query=search == null ? "" : search.trim();
        String filter=status == null || status.isBlank() ? "" : status.toUpperCase(Locale.ROOT);
        if (!filter.isBlank() && !List.of("UPLOADED","PROCESSING","READY","FAILED","ARCHIVED").contains(filter)) throw new IllegalArgumentException("Invalid document status filter.");
        return db.queryForList("""
                select id,file_name,status,created_at,updated_at,file_size,mime_type,version,supersedes_id,attempt_count,processing_error
                from knowledge_documents where company_id=? and (?='' or file_name ilike '%'||?||'%') and (?='' or status=?)
                order by created_at desc, version desc
                """, companyId, query, query, filter, filter);
    }

    public Map<String,Object> detail(String id) {
        UUID companyId=TenantContext.requireCompanyId(); UUID documentId=parse(id);
        List<Map<String,Object>> rows=db.queryForList("""
                select id,file_name,status,created_at,updated_at,file_size,mime_type,version,supersedes_id,attempt_count,processing_error,
                       (select count(*) from knowledge_chunks c where c.document_id=d.id and c.company_id=d.company_id) chunk_count
                from knowledge_documents d where d.id=? and d.company_id=?
                """,documentId,companyId);
        if(rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }

    public Map<String,Object> preview(String id) {
        UUID companyId=TenantContext.requireCompanyId(); UUID documentId=parse(id);
        List<Map<String,Object>> rows=db.queryForList("select id,file_name,status,content from knowledge_documents where id=? and company_id=?",documentId,companyId);
        if(rows.isEmpty()) throw notFound();
        Map<String,Object> row=rows.getFirst();
        row.put("content",Objects.toString(row.get("content"),""));
        return row;
    }

    public Map<String,Object> upload(MultipartFile file) {
        UUID companyId=TenantContext.requireCompanyId();
        if(file==null || file.isEmpty()) throw new IllegalArgumentException("Choose a non-empty document.");
        if(file.getSize()>maxUploadBytes) throw new IllegalArgumentException("Maximum document size is 10 MB.");
        billing.assertKnowledgeCapacity(companyId,file.getSize());
        String filename=cleanFilename(file.getOriginalFilename());
        String extension=extension(filename);
        if(!List.of("pdf","docx","txt","md").contains(extension)) throw new IllegalArgumentException("Supported formats are PDF, DOCX, TXT, and MD.");
        byte[] bytes;
        try { bytes=file.getBytes(); } catch(Exception ex) { throw new IllegalArgumentException("The document could not be read."); }
        validateSignature(extension,bytes,filename);
        String hash=sha256(bytes);
        if(!db.queryForList("select id from knowledge_documents where company_id=? and content_hash=? and status<>'ARCHIVED'",companyId,hash).isEmpty())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"This document is already in the active knowledge base.");
        List<Map<String,Object>> previous=db.queryForList("select id,version from knowledge_documents where company_id=? and lower(file_name)=lower(?) order by version desc limit 1",companyId,filename);
        int version=previous.isEmpty()?1:((Number)previous.getFirst().get("version")).intValue()+1;
        UUID supersedes=previous.isEmpty()?null:(UUID)previous.getFirst().get("id");
        UUID id=UUID.randomUUID(); String mime=new Tika().detect(bytes,filename);
        db.update("""
                insert into knowledge_documents(id,company_id,file_name,content,status,content_hash,original_file,mime_type,file_size,version,supersedes_id)
                values(?,?,?,'','UPLOADED',?,?,?,?,?,?)
                """,id,companyId,filename,hash,bytes,mime,file.getSize(),version,supersedes);
        audit(companyId,"KNOWLEDGE_DOCUMENT_UPLOADED",id,Map.of("fileName",filename,"version",version,"size",file.getSize()));
        processAsync(companyId,id);
        return detail(id.toString());
    }

    public Map<String,Object> retry(String id, boolean forceReprocess) {
        UUID companyId=TenantContext.requireCompanyId(); UUID documentId=parse(id);
        List<Map<String,Object>> rows=db.queryForList("select status,attempt_count,original_file,content from knowledge_documents where id=? and company_id=?",documentId,companyId);
        if(rows.isEmpty()) throw notFound();
        Map<String,Object> row=rows.getFirst(); String status=Objects.toString(row.get("status"));
        if("ARCHIVED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Archived documents cannot be processed.");
        if("PROCESSING".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This document is already being processed.");
        if(!forceReprocess && !"FAILED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Only failed documents can be retried.");
        if(((Number)row.get("attempt_count")).intValue()>=MAX_ATTEMPTS) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Retry limit reached. Upload a new version or contact support.");
        db.update("update knowledge_documents set status='UPLOADED',processing_error=null,updated_at=now() where id=? and company_id=?",documentId,companyId);
        processAsync(companyId,documentId);
        return detail(documentId.toString());
    }

    public Map<String,Object> archive(String id) {
        UUID companyId=TenantContext.requireCompanyId(); UUID documentId=parse(id);
        int updated=db.update("update knowledge_documents set status='ARCHIVED',updated_at=now() where id=? and company_id=? and status<>'ARCHIVED'",documentId,companyId);
        if(updated==0) throw notFound();
        audit(companyId,"KNOWLEDGE_DOCUMENT_ARCHIVED",documentId,Map.of());
        return detail(documentId.toString());
    }

    public void processAsync(UUID companyId, UUID documentId) {
        // UPLOADED is the durable queue state. In Render production a dedicated worker polls this state;
        // local Compose keeps the existing in-process executor for a zero-setup developer experience.
        if (!dispatchLocally) return;
        try { executor.execute(() -> processNow(companyId,documentId)); }
        catch(Exception ex) {
            db.update("update knowledge_documents set status='FAILED',processing_error='Processing queue is full. Retry this document later.',updated_at=now() where id=? and company_id=? and status='UPLOADED'",documentId,companyId);
        }
    }

    public void processQueued(UUID companyId, UUID documentId) { processNow(companyId,documentId); }

    public void recoverStaleProcessing(int staleMinutes) {
        int attempts=db.update("update knowledge_documents set status='UPLOADED',processing_error='Worker interruption detected; processing will be retried.',updated_at=now() where status='PROCESSING' and updated_at < now() - make_interval(mins => ?) and attempt_count < ?",staleMinutes,MAX_ATTEMPTS);
        int exhausted=db.update("update knowledge_documents set status='FAILED',processing_error='Worker stopped during processing too many times. Review the document and retry it.',updated_at=now() where status='PROCESSING' and updated_at < now() - make_interval(mins => ?) and attempt_count >= ?",staleMinutes,MAX_ATTEMPTS);
        if(attempts+exhausted>0) log.warn("Recovered document jobs stale={} failed={}",attempts,exhausted);
    }

    private void processNow(UUID companyId, UUID documentId) {
        int claimed=db.update("update knowledge_documents set status='PROCESSING',attempt_count=attempt_count+1,processing_error=null,updated_at=now() where id=? and company_id=? and status='UPLOADED'",documentId,companyId);
        if(claimed==0) return;
        try {
            Map<String,Object> doc=db.queryForMap("select file_name,original_file,content,version from knowledge_documents where id=? and company_id=? and status='PROCESSING'",documentId,companyId);
            byte[] bytes=(byte[])doc.get("original_file");
            DocumentTextExtractor.ExtractedDocument extracted;
            if(bytes==null || bytes.length==0) {
                String legacy=Objects.toString(doc.get("content"),"");
                extracted=extractor.extractText(legacy);
            } else extracted=extractor.extract(Objects.toString(doc.get("file_name")),bytes);
            List<KnowledgeChunker.ChunkDraft> pieces=chunker.chunk(extracted.segments());
            if(pieces.isEmpty()) throw new IllegalArgumentException("No usable text chunks were found in this document.");
            List<String> forEmbedding=pieces.stream().map(p->"title: " + doc.get("file_name") + " | text: " + p.text()).toList();
            if(!embeddings.available()) throw new IllegalStateException("Embeddings are not configured. Set GEMINI_API_KEY or GEMINI_EMBEDDING_API_KEY to process documents.");
            List<float[]> vectors=embeddings.embedDocuments(forEmbedding);
            if(vectors.size()!=pieces.size()) throw new IllegalStateException("The embedding provider returned an incomplete result.");
            transaction.executeWithoutResult(tx -> {
                db.update("delete from knowledge_chunks where company_id=? and document_id=?",companyId,documentId);
                for(int i=0;i<pieces.size();i++) {
                    var piece=pieces.get(i); float[] vector=vectors.get(i);
                    db.update("""
                            insert into knowledge_chunks(company_id,document_id,chunk_index,chunk_text,page_number,section_title,embedding,embedding_provider,embedding_model)
                            values(?,?,?,?,?,?,CAST(? AS vector),?,?)
                            """,companyId,documentId,piece.index(),piece.text(),piece.pageNumber(),piece.sectionTitle(),vectorLiteral(vector),embeddings.providerName(),embeddings.modelName());
                }
                int updated=db.update("update knowledge_documents set content=?,status='READY',processing_error=null,updated_at=now() where id=? and company_id=? and status='PROCESSING'",extracted.text(),documentId,companyId);
                if(updated!=1) throw new IllegalStateException("Document processing was cancelled.");
                // A successfully processed new version supersedes the prior active version, while keeping its history.
                db.update("update knowledge_documents set status='ARCHIVED',updated_at=now() where company_id=? and lower(file_name)=lower(?) and id<>? and status='READY'",companyId,doc.get("file_name"),documentId);
            });
            log.info("Knowledge document processed id={} company={} chunks={}",documentId,companyId,pieces.size());
        } catch(Exception ex) {
            String message=cleanError(ex.getMessage());
            db.update("update knowledge_documents set status='FAILED',processing_error=?,updated_at=now() where id=? and company_id=? and status='PROCESSING'",message,documentId,companyId);
            log.warn("Knowledge document processing failed id={} company={} reason={}",documentId,companyId,message);
        }
    }

    private void validateSignature(String ext,byte[] bytes,String filename) {
        if(bytes.length==0) throw new IllegalArgumentException("The document is empty.");
        if("pdf".equals(ext) && (bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))) throw new IllegalArgumentException("The file does not contain a valid PDF signature.");
        if("docx".equals(ext)) {
            try(ZipInputStream zip=new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                ZipEntry entry; boolean found=false;
                while((entry=zip.getNextEntry())!=null) if("word/document.xml".equals(entry.getName())) { found=true; break; }
                if(!found) throw new IllegalArgumentException("The file is not a valid DOCX document.");
            } catch(java.io.IOException ex) { throw new IllegalArgumentException("The file is not a valid DOCX document."); }
        }
        if(("txt".equals(ext)||"md".equals(ext))) {
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(text.isBlank()) throw new IllegalArgumentException("The text document is empty.");
            if(text.indexOf('\u0000')>=0) throw new IllegalArgumentException("The text document contains binary data.");
        }
        String mime=new Tika().detect(bytes,filename);
        if("pdf".equals(ext)&&!"application/pdf".equals(mime)) throw new IllegalArgumentException("The file contents do not match the PDF extension.");
        if("docx".equals(ext)&&!mime.contains("wordprocessingml")&& !"application/zip".equals(mime)) throw new IllegalArgumentException("The file contents do not match the DOCX extension.");
        if(("txt".equals(ext)||"md".equals(ext))&&!mime.startsWith("text/")) throw new IllegalArgumentException("The file contents do not match a text document.");
    }
    private String cleanFilename(String raw) {
        String supplied=Objects.toString(raw,"knowledge-document").replace('\\','/');
        String name=StringUtils.getFilename(supplied);
        if(name==null||name.isBlank()||name.length()>255||name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Document filename is invalid.");
        return name;
    }
    private String extension(String filename) { int dot=filename.lastIndexOf('.'); return dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT); }
    private String sha256(byte[] bytes) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);} }
    private UUID parse(String id) { try{return UUID.fromString(id);}catch(Exception ex){throw notFound();} }
    private ResponseStatusException notFound(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Knowledge document not found.");}
    private String cleanError(String raw) { String value=Objects.toString(raw,"Document processing failed.").replaceAll("[\\r\\n\\t]+"," ").trim(); return value.length()>590?value.substring(0,590):value; }
    private String vectorLiteral(float[] values) { StringBuilder b=new StringBuilder("["); for(int i=0;i<values.length;i++){if(i>0)b.append(',');b.append(Float.toString(values[i]));}return b.append(']').toString(); }
    private void audit(UUID companyId,String action,UUID documentId,Map<String,Object> details) {
        try { db.update("insert into audit_logs(company_id,actor_id,action,target_type,target_id,details) values(?,?,?,'KNOWLEDGE_DOCUMENT',?,?::jsonb)",companyId,TenantContext.userIdOrNull(),action,documentId.toString(),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details)); } catch(Exception ignored) { }
    }
}
