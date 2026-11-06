package com.supportiq.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.UUID;

/** Polls the durable UPLOADED state so queued knowledge jobs survive web/worker restarts. */
@Component
@EnableScheduling
@ConditionalOnProperty(name="app.ai.worker.enabled", havingValue="true")
public class KnowledgeDocumentWorker {
    private final JdbcTemplate db;
    private final KnowledgeDocumentService documents;
    private final int staleMinutes;

    public KnowledgeDocumentWorker(JdbcTemplate db, KnowledgeDocumentService documents,
                                   @Value("${app.ai.worker.stale-minutes:30}") int staleMinutes) {
        this.db=db;
        this.documents=documents;
        this.staleMinutes=Math.max(5,staleMinutes);
    }

    @Scheduled(fixedDelayString="${app.ai.worker.poll-ms:5000}", initialDelay=1000)
    public void poll() {
        documents.recoverStaleProcessing(staleMinutes);
        for(Map<String,Object> row:db.queryForList("select company_id,id from knowledge_documents where status='UPLOADED' order by created_at limit 4")) {
            documents.processQueued((UUID)row.get("company_id"),(UUID)row.get("id"));
        }
    }
}
