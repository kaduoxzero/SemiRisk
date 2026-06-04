package com.semirisk.config;

import com.semirisk.common.SemiriskConstants;
import com.semirisk.repository.PreparedRiskRepository;
import com.semirisk.security.PasswordHashService;
import com.semirisk.service.SemiRiskStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminProvisioner implements ApplicationRunner {

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
            @Value("${semirisk.bootstrap.admin.username:kaduoxli}") String username,
            @Value("${semirisk.bootstrap.admin.password:123qwe123}") String password,
            @Value("${semirisk.bootstrap.admin.display-name:kaduoxli}") String displayName,
            @Value("${semirisk.bootstrap.admin.email:600000002@qq.com}") String email) {
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
        store.upsertLoginUser(username, password, displayName, email, SemiriskConstants.ROLE_ADMIN);
        try {
            preparedRiskRepository.upsertSystemLoginUser(
                    "U-BOOTSTRAP-ADMIN",
                    username,
                    displayName,
                    email,
                    passwordHashService.hash(password),
                    SemiriskConstants.ROLE_ADMIN,
                    "启用"
            );
            preparedRiskRepository.insertAuditLog("INFO", "bootstrap admin synchronized username=" + username);
        } catch (Exception ignored) {
            // VM middleware may be offline during local development; the in-memory account remains usable.
        }
    }
}
