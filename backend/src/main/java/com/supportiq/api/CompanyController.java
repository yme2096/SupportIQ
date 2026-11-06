package com.supportiq.api;

import com.supportiq.security.TenantContext;
import com.supportiq.service.AuthService;
import com.supportiq.rag.GeminiEmbeddingProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.net.URI;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/company")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class CompanyController {
    private final AuthService auth;
    private final GeminiEmbeddingProvider embeddings;
    private final boolean answerKeyConfigured;
    public CompanyController(AuthService auth, GeminiEmbeddingProvider embeddings,
                             @Value("${app.gemini.api-key:}") String answerKey) {
        this.auth = auth;
        this.embeddings = embeddings;
        this.answerKeyConfigured = answerKey != null && !answerKey.isBlank();
    }

    @GetMapping("/profile") public Map<String,Object> profile() { return auth.companyProfile(); }
    @PutMapping("/profile") public Map<String,Object> updateProfile(@Valid @RequestBody CompanyProfile body) {
        return auth.updateCompanyProfile(Map.of("name", body.name(), "businessEmail", body.businessEmail(), "phone", body.phone() == null ? "" : body.phone(), "website", body.website() == null ? "" : body.website()), TenantContext.userIdOrNull());
    }
    @GetMapping("/settings") public Map<String,Object> settings() { return auth.companySettings(); }
    @GetMapping("/ai-status") public Map<String,Object> aiStatus() {
        return Map.of("answerProvider", answerKeyConfigured ? "GEMINI" : "LOCAL_FALLBACK",
                "answerConfigured", answerKeyConfigured,
                "embeddingProvider", embeddings.providerName(),
                "embeddingModel", embeddings.modelName(),
                "embeddingConfigured", embeddings.available());
    }
    @PutMapping("/settings") public Map<String,Object> updateSettings(@RequestBody Map<String,Object> body) {
        rejectCredentials(body);
        Object raw = body.get("widget");
        if (raw instanceof Map<?,?> widget) {
            checkLength(widget.get("name"), "Widget name", 40);
            checkLength(widget.get("welcomeMessage"), "Welcome message", 280);
            checkLength(widget.get("logoUrl"), "Logo URL", 500);
            Object color = widget.get("primaryColor");
            if (color != null && !color.toString().matches("^#[0-9a-fA-F]{6}$")) throw new IllegalArgumentException("Primary color must be a six-digit hex color.");
            Object logo = widget.get("logoUrl");
            if (logo != null && !logo.toString().isBlank()) {
                try {
                    URI uri = URI.create(logo.toString());
                    if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException();
                } catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Logo URL must use http or https."); }
            }
        }
        Object aiRaw=body.get("ai");
        if(aiRaw instanceof Map<?,?> ai) {
            String provider=Objects.toString(ai.containsKey("provider")?ai.get("provider"):"GEMINI").toUpperCase();
            if(!List.of("GEMINI","LOCAL_FALLBACK").contains(provider)) throw new IllegalArgumentException("Choose Gemini or local fallback as the AI provider.");
            Object model=ai.get("model");
            if(model!=null&&!model.toString().matches("gemini-[a-zA-Z0-9._-]{2,80}")) throw new IllegalArgumentException("Gemini model name is invalid.");
            checkLength(ai.get("language"),"Response language",40);
            checkLength(ai.get("tone"),"Response tone",80);
            checkLength(ai.get("fallbackResponse"),"Fallback response",500);
            Object max=ai.get("maxChunks");
            if(max!=null&&(!(max instanceof Number n)||n.intValue()<1||n.intValue()>8)) throw new IllegalArgumentException("Maximum retrieval chunks must be between 1 and 8.");
            Object threshold=ai.get("relevanceThreshold");
            if(threshold!=null&&(!(threshold instanceof Number n)||n.doubleValue()<0||n.doubleValue()>1)) throw new IllegalArgumentException("Relevance threshold must be between 0 and 1.");
        }
        return auth.updateCompanySettings(body, TenantContext.userIdOrNull());
    }

    private void checkLength(Object value, String field, int max) {
        if (value != null && value.toString().length() > max) throw new IllegalArgumentException(field + " must be at most " + max + " characters.");
    }

    private void rejectCredentials(Map<String,Object> settings) {
        for(String key:settings.keySet()) if(key.toLowerCase().matches(".*(apikey|secret|token|credential).*"))
            throw new IllegalArgumentException("Provider credentials must be configured on the server and cannot be saved in company settings.");
    }

    public record CompanyProfile(@NotBlank @Size(max=160) String name, @NotBlank @Email @Size(max=320) String businessEmail,
                                 @Size(max=40) String phone, @Size(max=255) String website) {}
}
