package com.supportiq.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class KnowledgeRetrievalService {
    private static final Logger log=LoggerFactory.getLogger(KnowledgeRetrievalService.class);
    private static final Pattern WORD=Pattern.compile("[a-zA-Z0-9]{3,}");
    private static final Set<String> STOP=Set.of("the","and","for","does","how","what","when","where","your","you","are","can","could","would","please","with","this","that","have","has","from","about","will","into","there","their");
    private final JdbcTemplate db;
    private final EmbeddingProvider embeddings;
    public KnowledgeRetrievalService(JdbcTemplate db,EmbeddingProvider embeddings){this.db=db;this.embeddings=embeddings;}

    public RetrievalResult retrieve(UUID companyId,String question,CompanyAiSettings settings) {
        if(embeddings.available()) {
            try {
                float[] query=embeddings.embedQuery(question);
                List<KnowledgeChunk> hits=vectorSearch(companyId,query,settings.maxChunks(),settings.relevanceThreshold(),
                        embeddings.providerName(),embeddings.modelName());
                if (!hits.isEmpty()) return new RetrievalResult(hits,embeddings.providerName()+"_VECTOR",true);
            } catch(Exception ex) { log.warn("Vector retrieval unavailable for company {}: {}",companyId,ex.getMessage()); }
        }
        return new RetrievalResult(fullTextSearch(companyId,question,settings.maxChunks()),"FULL_TEXT_FALLBACK",false);
    }

    private List<KnowledgeChunk> vectorSearch(UUID companyId,float[] vector,int topK,double threshold,String provider,String model) {
        if(vector.length!=768) throw new IllegalArgumentException("Query embedding dimension mismatch.");
        String literal=vectorLiteral(vector);
        return db.query("""
                select c.id,c.company_id,c.document_id,d.file_name,c.chunk_index,c.page_number,c.section_title,c.chunk_text,
                       1-(c.embedding <=> cast(? as vector)) similarity
                from knowledge_chunks c join knowledge_documents d on d.id=c.document_id and d.company_id=c.company_id
                where c.company_id=? and d.company_id=? and d.status='READY' and c.embedding is not null
                  and c.embedding_provider=? and c.embedding_model=?
                  and 1-(c.embedding <=> cast(? as vector)) >= ?
                order by c.embedding <=> cast(? as vector) limit ?
                """,(rs,row)->mapChunk(rs),literal,companyId,companyId,provider,model,literal,threshold,literal,topK);
    }

    private List<KnowledgeChunk> fullTextSearch(UUID companyId,String question,int topK) {
        String query=toTsQuery(question); if(query.isBlank()) return List.of();
        return db.query("""
                select c.id,c.company_id,c.document_id,d.file_name,c.chunk_index,c.page_number,c.section_title,c.chunk_text
                from knowledge_chunks c join knowledge_documents d on d.id=c.document_id and d.company_id=c.company_id
                where c.company_id=? and d.company_id=? and d.status='READY' and c.search_vector @@ to_tsquery('simple',?)
                order by ts_rank_cd(c.search_vector,to_tsquery('simple',?)) desc,c.created_at desc limit ?
                """,(rs,row)->new KnowledgeChunk(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),
                rs.getObject("document_id",UUID.class),rs.getString("file_name"),rs.getInt("chunk_index"),
                (Integer)rs.getObject("page_number"),rs.getString("section_title"),rs.getString("chunk_text"),0.0),companyId,companyId,query,query,topK);
    }

    private KnowledgeChunk mapChunk(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object raw=rs.getObject("similarity");
        return new KnowledgeChunk(rs.getObject("id",UUID.class),rs.getObject("company_id",UUID.class),rs.getObject("document_id",UUID.class),
                rs.getString("file_name"),rs.getInt("chunk_index"),(Integer)rs.getObject("page_number"),rs.getString("section_title"),
                rs.getString("chunk_text"),raw==null?null:((Number)raw).doubleValue());
    }

    private String toTsQuery(String query) {
        LinkedHashSet<String> words=new LinkedHashSet<>(); var matcher=WORD.matcher(query.toLowerCase(Locale.ROOT));
        while(matcher.find()) { String word=matcher.group(); if(!STOP.contains(word)) words.add(word); }
        return String.join(" | ",words.stream().limit(16).map(w->w+":*").toList());
    }
    private String vectorLiteral(float[] values) { StringBuilder b=new StringBuilder("["); for(int i=0;i<values.length;i++){if(i>0)b.append(',');if(!Float.isFinite(values[i]))throw new IllegalArgumentException("Invalid embedding value.");b.append(Float.toString(values[i]));}return b.append(']').toString(); }

    public boolean hasPotentialConflict(List<KnowledgeChunk> hits,String question) {
        if(hits.size()<2) return false;
        Set<String> queryTerms=terms(question);
        for(int i=0;i<hits.size();i++) for(int j=i+1;j<hits.size();j++) {
            KnowledgeChunk a=hits.get(i),b=hits.get(j);
            if(a.documentId().equals(b.documentId())) continue;
            Set<String> common=new HashSet<>(terms(a.text())); common.retainAll(terms(b.text())); common.retainAll(queryTerms);
            if(common.size()<2) continue;
            Set<String> numbersA=numbers(a.text()),numbersB=numbers(b.text());
            boolean valueConflict=!numbersA.isEmpty()&&!numbersB.isEmpty()&&!numbersA.equals(numbersB);
            boolean negationA=negated(a.text()),negationB=negated(b.text());
            if(valueConflict||negationA!=negationB) return true;
        }
        return false;
    }
    private Set<String> terms(String text){Set<String> result=new HashSet<>();var m=WORD.matcher(text.toLowerCase(Locale.ROOT));while(m.find()){String word=m.group();if(word.endsWith("s")&&word.length()>4)word=word.substring(0,word.length()-1);if(!STOP.contains(word))result.add(word);}return result;}
    private Set<String> numbers(String text){Set<String> r=new HashSet<>();var m=Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b").matcher(text);while(m.find())r.add(m.group());return r;}
    private boolean negated(String text){return Pattern.compile("\\b(no|not|never|cannot|ineligible|excluded|prohibited|without)\\b",Pattern.CASE_INSENSITIVE).matcher(text).find();}

    public record RetrievalResult(List<KnowledgeChunk> chunks,String mode,boolean vectorSearch) {}
}
