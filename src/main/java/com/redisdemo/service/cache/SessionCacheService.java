package com.redisdemo.service.cache;

// Uygulama DTO
import com.redisdemo.dto.records.SessionRecord;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Redis işlemleri
import org.springframework.data.redis.core.RedisTemplate;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Zaman ve koleksiyon
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Kullanıcı Session Cache Servisi
 *
 * Kullanıcı oturumlarını Redis'te saklar ve yönetir.
 *
 * Session Yönetimi Stratejisi:
 * - JWT Stateless + Redis Session Hibrit yaklaşımı
 * - JWT: kimlik doğrulama (imza ile)
 * - Redis Session: oturum durumu (aktif mi? hangi cihazdan? vb.)
 *
 * Redis'te session saklamanın avantajları:
 * - Anında oturum sonlandırma (logout her cihazdan)
 * - Son aktivite takibi
 * - Eşzamanlı oturum sayısı kontrolü
 * - Cihaz/IP bazlı oturum yönetimi
 *
 * Redis Yapısı:
 *   "session:{userId}" → SessionRecord (JSON)  [TTL: 30 dk]
 */
@Service // Spring servis bean'i
public class SessionCacheService {

    // Logger - session işlemlerini izle
    private static final Logger log = LoggerFactory.getLogger(SessionCacheService.class);

    // Session Redis anahtar öneki
    private static final String SESSION_ONEKI = "session:";

    // Session TTL (saniye) - uygulama.yml'den okunur
    @Value("${app.cache.user-session-ttl:1800}")
    private long sessionTtlSaniye;

    // Redis template - session nesnelerini JSON olarak sakla
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public SessionCacheService(RedisTemplate<String, Object> redisTemplate) {
        // RedisTemplate bağımlılığını ata
        this.redisTemplate = redisTemplate;
    }

    // ── SESSION OLUŞTURMA ─────────────────────────────────────────────────────

    /**
     * Kullanıcı session'ı oluşturur ve Redis'e kaydeder.
     *
     * @param kullaniciId  Kullanıcı ID'si
     * @param kullaniciAdi Kullanıcı adı
     * @param email        E-posta
     * @param roller       Kullanıcı rolleri
     * @param ipAdresi     İstemci IP adresi
     * @param cihazBilgisi User-Agent
     * @return Oluşturulan session kaydı
     */
    public SessionRecord sessionOlustur(
            String kullaniciId,
            String kullaniciAdi,
            String email,
            List<String> roller,
            String ipAdresi,
            String cihazBilgisi) {

        // Yeni session record'u oluştur
        var session = new SessionRecord(
                kullaniciId,       // Kullanıcı ID
                kullaniciAdi,      // Kullanıcı adı
                email,             // E-posta
                roller,            // Roller
                Instant.now(),     // Giriş zamanı
                Instant.now(),     // Son aktivite (şimdi)
                ipAdresi,          // IP adresi
                cihazBilgisi       // Cihaz bilgisi
        );

        // Redis anahtarını oluştur
        var anahtar = SESSION_ONEKI + kullaniciId;

        // Session'ı Redis'e kaydet (TTL: 30 dakika)
        redisTemplate.opsForValue().set(anahtar, session, Duration.ofSeconds(sessionTtlSaniye));

        log.info("Session oluşturuldu: kullaniciId={}, ip={}", kullaniciId, ipAdresi);

        return session;
    }

    // ── SESSION OKUMA ─────────────────────────────────────────────────────────

    /**
     * Kullanıcı session'ını getirir.
     *
     * @param kullaniciId Kullanıcı ID'si
     * @return Session kaydı (yoksa boş Optional)
     */
    public Optional<SessionRecord> sessionGetir(String kullaniciId) {
        // Session anahtarı
        var anahtar = SESSION_ONEKI + kullaniciId;

        // Redis'ten oku
        var session = redisTemplate.opsForValue().get(anahtar);

        // Tip kontrolü (instanceof pattern matching - Java 16+)
        if (session instanceof SessionRecord sr) {
            // TTL'i yenile - aktif kullanıcının session'ı uzasın
            redisTemplate.expire(anahtar, Duration.ofSeconds(sessionTtlSaniye));
            return Optional.of(sr);
        }

        // Session bulunamadı
        log.debug("Session bulunamadı: kullaniciId={}", kullaniciId);
        return Optional.empty();
    }

    // ── SESSION GÜNCELLEME ────────────────────────────────────────────────────

    /**
     * Son aktivite zamanını günceller.
     * Her API isteğinde çağrılarak session'ın aktif kalması sağlanır.
     *
     * @param kullaniciId Kullanıcı ID'si
     */
    public void aktiviteGuncelle(String kullaniciId) {
        // Mevcut session'ı getir
        var sessionOpt = sessionGetir(kullaniciId);

        if (sessionOpt.isPresent()) {
            // Aktivite zamanını güncelle (immutable record → yeni nesne)
            var guncellenenSession = sessionOpt.get().aktiviteGuncelle();

            // Session anahtarı
            var anahtar = SESSION_ONEKI + kullaniciId;

            // Güncellenmiş session'ı tekrar kaydet (TTL yenilenir)
            redisTemplate.opsForValue().set(anahtar, guncellenenSession, Duration.ofSeconds(sessionTtlSaniye));
        }
    }

    // ── SESSION SONLANDIRMA ───────────────────────────────────────────────────

    /**
     * Kullanıcı session'ını sonlandırır (logout).
     *
     * @param kullaniciId Çıkış yapan kullanıcı ID'si
     */
    public void sessionSonlandir(String kullaniciId) {
        // Session anahtarı
        var anahtar = SESSION_ONEKI + kullaniciId;

        // Redis'ten session'ı sil
        redisTemplate.delete(anahtar);

        log.info("Session sonlandırıldı: kullaniciId={}", kullaniciId);
    }

    /**
     * Session'ın geçerli olup olmadığını kontrol eder.
     *
     * @param kullaniciId Kullanıcı ID'si
     * @return true ise aktif session var
     */
    public boolean aktifSessionVarMi(String kullaniciId) {
        // Session anahtarı Redis'te var mı?
        return Boolean.TRUE.equals(redisTemplate.hasKey(SESSION_ONEKI + kullaniciId));
    }
}
