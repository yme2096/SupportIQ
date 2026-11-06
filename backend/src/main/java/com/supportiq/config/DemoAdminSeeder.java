package com.supportiq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class DemoAdminSeeder implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    @Value("${app.demo.seed-admin:true}") boolean enabled;
    @Value("${app.demo.admin-email}") String email;
    @Value("${app.demo.admin-password}") String password;
    @Value("${app.demo.admin-name}") String fullName;

    public DemoAdminSeeder(JdbcTemplate db, PasswordEncoder encoder) { this.db = db; this.encoder = encoder; }

    @Override public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (password == null || password.length() < 12) throw new IllegalStateException("Set DEMO_ADMIN_PASSWORD to a local-development password of at least 12 characters.");
        UUID companyId = db.query("select id from companies where slug='shopsmart'", rs -> rs.next() ? rs.getObject(1, UUID.class) : null);
        if (companyId == null) return;
        Integer users = db.queryForObject("select count(*) from app_users where lower(email)=lower(?)", Integer.class, email);
        if (users != null && users > 0) return;
        Integer companyRole = db.queryForObject("select id from roles where name='COMPANY_ADMIN'", Integer.class);
        db.update("insert into app_users(company_id,role_id,email,full_name,password_hash,status) values(?,?,?,?,?,'ACTIVE')",
                companyId, companyRole, email.trim().toLowerCase(), fullName, encoder.encode(password));
        db.update("insert into audit_logs(company_id,action,target_type,details) values(?,'DEMO_ADMIN_SEEDED','USER',jsonb_build_object('email',?))", companyId, email);
    }
}
