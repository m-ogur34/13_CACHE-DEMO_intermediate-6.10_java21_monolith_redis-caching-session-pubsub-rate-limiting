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

// Rastgele sayı üretimi ve zaman
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;

/**
 * OTP (One Time Password) Servisi
 *
 * Redis ile tek kullanımlık şifre yönetimi yapar.
 *
 * Neden Redis?
 * - OTP kısa ömürlüdür (5 dakika) → TTL ile otomatik sona erer
 * - Tek kullanımlık → Doğrulama sonrası silinir
 * - Hızlı erişim → Milyonlarca OTP saniyede kontrol edilebilir
 * - Veritabanı şişmez → Süresi dolan OTP'ler Redis'in siler
 *
 * Redis Yapısı:
 *   "otp:{email}" → "123456"  (TTL: 5 dakika)
 *   "otp:{telefon}" → "789012" (TTL: 5 dakika)
 *
 * Kullanım Senaryoları:
 * 1. İki faktörlü doğrulama (2FA)
 * 2. Telefon numarası doğrulama
 * 3. E-posta doğrulama
 * 4. İşlem onaylama (büyük transfer vb.)
 *
 * Güvenlik Önlemleri:
 * - SecureRandom kullanımı (Math.random() değil!)
 * - Tek kullanımlık (doğrulama sonrası silinir)
 * - Kısa TTL (5 dakika)
 * - Deneme limiti (RateLimiterService ile birlikte kullanılır)
 */
@Service // Spring servis bean'i
public class OtpService {

    // Logger - OTP işlemlerini izle
    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    // OTP Redis anahtar öneki
    private static final String OTP_ONEKI = "otp:";

    // OTP deneme sayısı öneki
    private static final String OTP_DENEME_ONEKI = "otp:deneme:";

    // OTP geçerlilik süresi (saniye) - uygulama.yml'den okunur
    @Value("${app.cache.otp-ttl:300}")
    private long otpTtlSaniye;

    // Güvenli rastgele sayı üreticisi
    private final SecureRandom guvenliRastgele = new SecureRandom();

    // String Redis template - OTP string değeri için
    private final StringRedisTemplate stringRedisTemplate;

    @Autowired
    public OtpService(StringRedisTemplate stringRedisTemplate) {
        // StringRedisTemplate bağımlılığını ata
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // ── OTP ÜRETME ────────────────────────────────────────────────────────────

    /**
     * 6 haneli OTP oluşturur ve Redis'e kaydeder.
     *
     * Güvenli rastgele sayı: SecureRandom sınıfı kriptografik olarak
     * güvenli rastgele sayılar üretir (Math.random() değil!).
     *
     * @param hedef E-posta veya telefon numarası
     * @return Oluşturulan 6 haneli OTP
     */
    public String otpOlustur(String hedef) {
        // 6 haneli OTP üret: 100000 ile 999999 arası
        var otp = String.format("%06d", guvenliRastgele.nextInt(900000) + 100000);

        // Redis anahtarını oluştur
        var anahtar = OTP_ONEKI + hedef;

        // Yeni OTP oluşturmadan önce eski OTP'yi sil (eğer varsa)
        stringRedisTemplate.delete(anahtar);

        // OTP'yi Redis'e kaydet - TTL: 5 dakika
        stringRedisTemplate.opsForValue().set(anahtar, otp, Duration.ofSeconds(otpTtlSaniye));

        // Deneme sayacını sıfırla (yeni OTP ile yeni şans)
        stringRedisTemplate.delete(OTP_DENEME_ONEKI + hedef);

        // Güvenlik için OTP'nin tamamını log'a yazma!
        log.info("OTP oluşturuldu: hedef={}, ttl={}s", hedef, otpTtlSaniye);

        // OTP'yi döndür (e-posta veya SMS ile göndermek için)
        return otp;
    }

    /**
     * Belirli uzunlukta özel OTP oluşturur.
     *
     * @param hedef  E-posta veya telefon
     * @param uzunluk OTP uzunluğu (4-8 arası)
     * @return Oluşturulan OTP
     */
    public String otpOlusturOzel(String hedef, int uzunluk) {
        // Uzunluğu sınırla (4-8 arası)
        var gecerliUzunluk = Math.max(4, Math.min(8, uzunluk));

        // Maksimum değeri hesapla (örn: uzunluk=6 → max=999999)
        var maks = (int) Math.pow(10, gecerliUzunluk) - 1;
        var min = (int) Math.pow(10, gecerliUzunluk - 1);

        // Güvenli rastgele OTP üret
        var otp = String.format("%0" + gecerliUzunluk + "d",
                guvenliRastgele.nextInt(maks - min + 1) + min);

        // Redis'e kaydet
        var anahtar = OTP_ONEKI + hedef;
        stringRedisTemplate.opsForValue().set(anahtar, otp, Duration.ofSeconds(otpTtlSaniye));

        // Log
        log.info("Özel OTP oluşturuldu: hedef={}, uzunluk={}", hedef, gecerliUzunluk);

        return otp;
    }

    // ── OTP DOĞRULAMA ─────────────────────────────────────────────────────────

    /**
     * OTP'yi doğrular.
     *
     * Doğrulama süreci:
     * 1. Redis'ten saklanan OTP'yi al
     * 2. Gelen OTP ile karşılaştır
     * 3. Doğruysa: Redis'ten sil (tek kullanımlık!)
     * 4. Yanlışsa: Deneme sayacını artır
     *
     * @param hedef  E-posta veya telefon
     * @param girisOtp Kullanıcının girdiği OTP
     * @return OtpDogrulamaResult - sonuç enum'u
     */
    public OtpDogrulamaResult otpDogrula(String hedef, String girisOtp) {
        // Redis anahtarını oluştur
        var anahtar = OTP_ONEKI + hedef;

        // Redis'ten saklanan OTP'yi al
        var saklananOtp = stringRedisTemplate.opsForValue().get(anahtar);

        // OTP Redis'te yok = süresi dolmuş veya hiç oluşturulmamış
        if (saklananOtp == null) {
            log.warn("OTP bulunamadı veya süresi doldu: hedef={}", hedef);
            return OtpDogrulamaResult.SURE_DOLDU; // Süresi dolmuş
        }

        // Deneme sayısını kontrol et (maksimum 3 deneme)
        var denemeAnahtari = OTP_DENEME_ONEKI + hedef;
        var denemeSayaci = stringRedisTemplate.opsForValue().increment(denemeAnahtari);

        // İlk denemede deneme sayacı için TTL ayarla
        if (denemeSayaci != null && denemeSayaci == 1) {
            stringRedisTemplate.expire(denemeAnahtari, Duration.ofSeconds(otpTtlSaniye));
        }

        // 3 başarısız denemeden sonra OTP geçersiz sayılır
        if (denemeSayaci != null && denemeSayaci > 3) {
            // OTP'yi sil - artık kullanılamaz
            stringRedisTemplate.delete(anahtar);
            stringRedisTemplate.delete(denemeAnahtari);
            log.warn("OTP deneme limiti aşıldı, OTP iptal edildi: hedef={}", hedef);
            return OtpDogrulamaResult.DENEME_LIMITI_ASILDI;
        }

        // OTP eşleşiyor mu?
        if (saklananOtp.equals(girisOtp)) {
            // Başarılı! OTP'yi Redis'ten sil (tek kullanımlık)
            stringRedisTemplate.delete(anahtar);
            // Deneme sayacını da temizle
            stringRedisTemplate.delete(denemeAnahtari);
            log.info("OTP doğrulama başarılı: hedef={}", hedef);
            return OtpDogrulamaResult.BASARILI; // Doğrulama başarılı
        }

        // OTP yanlış
        log.warn("OTP eşleşmedi: hedef={}, kalan deneme={}", hedef, Math.max(0, 3 - denemeSayaci));
        return OtpDogrulamaResult.YANLIS_OTP; // Yanlış OTP
    }

    /**
     * OTP'nin hala geçerli olup olmadığını kontrol eder (doğrulamadan).
     *
     * @param hedef E-posta veya telefon
     * @return true ise OTP hala geçerli
     */
    public boolean otpGecerliMi(String hedef) {
        // Redis'te OTP anahtarı var mı kontrol et
        var anahtar = OTP_ONEKI + hedef;
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(anahtar));
    }

    /**
     * OTP'nin kalan geçerlilik süresini döndürür.
     *
     * @param hedef E-posta veya telefon
     * @return Kalan saniye (0 ise süresi dolmuş)
     */
    public long kalanSure(String hedef) {
        // Redis'ten TTL'yi oku
        var anahtar = OTP_ONEKI + hedef;
        var ttl = stringRedisTemplate.getExpire(anahtar);
        return ttl != null && ttl > 0 ? ttl : 0L;
    }

    /**
     * OTP'yi iptal eder (istek üzerine veya hesap devre dışı bırakılınca).
     *
     * @param hedef E-posta veya telefon
     */
    public void otpIptalEt(String hedef) {
        // OTP anahtarını Redis'ten sil
        stringRedisTemplate.delete(OTP_ONEKI + hedef);
        // Deneme sayacını da sil
        stringRedisTemplate.delete(OTP_DENEME_ONEKI + hedef);
        log.info("OTP iptal edildi: hedef={}", hedef);
    }

    // ── OTP DOĞRULAMA SONUÇ ENUM'U ────────────────────────────────────────────

    /**
     * OTP doğrulama sonuç tipleri.
     * Java 21 sealed class veya enum ile tip güvenli sonuç yönetimi.
     */
    public enum OtpDogrulamaResult {
        // Doğrulama başarılı
        BASARILI("OTP doğrulandı"),
        // Yanlış OTP girildi
        YANLIS_OTP("Yanlış OTP. Lütfen tekrar deneyin."),
        // OTP süresi dolmuş
        SURE_DOLDU("OTP süresi dolmuş. Yeni OTP talep edin."),
        // Çok fazla hatalı deneme
        DENEME_LIMITI_ASILDI("Çok fazla hatalı deneme. OTP iptal edildi.");

        // Kullanıcıya gösterilecek mesaj
        private final String mesaj;

        // Enum constructor
        OtpDogrulamaResult(String mesaj) {
            // Mesajı ata
            this.mesaj = mesaj;
        }

        // Mesajı döndür
        public String getMesaj() {
            return mesaj;
        }

        // Başarılı mı?
        public boolean basariliMi() {
            // Sadece BASARILI değeri başarılıdır
            return this == BASARILI;
        }
    }
}
