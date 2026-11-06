package com.supportiq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class SuperAdminSeeder implements ApplicationRunner {
    private final JdbcTemplate db; private final PasswordEncoder encoder;
    @Value("${app.platform.super-admin-email:}") private String email;
    @Value("${app.platform.super-admin-password:}") private String password;
    @Value("${app.platform.super-admin-name:SupportIQ Platform Admin}") private String name;
    public SuperAdminSeeder(JdbcTemplate db,PasswordEncoder encoder){this.db=db;this.encoder=encoder;}
    @Override public void run(ApplicationArguments args){
        if(email==null||email.isBlank()||password==null||password.isBlank())return;
        if(password.length()<16)throw new IllegalStateException("SUPER_ADMIN_PASSWORD must be at least 16 characters when platform admin provisioning is configured.");
        String normalized=email.trim().toLowerCase(java.util.Locale.ROOT);
        Integer existing=db.queryForObject("select count(*) from app_users where lower(email)=?",Integer.class,normalized);
        if(existing!=null&&existing>0){String role=db.queryForObject("select r.name from app_users u join roles r on r.id=u.role_id where lower(u.email)=?",String.class,normalized);if(!"SUPER_ADMIN".equals(role))throw new IllegalStateException("Configured SUPER_ADMIN_EMAIL already belongs to a non-platform account.");return;}
        Integer role=db.queryForObject("select id from roles where name='SUPER_ADMIN'",Integer.class);
        db.update("insert into app_users(company_id,role_id,email,full_name,password_hash,status) values(null,?,?,?,?, 'ACTIVE')",role,normalized,name,encoder.encode(password));
        db.update("insert into audit_logs(company_id,action,target_type,details) values(null,'PLATFORM_SUPER_ADMIN_PROVISIONED','USER',jsonb_build_object('email',?))",normalized);
    }
}
