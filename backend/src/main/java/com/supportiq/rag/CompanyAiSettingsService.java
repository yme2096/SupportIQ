package com.supportiq.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class CompanyAiSettingsService {
    private final JdbcTemplate db;
    private final ObjectMapper json=new ObjectMapper();
    public CompanyAiSettingsService(JdbcTemplate db) { this.db=db; }

    public CompanyAiSettings forCompany(UUID companyId) {
        try {
            String encoded=db.queryForObject("select settings::text from company_settings where company_id=?",String.class,companyId);
            Map<String,Object> settings=json.readValue(encoded,new TypeReference<>(){});
            Object raw=settings.get("ai");
            if(!(raw instanceof Map<?,?> map)) return CompanyAiSettings.defaults();
            var defaults=CompanyAiSettings.defaults();
            boolean enabled=Boolean.parseBoolean(Objects.toString(value(map,"enabled",defaults.enabled())));
            String provider=Objects.toString(value(map,"provider",defaults.provider())).toUpperCase();
            String model=Objects.toString(value(map,"model",defaults.model()));
            String language=Objects.toString(value(map,"language",defaults.language()));
            String tone=Objects.toString(value(map,"tone",defaults.tone()));
            int maxChunks=integer(map.get("maxChunks"),defaults.maxChunks(),1,8);
            double threshold=decimal(map.get("relevanceThreshold"),defaults.relevanceThreshold(),0,1);
            boolean handoff=Boolean.parseBoolean(Objects.toString(value(map,"handoffOnUnknown",defaults.handoffOnUnknown())));
            String fallback=Objects.toString(value(map,"fallbackResponse",defaults.fallbackResponse()));
            if(!provider.equals("GEMINI")&&!provider.equals("LOCAL_FALLBACK")) provider="LOCAL_FALLBACK";
            if(!model.matches("gemini-[a-zA-Z0-9._-]{2,80}")) model=defaults.model();
            if(language.length()>40) language=defaults.language();
            if(tone.length()>80) tone=defaults.tone();
            if(fallback.length()>500) fallback=defaults.fallbackResponse();
            return new CompanyAiSettings(enabled,provider,model,language,tone,maxChunks,threshold,handoff,fallback);
        } catch(Exception ex) { return CompanyAiSettings.defaults(); }
    }
    private Object value(Map<?,?> map,String key,Object fallback){return map.containsKey(key)?map.get(key):fallback;}
    private int integer(Object value,int fallback,int min,int max) { try{return Math.max(min,Math.min(max,Integer.parseInt(Objects.toString(value))));}catch(Exception ex){return fallback;} }
    private double decimal(Object value,double fallback,double min,double max) { try{return Math.max(min,Math.min(max,Double.parseDouble(Objects.toString(value))));}catch(Exception ex){return fallback;} }
}
