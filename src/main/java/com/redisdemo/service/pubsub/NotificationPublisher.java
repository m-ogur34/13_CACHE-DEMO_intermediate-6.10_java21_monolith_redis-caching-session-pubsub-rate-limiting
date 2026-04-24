package com.redisdemo.service.pubsub;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Redis yayıncısı için
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;

// JSON dönüşümü için
import com.fasterxml.jackson.databind.ObjectMapper;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Zaman ve koleksiyon
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Redis Pub/Sub Yayıncısı (Publisher)
 *
 * Redis Pub/Sub Nedir?
 * - Publish/Subscribe mesajlaşma desenidir
 * - Yayıncı (publisher) kanala mesaj gönderir
 * - Abone olan tüm dinleyiciler (subscribers) mesajı alır
 * - Loosely coupled: yayıncı ve dinleyici birbirini bilmez
 *
 * Kullanım Senaryoları:
 * - Gerçek zamanlı bildirimler (siparişi kargoya verildi!)
 * - Stok güncellemesi bildirimi
 * - Fiyat değişikliği bildirimi
 * - Chat mesajları
 * - Broadcast duyuruları
 *
 * Redis Pub/Sub Özellikleri:
 * - Fire and forget (mesaj kaybolabilir - kalıcı değil)
 * - Tüm subscriber'lar aynı anda mesajı alır
 * - Kanal tabanlı yönlendirme
 *
 * NOT: Mesaj garantisi istiyorsanız Redis Streams kullanın!
 * Pub/Sub: hız odaklı, kayıp tolere edilebilir senaryolar için.
 */
@Service // Spring servis bean'i
public class NotificationPublisher {

    // Logger - yayın işlemlerini izle
    private static final Logger log = LoggerFactory.getLogger(NotificationPublisher.class);

    // Bildirim kanalı adı - uygulama.yml'den okunur
    @Value("${app.redis.notification-channel:notifications}")
    private String bildirimKanali;

    // Sipariş kanalı adı
    @Value("${app.redis.order-channel:orders}")
    private String siparisKanali;

    // Redis mesaj gönderme için template
    private final RedisTemplate<String, Object> redisTemplate;

    // JSON dönüşümü için Jackson
    private final ObjectMapper objectMapper;

    @Autowired
    public NotificationPublisher(RedisTemplate<String, Object> redisTemplate,
                                 ObjectMapper objectMapper) {
        // RedisTemplate bağımlılığını ata
        this.redisTemplate = redisTemplate;
        // ObjectMapper bağımlılığını ata
        this.objectMapper = objectMapper;
    }

    // ── BİLDİRİM YAYINLAMA ───────────────────────────────────────────────────

    /**
     * Bildirim kanalına mesaj yayınlar.
     *
     * Redis komutu: PUBLISH notifications "{JSON mesaj}"
     * Bu komutu alan tüm subscriber'lar anında mesajı işler.
     *
     * @param mesaj Yayınlanacak bildirim mesajı
     */
    public void bildirimYayinla(BildirimMesaji mesaj) {
        try {
            // Bildirim nesnesini JSON String'e dönüştür
            var jsonMesaj = objectMapper.writeValueAsString(mesaj);

            // Redis PUBLISH komutuyla kanala gönder
            var abone_sayisi = redisTemplate.convertAndSend(bildirimKanali, jsonMesaj);

            // Kaç abonenin mesajı aldığını log'a yaz
            log.info("Bildirim yayınlandı: kanal={}, aliciSayisi={}, tip={}",
                    bildirimKanali, abone_sayisi, mesaj.tip());
        } catch (Exception e) {
            // JSON dönüşüm hatası - log'a yaz ama uygulamayı durdurma
            log.error("Bildirim yayınlama hatası: {}", e.getMessage());
        }
    }

    /**
     * Sipariş kanalına mesaj yayınlar.
     *
     * @param siparisMesaji Yayınlanacak sipariş mesajı
     */
    public void siparisYayinla(SiparisMesaji siparisMesaji) {
        try {
            // Sipariş nesnesini JSON'a dönüştür
            var jsonMesaj = objectMapper.writeValueAsString(siparisMesaji);

            // Sipariş kanalına gönder
            redisTemplate.convertAndSend(siparisKanali, jsonMesaj);

            log.info("Sipariş bildirimi yayınlandı: siparisId={}, durum={}",
                    siparisMesaji.siparisId(), siparisMesaji.durum());
        } catch (Exception e) {
            log.error("Sipariş yayınlama hatası: {}", e.getMessage());
        }
    }

    /**
     * Özel kanala mesaj yayınlar.
     *
     * @param kanal   Hedef kanal adı
     * @param mesaj   Yayınlanacak mesaj
     */
    public void ozelKanalaYayinla(String kanal, Object mesaj) {
        try {
            // Nesneyi JSON'a dönüştür
            var jsonMesaj = objectMapper.writeValueAsString(mesaj);

            // Belirtilen kanala gönder
            redisTemplate.convertAndSend(kanal, jsonMesaj);

            log.info("Özel kanal mesajı yayınlandı: kanal={}", kanal);
        } catch (Exception e) {
            log.error("Özel kanal yayınlama hatası: kanal={}, hata={}", kanal, e.getMessage());
        }
    }

    // ── YARDIMCI METODLAR ─────────────────────────────────────────────────────

    /**
     * Stok güncelleme bildirimi yayınlar.
     *
     * @param urunId      Stoku değişen ürün
     * @param yeniStok    Yeni stok miktarı
     */
    public void stokGuncellemeBildirimi(Long urunId, int yeniStok) {
        // Stok bildirimi oluştur
        var mesaj = new BildirimMesaji(
                "STOK_GUNCELLEME",       // Bildirim tipi
                "Stok güncellendi: Ürün #" + urunId + " → " + yeniStok + " adet",
                Map.of("urunId", urunId.toString(), "yeniStok", String.valueOf(yeniStok)),
                LocalDateTime.now()
        );

        // Bildirim kanalına yayınla
        bildirimYayinla(mesaj);
    }

    /**
     * Fiyat değişikliği bildirimi yayınlar.
     *
     * @param urunId    Fiyatı değişen ürün
     * @param eskiFiyat Önceki fiyat
     * @param yeniFiyat Yeni fiyat
     */
    public void fiyatDegisikligi(Long urunId, double eskiFiyat, double yeniFiyat) {
        // Fiyat değişiklik bildirimi
        var mesaj = new BildirimMesaji(
                "FIYAT_DEGISIKLIGI",
                "Fiyat güncellendi: Ürün #" + urunId,
                Map.of(
                        "urunId", urunId.toString(),
                        "eskiFiyat", String.valueOf(eskiFiyat),
                        "yeniFiyat", String.valueOf(yeniFiyat)
                ),
                LocalDateTime.now()
        );

        // Bildirimi yayınla
        bildirimYayinla(mesaj);
    }

    // ── MESAJ RECORD'LARI ─────────────────────────────────────────────────────

    /**
     * Bildirim mesajı - Java 21 Record.
     *
     * @param tip     Bildirim tipi (STOK_GUNCELLEME, FIYAT_DEGISIKLIGI vb.)
     * @param icerik  İnsan okunabilir mesaj
     * @param veriler Ek veri (key-value çiftleri)
     * @param zaman   Bildirim zamanı
     */
    public record BildirimMesaji(
            String tip,                  // Bildirim tipi
            String icerik,               // Mesaj içeriği
            Map<String, String> veriler, // Ek veriler
            LocalDateTime zaman          // Gönderim zamanı
    ) {}

    /**
     * Sipariş mesajı - Java 21 Record.
     *
     * @param siparisId Sipariş numarası
     * @param userId    Sipariş veren kullanıcı
     * @param durum     Sipariş durumu (ALINDI, HAZIRLANIYOR, KARGODA, TESLİM)
     * @param zaman     Durum güncelleme zamanı
     */
    public record SiparisMesaji(
            String siparisId,   // Sipariş ID
            String userId,      // Kullanıcı ID
            String durum,       // Sipariş durumu
            LocalDateTime zaman // Güncelleme zamanı
    ) {}
}
