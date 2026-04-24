package com.redisdemo.repository;

// JPA entity
import com.redisdemo.entity.User;
// Spring Data JPA
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
// Spring stereotype
import org.springframework.stereotype.Repository;
// Transaction anotasyonu
import org.springframework.transaction.annotation.Transactional;

// Dönen tipler
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Kullanıcı Repository'si
 *
 * Kullanıcı sorguları ve güncelleme işlemlerini kapsar.
 * Auth ve session yönetimi için kullanılır.
 */
@Repository // Spring repository bean'i
public interface UserRepository extends JpaRepository<User, Long> {

    // Kullanıcı adına göre kullanıcı bul (login için)
    Optional<User> findByKullaniciAdi(String kullaniciAdi);

    // E-postaya göre kullanıcı bul (OTP doğrulama için)
    Optional<User> findByEmail(String email);

    // Kullanıcı adı veya e-posta ile bul (esnek arama)
    Optional<User> findByKullaniciAdiOrEmail(String kullaniciAdi, String email);

    // Kullanıcı adının mevcut olup olmadığını kontrol et (kayıt için)
    boolean existsByKullaniciAdi(String kullaniciAdi);

    // E-postanın mevcut olup olmadığını kontrol et (kayıt için)
    boolean existsByEmail(String email);

    // E-posta doğrulama durumunu güncelle
    @Modifying // Bu metodun veritabanını değiştirdiğini belirt
    @Transactional // Güncelleme için transaction gerekli
    @Query("UPDATE User u SET u.emailDogrulandi = true WHERE u.email = :email")
    int emailDogrula(@Param("email") String email);

    // Son giriş tarihini güncelle (her başarılı girişte)
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.sonGirisTarihi = :tarih WHERE u.id = :id")
    void sonGirisTarihiGuncelle(@Param("id") Long id, @Param("tarih") LocalDateTime tarih);

    // Aktif ve e-postası doğrulanmış kullanıcı bul
    Optional<User> findByKullaniciAdiAndAktifTrueAndEmailDogrulandiTrue(String kullaniciAdi);
}
