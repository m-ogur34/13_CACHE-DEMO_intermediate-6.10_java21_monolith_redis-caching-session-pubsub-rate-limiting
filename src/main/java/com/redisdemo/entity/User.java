package com.redisdemo.entity;

// JPA anotasyonları
import jakarta.persistence.*;
// Lombok
import lombok.*;

// Tarih/saat
import java.time.LocalDateTime;
// Roller için set
import java.util.Set;
import java.util.HashSet;

/**
 * Kullanıcı JPA Entity'si
 *
 * PostgreSQL'deki "users" tablosuna karşılık gelir.
 * Session bilgisi Redis'te SessionRecord olarak saklanır.
 *
 * Tablo yapısı:
 * CREATE TABLE users (
 *   id BIGSERIAL PRIMARY KEY,
 *   kullanici_adi VARCHAR(50) UNIQUE NOT NULL,
 *   email VARCHAR(255) UNIQUE NOT NULL,
 *   sifre_hash VARCHAR(255) NOT NULL,
 *   aktif BOOLEAN DEFAULT true,
 *   email_dogrulandi BOOLEAN DEFAULT false,
 *   olusturma_tarihi TIMESTAMP,
 *   son_giris_tarihi TIMESTAMP
 * );
 */
@Entity // JPA entity işaretleyicisi
@Table(name = "users") // Veritabanı tablo adı
@Getter // Tüm getter'ları otomatik üret
@Setter // Tüm setter'ları otomatik üret
@Builder // Builder pattern
@NoArgsConstructor // JPA için parametresiz constructor
@AllArgsConstructor // Tüm alanları alan constructor
public class User {

    // Birincil anahtar - otomatik artan
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Kullanıcı adı - benzersiz, en fazla 50 karakter
    @Column(name = "kullanici_adi", unique = true, nullable = false, length = 50)
    private String kullaniciAdi;

    // E-posta - benzersiz, en fazla 255 karakter
    @Column(unique = true, nullable = false, length = 255)
    private String email;

    // BCrypt ile hashlenmiş şifre - düz metin değil!
    @Column(name = "sifre_hash", nullable = false, length = 255)
    private String sifreHash;

    // Hesabın aktif olup olmadığı
    @Column(nullable = false)
    @Builder.Default
    private boolean aktif = true;

    // E-posta doğrulama durumu (OTP ile doğrulanır)
    @Column(name = "email_dogrulandi", nullable = false)
    @Builder.Default
    private boolean emailDogrulandi = false;

    // Kullanıcı rolleri - veritabanında ayrı tablo yerine JSON/koleksiyon
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "rol")
    @Builder.Default
    private Set<String> roller = new HashSet<>();

    // Kayıt tarihi - ilk kaydedildiğinde set edilir
    @Column(name = "olusturma_tarihi", nullable = false, updatable = false)
    private LocalDateTime olusturmaTarihi;

    // Son giriş tarihi - her oturum açılışında güncellenir
    @Column(name = "son_giris_tarihi")
    private LocalDateTime sonGirisTarihi;

    /**
     * İlk kayıt öncesi otomatik tarih set etme.
     */
    @PrePersist
    public void kayitOncesi() {
        // Kayıt tarihini şimdi olarak ayarla
        this.olusturmaTarihi = LocalDateTime.now();
        // Varsayılan rol ata (roller boşsa)
        if (roller.isEmpty()) {
            roller.add("ROLE_USER"); // Her yeni kullanıcı USER rolüyle başlar
        }
    }
}
