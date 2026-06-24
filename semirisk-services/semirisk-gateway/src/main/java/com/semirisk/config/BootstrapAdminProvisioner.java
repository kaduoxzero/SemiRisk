package com.semirisk.config;

import com.semirisk.common.SemiriskConstants;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.security.PasswordHashService;
import com.semirisk.service.SemiRiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminProvisioner.class);

    private final SemiRiskStore store;
    private final PreparedRiskRepository preparedRiskRepository;
    private final PasswordHashService passwordHashService;
    private final boolean enabled;
    private final String username;
    private final String password;
    private final String displayName;
    private final String email;

    public BootstrapAdminProvisioner(
            SemiRiskStore store,
            PreparedRiskRepository preparedRiskRepository,
            PasswordHashService passwordHashService,
            @Value("${semirisk.bootstrap.admin.enabled:true}") boolean enabled,
            @Value("${semirisk.bootstrap.admin.username:admin}") String username,
            @Value("${semirisk.bootstrap.admin.password:admin123}") String password,
            @Value("${semirisk.bootstrap.admin.display-name:系统管理员}") String displayName,
            @Value("${semirisk.bootstrap.admin.email:admin@semirisk.com}") String email) {
        this.store = store;
        this.preparedRiskRepository = preparedRiskRepository;
        this.passwordHashService = passwordHashService;
        this.enabled = enabled;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
        this.email = email;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || username.isBlank() || password.isBlank()) {
            return;
        }
        String hashedPassword = passwordHashService.hash(password);
        store.upsertLoginUser(username, hashedPassword, displayName, email, SemiriskConstants.ROLE_ADMIN);
        try {
            preparedRiskRepository.upsertSystemLoginUser(
                    "U-BOOTSTRAP-ADMIN",
                    username,
                    displayName,
                    email,
                    hashedPassword,
                    SemiriskConstants.ROLE_ADMIN,
                    "启用"
            );
            preparedRiskRepository.insertAuditLog("INFO", "bootstrap admin synchronized username=" + username);
        } catch (Exception ex) {
            // Tables may not exist yet if SchemaInitializer has not run (ContextRefreshedEvent).
            // This is expected during cold start — the admin user is already persisted in SemiRiskStore.
            log.warn("Bootstrap admin MySQL sync skipped (tables may not be ready yet): {}", ex.getMessage());
        }
    }
}
