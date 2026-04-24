package com.redisdemo.service.cache;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Redis işlemleri için
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Zaman ve UUID
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Token Cache Servisi
 *
 * Redis ile JWT token yönetiminin 3 ana senaryosunu gösterir:
 *
 * 1. JWT BLACKLIST (Çıkış sonrası token geçersizleştirme)
 *    Redis Key: "blacklist:jwt:{jti}"
 *    Değer: "revoked" (string)
 *    TTL: Access token'ın kalan süresi
 *
 * 2. REFRESH TOKEN SAKLAMA
 *    Redis Key: "refresh:{userId}"
 *    Değer: Refresh token string'i
 *    TTL: 7 gün
 *
 * 3. EMAIL VERIFICATION TOKEN
 *    Redis Key: "email-verify:{email}"
 *    Değer: UUID token
 *    TTL: 24 saat
 *
 * Neden Redis? Token bilgilerini veritabanında saklamak yerine
 * Redis kullanmak çok daha hızlıdır. Saniyede yüz binlerce
 * token kontrolü yapılabilir.
 */
@Service // Spring servis bean'i
public class TokenCacheService {

    // Logger - token işlemlerini kayıt al
    private static final Logger log = LoggerFactory.getLogger(TokenCacheService.class);

    // ── REDIS ANAHTAR ÖNEKLERİ ────────────────────────────────────────────────
    // Token kara listesi anahtarı öneki
    private static final String BLACKLIST_ONEKI = "blacklist:jwt:";
    // Refresh token anahtarı öneki
    private static final String REFRESH_TOKEN_ONEKI = "refresh:";
    // Email doğrulama token anahtarı öneki
    private static final String EMAIL_VERIFY_ONEKI = "email-verify:";
    // Şifre sıfırlama token anahtarı öneki
    private static final String SIFRE_SIFIRLAMA_ONEKI = "password-reset:";

    // Refresh token geçerlilik süresi (saniye) - uygulama.yml'den okunur
    @Value("${app.jwt.refresh-token-expiry:604800}")
    private long refreshTokenSuresi;

    // String Redis işlemleri için (token değerleri basit string)
    private final StringRedisTemplate stringRedisTemplate;

    // Genel Redis işlemleri için
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public TokenCacheService(StringRedisTemplate stringRedisTemplate,
                             RedisTemplate<String, Object> redisTemplate) {
        // String template bağımlılığını ata
        this.stringRedisTemplate = stringRedisTemplate;
        // Genel template bağımlılığını ata
        this.redisTemplate = redisTemplate;
    }

    // ── 1. JWT BLACKLIST ───────────────────────────────────────────────────────

    /**
     * JWT token'ı kara listeye ekler (kullanıcı çıkış yaptığında).
     *
     * Sorun: JWT'ler stateless'tır - bir kez imzalandıktan sonra
     * sunucu tarafında iptal edilemez. Çözüm: Redis kara listesi.
     *
     * Kullanıcı çıkış yaptığında token Redis'e eklenir.
     * Her istek geldiğinde token kara listede var mı kontrol edilir.
     *
     * @param jti           JWT ID (token'ın benzersiz kimliği)
     * @param kalanSureSn   Token'ın kalan geçerlilik süresi (saniye)
     */
    public void tokenKaraListeyeEkle(String jti, long kalanSureSn) {
        // Kara liste Redis anahtarını oluştur
        var anahtar = BLACKLIST_ONEKI + jti;

        // Token'ı kara listeye ekle - değer önemsiz, varlık önemli
        // TTL = token'ın kalan süresi (süre dolunca Redis otomatik siler)
        stringRedisTemplate.opsForValue().set(anahtar, "revoked", Duration.ofSeconds(kalanSureSn));

        // İşlemi kayıt al
        log.info("Token kara listeye eklendi: jti={}, ttl={}s", jti, kalanSureSn);
    }

    /**
     * Token'ın kara listede olup olmadığını kontrol eder.
     * Her korumalı API isteğinde çağrılır.
     *
     * @param jti JWT ID
     * @return true ise token iptal edilmiş (erişim reddedilmeli)
     */
    public boolean tokenKaraListede(String jti) {
        // Kara liste anahtarını oluştur
        var anahtar = BLACKLIST_ONEKI + jti;

        // Redis'te bu anahtar var mı? (Boolean döner)
        var mevcutMu = stringRedisTemplate.hasKey(anahtar);

        // Log: kara liste kontrolü sonucu
        log.debug("Token kara liste kontrolü: jti={}, sonuç={}", jti, mevcutMu);

        // null guard - Redis bağlantı sorununda false dön (güvenli taraf)
        return Boolean.TRUE.equals(mevcutMu);
    }

    // ── 2. REFRESH TOKEN SAKLAMA ───────────────────────────────────────────────

    /**
     * Refresh token'ı Redis'e kaydeder.
     *
     * Access token'lar kısa ömürlüdür (15 dk). Süresi dolduğunda
     * kullanıcıdan tekrar giriş istemek yerine refresh token ile
     * yeni access token alınır.
     *
     * Yapı: "refresh:{userId}" → "{refreshTokenString}"
     *
     * @param userId       Kullanıcı ID'si
     * @param refreshToken Yeni refresh token değeri
     */
    public void refreshTokenKaydet(String userId, String refreshToken) {
        // Refresh token anahtarını oluştur
        var anahtar = REFRESH_TOKEN_ONEKI + userId;

        // Redis'e kaydet - TTL = 7 gün (uygulama ayarlarından)
        stringRedisTemplate.opsForValue().set(
                anahtar,                              // Anahtar
                refreshToken,                         // Token değeri
                Duration.ofSeconds(refreshTokenSuresi) // 7 günlük TTL
        );

        // İşlemi kayıt al
        log.info("Refresh token kaydedildi: userId={}", userId);
    }

    /**
     * Kullanıcının mevcut refresh token'ını getirir.
     *
     * @param userId Kullanıcı ID'si
     * @return Refresh token (yoksa boş Optional)
     */
    public Optional<String> refreshTokenGetir(String userId) {
        // Refresh token anahtarını oluştur
        var anahtar = REFRESH_TOKEN_ONEKI + userId;

        // Redis'ten oku
        var token = stringRedisTemplate.opsForValue().get(anahtar);

        // Optional sarmalayıcısına al
        return Optional.ofNullable(token);
    }

    /**
     * Refresh token'ı doğrular.
     *
     * @param userId       Kullanıcı ID'si
     * @param refreshToken Doğrulanacak token
     * @return true ise token geçerli ve eşleşiyor
     */
    public boolean refreshTokenGecerliMi(String userId, String refreshToken) {
        // Saklanan token'ı getir
        var saklananToken = refreshTokenGetir(userId);

        // Saklanan token ile gelen token eşleşiyor mu?
        return saklananToken.isPresent() && saklananToken.get().equals(refreshToken);
    }

    /**
     * Refresh token'ı siler (çıkış veya token rotasyonunda).
     *
     * @param userId Kullanıcı ID'si
     */
    public void refreshTokenSil(String userId) {
        // Refresh token anahtarını oluştur
        var anahtar = REFRESH_TOKEN_ONEKI + userId;

        // Redis'ten sil
        stringRedisTemplate.delete(anahtar);

        // İşlemi kayıt al
        log.info("Refresh token silindi: userId={}", userId);
    }

    /**
     * Token rotasyonu - eski refresh token'ı sil, yeni oluştur ve kaydet.
     * Güvenlik için her access token yenilemede refresh token da değiştirilir.
     *
     * @param userId           Kullanıcı ID'si
     * @param eskiRefreshToken Doğrulanacak eski token
     * @return Yeni refresh token (geçersiz eski token için boş Optional)
     */
    public Optional<String> tokenRotasyonu(String userId, String eskiRefreshToken) {
        // Eski token geçerli mi kontrol et
        if (!refreshTokenGecerliMi(userId, eskiRefreshToken)) {
            // Token geçersiz veya çalınmış olabilir - güvenlik uyarısı
            log.warn("Geçersiz refresh token rotasyon girişimi: userId={}", userId);
            return Optional.empty(); // Boş döndür - erişim reddedilecek
        }

        // Yeni benzersiz refresh token üret
        var yeniToken = UUID.randomUUID().toString();

        // Yeni token'ı kaydet (eskinin üzerine yazar)
        refreshTokenKaydet(userId, yeniToken);

        // İşlemi kayıt al
        log.info("Token rotasyonu tamamlandı: userId={}", userId);

        // Yeni token'ı döndür
        return Optional.of(yeniToken);
    }

    // ── 3. EMAIL VERIFICATION TOKEN ───────────────────────────────────────────

    /**
     * E-posta doğrulama token'ı oluşturur ve Redis'e kaydeder.
     *
     * Kullanıcı kayıt olduğunda:
     * 1. Benzersiz UUID token oluştur
     * 2. Redis'e 24 saatlik TTL ile kaydet
     * 3. Kullanıcıya e-posta gönder (token ile link içerir)
     *
     * @param email Doğrulanacak e-posta adresi
     * @return Oluşturulan doğrulama token'ı
     */
    public String emailDogrulamaTokeniOlustur(String email) {
        // Benzersiz UUID token oluştur
        var token = UUID.randomUUID().toString();

        // Redis anahtarı
        var anahtar = EMAIL_VERIFY_ONEKI + email;

        // Token'ı 24 saat TTL ile Redis'e kaydet
        stringRedisTemplate.opsForValue().set(anahtar, token, Duration.ofHours(24));

        // İşlemi kayıt al
        log.info("E-posta doğrulama token'ı oluşturuldu: email={}", email);

        // Token'ı döndür (e-posta göndermek için)
        return token;
    }

    /**
     * E-posta doğrulama token'ını doğrular.
     *
     * @param email E-posta adresi
     * @param token Doğrulanacak token
     * @return true ise token geçerli
     */
    public boolean emailTokenDogrula(String email, String token) {
        // Redis anahtarını oluştur
        var anahtar = EMAIL_VERIFY_ONEKI + email;

        // Saklanan token'ı Redis'ten oku
        var saklananToken = stringRedisTemplate.opsForValue().get(anahtar);

        // Saklanan token ile gelen token eşleşiyor mu?
        var gecerliMi = saklananToken != null && saklananToken.equals(token);

        // Doğrulanmışsa token'ı Redis'ten sil (tek kullanımlık)
        if (gecerliMi) {
            stringRedisTemplate.delete(anahtar); // Kullanıldı, sil
            log.info("E-posta doğrulama başarılı, token silindi: email={}", email);
        }

        // Doğrulama sonucunu döndür
        return gecerliMi;
    }

    // ── 4. ŞİFRE SIFIRLAMA TOKEN ──────────────────────────────────────────────

    /**
     * Şifre sıfırlama token'ı oluşturur.
     * Kullanıcı "Şifremi Unuttum" tıkladığında çağrılır.
     *
     * @param email Kullanıcı e-postası
     * @return Şifre sıfırlama token'ı (e-postayla gönderilir)
     */
    public String sifreSifirlamaTokeniOlustur(String email) {
        // Benzersiz sıfırlama token'ı üret
        var token = UUID.randomUUID().toString();

        // Redis anahtarı
        var anahtar = SIFRE_SIFIRLAMA_ONEKI + email;

        // Token'ı 1 saatlik TTL ile kaydet (güvenlik: kısa süre)
        stringRedisTemplate.opsForValue().set(anahtar, token, Duration.ofHours(1));

        // İşlemi kayıt al
        log.info("Şifre sıfırlama token'ı oluşturuldu: email={}", email);

        // Token'ı döndür
        return token;
    }

    /**
     * Şifre sıfırlama token'ını doğrular ve siler.
     *
     * @param email E-posta
     * @param token Doğrulanacak token
     * @return true ise token geçerli
     */
    public boolean sifreSifirlamaTokenDogrula(String email, String token) {
        // Redis anahtarı
        var anahtar = SIFRE_SIFIRLAMA_ONEKI + email;

        // Saklanan token'ı oku
        var saklananToken = stringRedisTemplate.opsForValue().get(anahtar);

        // Eşleşme kontrolü
        var gecerliMi = saklananToken != null && saklananToken.equals(token);

        // Geçerliyse sil (tek kullanımlık - replay saldırısını önle)
        if (gecerliMi) {
            stringRedisTemplate.delete(anahtar);
            log.info("Şifre sıfırlama token'ı kullanıldı ve silindi: email={}", email);
        } else {
            log.warn("Geçersiz şifre sıfırlama token'ı: email={}", email);
        }

        return gecerliMi;
    }
}
