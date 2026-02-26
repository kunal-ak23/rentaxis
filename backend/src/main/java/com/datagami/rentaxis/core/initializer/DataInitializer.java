package com.datagami.rentaxis.core.initializer;

import com.datagami.rentaxis.core.service.UserService;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final UserService userService;

    public DataInitializer(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Override
    public void run(String... args) {
        if (userRepository.count() == 0) {
            log.info("No users found. Creating default Super Admin...");
            userService.createUser(
                    "admin@rentaxis.com",
                    "admin123",
                    "System Admin",
                    UserRole.SUPER_ADMIN,
                    null,
                    null);
            log.info("Default Super Admin created successfully.");
        }
    }
}
