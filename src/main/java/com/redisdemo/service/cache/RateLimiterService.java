package com.redisdemo.service.cache;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Redis işlemleri
import org.springframework.data.redis.core.StringRedisTemplate;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Zaman
import java.time.Duration;

/**
 * Rate Limiter Servisi - Redis ile Hız Sınırlama
 *
 * Belirli bir zaman penceresi içinde kullanıcının yapabileceği
 * maksimum istek sayısını sınırlar. DDoS ve brute-force saldırılarına
 * karşı koruma sağlar.
 *
 * Algoritma: Fixed Window Counter (Sabit Pencere Sayacı)
 *
 * Nasıl çalışır?
 * 1. İlk istekte: INCR rate:limit:{userId} → sayaç = 1
 *    Eğer 1 ise (ilk istek): EXPIRE rate:limit:{userId} 60 (60 saniyelik pencere)
 * 2. Sonraki isteklerde: INCR → sayaç artar
 * 3. Sayaç > maxIstek ise: istek reddedilir (429 Too Many Requests)
 * 4. 60 saniye sonra: Redis otomatik anahtarı siler, pencere sıfırlanır
 *
 * Redis Atomic Operations:
 * INCR komutu atomiktir - eşzamanlı isteklerde race condition olmaz!
 * Bu Redis'in büyük avantajlarından biridir.
 *
 * Farklı Rate Limit Kuralları:
 * - API istekleri: 100/dakika
 * - Giriş denemeleri: 5/dakika (brute-force koruması)
 * - E-posta gönderme: 3/saat
 * - OTP isteme: 3/15 dakika
 */
@Service // Spring servis bean'i
public class RateLimiterService {

    // Logger - rate limit ihlallerini izle
    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    // Rate limit anahtar öneki
    private static final String RATE_LIMIT_ONEKI = "rate:limit:";
    // Giriş denemesi limit anahtarı
    private static final String GIRIS_ONEKI = "rate:login:";
    // E-posta limit anahtarı
    private static final String EMAIL_ONEKI = "rate:email:";
    // OTP isteme limit anahtarı
    private static final String OTP_ISTEK_ONEKI = "rate:otp:";

    // Maksimum istek sayısı - uygulama.yml'den okunur
    @Value("${app.cache.rate-limit-max:100}")
    private long maxIstek;

    // Rate limit zaman penceresi (saniye) - uygulama.yml'den okunur
    @Value("${app.cache.rate-limit-window:60}")
    private long pencereSuresiSaniye;

    // String Redis template - sayaç işlemleri için
    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public RateLimiterService(StringRedisTemplate stringRedisTemplate) {
        // StringRedisTemplate bağımlılığını ata
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ── GENEL API RATE LIMIT ──────────────────────────────────────────────────

    /**
     * Genel API rate limit kontrolü.
     *
     * Her kullanıcı/IP için 1 dakikada en fazla N istek.
     * INCR Redis komutu: Değeri 1 artırır, yoksa 1 oluşturur (atomik!)
     *
     * @param kimlik Kullanıcı ID'si veya IP adresi
     * @return true ise istek izin verilir, false ise reddedilir
     */
    public boolean istekIzniVarMi(String kimlik) {
        // Rate limit Redis anahtarını oluştur
        var anahtar = RATE_LIMIT_ONEKI + kimlik;

        // INCR komutuyla sayacı atomik olarak artır
        var sayac = stringRedisTemplate.opsForValue().increment(anahtar);

        // İlk istek ise (sayaç = 1): TTL ayarla
        if (sayac != null && sayac == 1) {
            // Zaman penceresini başlat (60 saniye)
            stringRedisTemplate.expire(anahtar, Duration.ofSeconds(pencereSuresiSaniye));
            log.debug("Rate limit penceresi başlatıldı: kimlik={}, pencere={}s", kimlik, pencereSuresiSaniye);
        }

        // Sayaç limiti aştı mı?
        if (sayac != null && sayac > maxIstek) {
            // Limit aşıldı - istekte bulunan zamanı log'a yaz
            log.warn("RATE LIMIT AŞILDI: kimlik={}, sayaç={}, limit={}", kimlik, sayac, maxIstek);
            return false; // İstek reddedilir
        }

        // Limit dahilinde - izin ver
        log.debug("Rate limit OK: kimlik={}, sayaç={}/{}", kimlik, sayac, maxIstek);
        return true;
    }

    /**
     * Mevcut penceredeki istek sayısını döndürür.
     *
     * @param kimlik Kullanıcı ID'si veya IP adresi
     * @return Mevcut penceredeki istek sayısı
     */
    public long mevcutIstemSayisi(String kimlik) {
        // Rate limit anahtarını oluştur
        var anahtar = RATE_LIMIT_ONEKI + kimlik;

        // Redis'ten mevcut sayacı oku
        var deger = stringRedisTemplate.opsForValue().get(anahtar);

        // String'i long'a dönüştür (null kontrolü)
        return deger != null ? Long.parseLong(deger) : 0L;
    }

    /**
     * Bir sonraki pencere başlayana kadar kalan süreyi döndürür.
     *
     * @param kimlik Kullanıcı ID'si veya IP adresi
     * @return Kalan saniye (-1 ise TTL yok, -2 ise anahtar yok)
     */
    public long kalanSure(String kimlik) {
        // Rate limit anahtarını oluştur
        var anahtar = RATE_LIMIT_ONEKI + kimlik;

        // Redis'ten kalan TTL'yi getir (saniye cinsinden)
        var kalanTtl = stringRedisTemplate.getExpire(anahtar);

        // null ise 0 döndür
        return kalanTtl != null ? kalanTtl : 0L;
    }

    // ── GİRİŞ DENEMESI RATE LIMIT ─────────────────────────────────────────────

    /**
     * Giriş denemelerini sınırlar - brute-force koruması.
     * 5 dakikada en fazla 5 başarısız giriş girişimi.
     *
     * @param kullaniciAdi Giriş yapılmaya çalışılan kullanıcı adı
     * @return true ise giriş denemesine izin ver
     */
    public boolean girisDenemesiIzniVarMi(String kullaniciAdi) {
        // Giriş denemesi anahtarı
        var anahtar = GIRIS_ONEKI + kullaniciAdi;

        // Sayacı artır (atomik)
        var sayac = stringRedisTemplate.opsForValue().increment(anahtar);

        // İlk denemede 5 dakikalık pencere aç
        if (sayac != null && sayac == 1) {
            // Giriş denemesi için 5 dakikalık pencere
            stringRedisTemplate.expire(anahtar, Duration.ofMinutes(5));
        }

        // 5 denemeden fazlaysa reddet
        if (sayac != null && sayac > 5) {
            // Güvenlik uyarısı - brute-force saldırısı şüphesi
            log.warn("BRUTE-FORCE ŞÜPHESI: kullaniciAdi={}, deneme={}", kullaniciAdi, sayac);
            return false; // Giriş reddedildi
        }

        return true; // Denemeye izin ver
    }

    /**
     * Başarılı giriş sonrası deneme sayacını sıfırlar.
     *
     * @param kullaniciAdi Giriş yapan kullanıcı adı
     */
    public void basariliGirisSonrasiSifirla(String kullaniciAdi) {
        // Giriş denemesi anahtarını oluştur
        var anahtar = GIRIS_ONEKI + kullaniciAdi;

        // Başarılı girişte sayacı sil (sıfırla)
        stringRedisTemplate.delete(anahtar);

        log.info("Giriş denemesi sayacı sıfırlandı: kullaniciAdi={}", kullaniciAdi);
    }

    // ── E-POSTA GÖNDERME RATE LIMIT ───────────────────────────────────────────

    /**
     * E-posta gönderme hızını sınırlar.
     * Saatte en fazla 3 e-posta gönderilir.
     *
     * @param email Hedef e-posta adresi
     * @return true ise gönderme izni var
     */
    public boolean emailGondermeleIzniVarMi(String email) {
        // E-posta rate limit anahtarı
        var anahtar = EMAIL_ONEKI + email;

        // Sayacı atomik artır
        var sayac = stringRedisTemplate.opsForValue().increment(anahtar);

        // İlk gönderimde 1 saatlik pencere aç
        if (sayac != null && sayac == 1) {
            // 1 saatlik pencere - e-posta limiti daha uzun tutulur
            stringRedisTemplate.expire(anahtar, Duration.ofHours(1));
        }

        // Saatte 3'ten fazla e-posta gönderilmesin
        if (sayac != null && sayac > 3) {
            log.warn("E-posta rate limit aşıldı: email={}", email);
            return false; // Gönderme reddedildi
        }

        return true; // Gönderme izni var
    }

    // ── OTP RATE LIMIT ────────────────────────────────────────────────────────

    /**
     * OTP isteme hızını sınırlar.
     * 15 dakikada en fazla 3 OTP isteği.
     *
     * @param email OTP istenen e-posta
     * @return true ise OTP gönderilebilir
     */
    public boolean otpIstegiIzniVarMi(String email) {
        // OTP istek anahtarı
        var anahtar = OTP_ISTEK_ONEKI + email;

        // Sayacı artır
        var sayac = stringRedisTemplate.opsForValue().increment(anahtar);

        // İlk istekte 15 dakikalık pencere
        if (sayac != null && sayac == 1) {
            // 15 dakikalık pencere
            stringRedisTemplate.expire(anahtar, Duration.ofMinutes(15));
        }

        // 3 istekten fazlasına izin verme
        if (sayac != null && sayac > 3) {
            log.warn("OTP istek limiti aşıldı: email={}", email);
            return false; // OTP gönderme reddedildi
        }

        return true; // OTP gönderilebilir
    }

    /**
     * Belirli bir IP adresini geçici olarak engeller.
     *
     * @param ipAdresi     Engellenecek IP
     * @param sureDakika   Engelleme süresi (dakika)
     */
    public void ipEngelle(String ipAdresi, long sureDakika) {
        // IP engelleme anahtarı
        var anahtar = "blocked:ip:" + ipAdresi;

        // IP'yi belirtilen süre için engelle
        stringRedisTemplate.opsForValue().set(anahtar, "blocked", Duration.ofMinutes(sureDakika));

        // Güvenlik kaydı
        log.warn("IP adresi engellendi: ip={}, süre={}dk", ipAdresi, sureDakika);
    }

    /**
     * IP adresinin engellenip engellenmediğini kontrol eder.
     *
     * @param ipAdresi Kontrol edilecek IP
     * @return true ise IP engellenmiş
     */
    public boolean ipEngelliMi(String ipAdresi) {
        // IP engelleme anahtarını kontrol et
        var anahtar = "blocked:ip:" + ipAdresi;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(anahtar));
    }
}
