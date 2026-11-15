
package com.redx;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.servlet.http.HttpSession;

import java.util.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final StorageService storage;
    private final JavaMailSender mail;

    @Value("${app.public-base-url}")
    private String baseUrl;

    public ApiController(
        JdbcTemplate db,
        PasswordEncoder encoder,
        StorageService storage,
        JavaMailSender mail
    ) {
        this.db = db;
        this.encoder = encoder;
        this.storage = storage;
        this.mail = mail;
    }

    private void requireLogin(HttpSession session) {
        if (session.getAttribute("userId") == null) {
            throw new RuntimeException("Silakan login terlebih dahulu");
        }
    }

    private void requireAdmin(HttpSession session) {
        requireLogin(session);

        if (!"ADMIN".equals(session.getAttribute("role"))) {
            throw new RuntimeException("Akses admin ditolak");
        }
    }

    @GetMapping("/auth/csrf")
    public String csrf(CsrfToken token) {
        return token.getToken();
    }

    @PostMapping("/auth/register")
    public Map<String,Object> register(
        @RequestBody Map<String,String> data
    ) {
        String username = data.get("username");
        String email = data.get("email");
        String password = data.get("password");

        if (username == null || email == null ||
            password == null || password.length() < 10) {
            throw new IllegalArgumentException(
                "Lengkapi data dan gunakan password minimal 10 karakter"
            );
        }

        db.update(
            """
            INSERT INTO users
            (username,email,password_hash,role)
            VALUES (?,?,?,'CUSTOMER')
            """,
            username.trim(),
            email.trim().toLowerCase(),
            encoder.encode(password)
        );

        return Map.of("message", "Pendaftaran berhasil");
    }

    @PostMapping("/auth/login")
    public Map<String,Object> login(
        @RequestBody Map<String,String> data,
        HttpSession session
    ) {
        String username = data.get("username");
        String password = data.get("password");

        List<Map<String,Object>> users = db.queryForList(
            "SELECT * FROM users WHERE username=?",
            username
        );

        if (users.isEmpty()) {
            throw new IllegalArgumentException("Login gagal");
        }

        Map<String,Object> user = users.get(0);

        if (!encoder.matches(
            password,
            (String) user.get("password_hash")
        )) {
            throw new IllegalArgumentException("Login gagal");
        }

        session.setAttribute("userId", user.get("id"));
        session.setAttribute("role", user.get("role"));
        session.setAttribute("username", user.get("username"));

        return Map.of(
            "message", "Login berhasil",
            "role", user.get("role")
        );
    }

    @PostMapping("/auth/logout")
    public Map<String,String> logout(HttpSession session) {
        session.invalidate();
        return Map.of("message", "Logout berhasil");
    }

    @GetMapping("/admin/me")
    public Map<String,Object> adminMe(HttpSession session) {
        requireAdmin(session);

        return Map.of(
            "username", session.getAttribute("username"),
            "role", "ADMIN"
        );
    }

    @GetMapping("/products")
    public List<Map<String,Object>> products() {
        return db.queryForList(
            """
            SELECT id,title,category,description,price
            FROM products WHERE active=TRUE
            ORDER BY id DESC
            """
        );
    }

    @PostMapping(
        value="/admin/products",
        consumes=MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String,Object> addProduct(
        @RequestParam String title,
        @RequestParam String category,
        @RequestParam String description,
        @RequestParam double price,
        @RequestParam MultipartFile digitalFile,
        HttpSession session
    ) throws Exception {

        requireAdmin(session);

        if (price < 0 || title.isBlank()) {
            throw new IllegalArgumentException("Data produk tidak valid");
        }

        String path = storage.save(digitalFile);

        db.update(
            """
            INSERT INTO products
            (title,category,description,price,digital_file_path)
            VALUES (?,?,?,?,?)
            """,
            title,
            category,
            description,
            price,
            path
        );

        return Map.of("message", "Produk berhasil ditambahkan");
    }

    @DeleteMapping("/admin/products/{id}")
    public Map<String,String> deleteProduct(
        @PathVariable long id,
        HttpSession session
    ) {
        requireAdmin(session);

        db.update(
            "UPDATE products SET active=FALSE WHERE id=?",
            id
        );

        return Map.of("message", "Produk dinonaktifkan");
    }

    @PostMapping("/orders")
    public Map<String,Object> createOrder(
        @RequestBody Map<String,Object> data,
        HttpSession session
    ) {
        requireLogin(session);

        long userId = ((Number)
            session.getAttribute("userId")).longValue();

        long productId = ((Number)
            data.get("productId")).longValue();

        Map<String,Object> product = db.queryForMap(
            """
            SELECT id,title,price FROM products
            WHERE id=? AND active=TRUE
            """,
            productId
        );

        String email = db.queryForObject(
            "SELECT email FROM users WHERE id=?",
            String.class,
            userId
        );

        db.update(
            """
            INSERT INTO orders
            (user_id,product_id,buyer_email,amount,status)
            VALUES (?,?,?,?,'WAITING_PAYMENT')
            """,
            userId,
            productId,
            email,
            product.get("price")
        );

        Long orderId = db.queryForObject(
            "SELECT LAST_INSERT_ID()",
            Long.class
        );

        return Map.of(
            "orderId", orderId,
            "amount", product.get("price"),
            "status", "WAITING_PAYMENT"
        );
    }

    @PostMapping(
        value="/orders/{id}/proof",
        consumes=MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public Map<String,String> uploadProof(
        @PathVariable long id,
        @RequestParam MultipartFile proof,
        HttpSession session
    ) throws Exception {

        requireLogin(session);

        long userId = ((Number)
            session.getAttribute("userId")).longValue();

        Map<String,Object> order = db.queryForMap(
            "SELECT * FROM orders WHERE id=? AND user_id=?",
            id, userId
        );

        if (!"WAITING_PAYMENT".equals(order.get("status"))) {
            throw new IllegalArgumentException("Status pesanan tidak sesuai");
        }

        String path = storage.save(proof);

        db.update(
            """
            UPDATE orders SET proof_file_path=?,
            status='PAYMENT_REVIEW' WHERE id=?
            """,
            path, id
        );

        return Map.of("message", "Bukti pembayaran terkirim");
    }

    @GetMapping("/admin/orders")
    public List<Map<String,Object>> adminOrders(
        HttpSession session
    ) {
        requireAdmin(session);

        return db.queryForList(
            """
            SELECT o.id,o.buyer_email,o.amount,o.status,
                   o.created_at,p.title
            FROM orders o
            JOIN products p ON p.id=o.product_id
            ORDER BY o.id DESC
            """
        );
    }

    private String createToken() throws Exception {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder()
            .withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return HexFormat.of().formatHex(hash);
    }

    @PostMapping("/admin/orders/{id}/approve")
    public Map<String,String> approveOrder(
        @PathVariable long id,
        HttpSession session
    ) throws Exception {

        requireAdmin(session);

        Map<String,Object> order = db.queryForMap(
            """
            SELECT o.*,p.title,p.digital_file_path
            FROM orders o JOIN products p ON p.id=o.product_id
            WHERE o.id=?
            """,
            id
        );

        if (!"PAYMENT_REVIEW".equals(order.get("status"))) {
            throw new IllegalArgumentException(
                "Pesanan belum siap diverifikasi"
            );
        }

        String token = createToken();
        String tokenHash = hashToken(token);

        db.update(
            """
            UPDATE orders
            SET status='PAID',
                download_token_hash=?,
                token_expires_at=?
            WHERE id=? AND status='PAYMENT_REVIEW'
            """,
            tokenHash,
            LocalDateTime.now().plusDays(7),
            id
        );

        String email = (String) order.get("buyer_email");
        String title = (String) order.get("title");
        String link = baseUrl + "/api/download/" + token;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(email);
        message.setSubject("Produk REDX siap diunduh");
        message.setText(
            "Pembayaran Anda telah disetujui.\n\n" +
            "Produk: " + title + "\n" +
            "Tautan unduhan: " + link + "\n\n" +
            "Tautan berlaku selama 7 hari."
        );

        mail.send(message);

        return Map.of(
            "message", "Pembayaran disetujui dan email dikirim"
        );
    }

    @GetMapping("/download/{token}")
    public ResponseEntity<Resource> download(
        @PathVariable String token
    ) throws Exception {

        String hash = hashToken(token);

        List<Map<String,Object>> rows = db.queryForList(
            """
            SELECT o.id,o.download_count,p.title,p.digital_file_path
            FROM orders o JOIN products p ON p.id=o.product_id
            WHERE o.download_token_hash=?
              AND o.status='PAID'
              AND o.token_expires_at > NOW()
              AND o.download_count < 3
            """,
            hash
        );

        if (rows.isEmpty()) {
            return ResponseEntity.status(403).build();
        }

        Map<String,Object> row = rows.get(0);
        long orderId = ((Number) row.get("id")).longValue();

        Resource file = storage.load(
            (String) row.get("digital_file_path")
        );

        db.update(
            "UPDATE orders SET download_count=download_count+1 WHERE id=?",
            orderId
        );

        String filename = file.getFilename();

        return ResponseEntity.ok()
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + filename + "\""
            )
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(file);
    }
}
