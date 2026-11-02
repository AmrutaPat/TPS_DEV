package com.example.transactions.config;

import com.example.transactions.domain.Account;
import com.example.transactions.repository.AccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class DemoDataConfig {
    @Bean
    CommandLineRunner seedAccounts(AccountRepository repository) {
        return args -> {
            if (!repository.existsById(1001L)) {
                repository.save(new Account(1001L, new BigDecimal("10000.00"), 0));
            }
            if (!repository.existsById(1002L)) {
                repository.save(new Account(1002L, new BigDecimal("5000.00"), 0));
            }
        };
    }
}
