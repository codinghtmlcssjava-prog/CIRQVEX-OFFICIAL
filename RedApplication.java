
package com.redx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootApplication
public class RedxApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedxApplication.class, args);
    }

    @Bean
    CommandLineRunner createAdmin(
        JdbcTemplate db,
        PasswordEncoder encoder
    ) {
        return args -> {
            String username = System.getenv("REDX_ADMIN_USERNAME");
            String password = System.getenv("REDX_ADMIN_PASSWORD");
            String email = System.getenv("REDX_ADMIN_EMAIL");

            if (username == null || password == null || email == null) {
                System.out.println(
                    "Admin belum dibuat. Isi environment variables REDX_ADMIN_*."
                );
                return;
            }

            Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username=?",
                Integer.class,
                username
            );

            if (count != null && count == 0) {
                db.update(
                    """
                    INSERT INTO users
                    (username,email,password_hash,role)
                    VALUES (?,?,?,'ADMIN')
                    """,
                    username,
                    email,
                    encoder.encode(password)
                );

                System.out.println("Akun admin REDX berhasil dibuat.");
            }
        };
    }
}
