package com.redisdemo.service.cache;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Redis Sorted Set işlemleri
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Koleksiyon ve tipler
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Liderlik Tablosu Servisi - Redis Sorted Set
 *
 * Redis Sorted Set (ZSET), her üyeye bir skor atayarak
 * otomatik sıralama yapan veri yapısıdır.
 *
 * E-ticaret Kullanım Senaryoları:
 * - En çok satan ürünler (satış adedi → skor)
 * - En yüksek puanlı ürünler (rating → skor)
 * - Günlük/haftalık/aylık trend ürünler
 * - Kategori liderlik tablosu
 *
 * Redis Sorted Set Komutları:
 * - ZADD  leaderboard 150 "product:1"  → Ürünü ekle/güncelle (skor: 150)
 * - ZINCRBY leaderboard 5 "product:1"  → Skoru 5 artır (atomik)
 * - ZRANGE leaderboard 0 9 WITHSCORES  → En düşük 10 (ascending)
 * - ZREVRANGE leaderboard 0 9 WITHSCORES → En yüksek 10 (descending)
 * - ZRANK leaderboard "product:1"      → Sıralama (düşükten yükseğe, 0-tabanlı)
 * - ZREVRANK leaderboard "product:1"   → Sıralama (yüksekten düşüğe)
 * - ZSCORE leaderboard "product:1"     → Skoru oku
 * - ZCARD leaderboard                  → Toplam üye sayısı
 *
 * Neden Sorted Set?
 * - O(log N) ekle/sil → Milyonlarca ürün için hala hızlı
 * - Skor değişince otomatik yeniden sıralar
 * - ZINCRBY atomik → Aynı anda binlerce satış sayılabilir
 */
@Service // Spring servis bean'i
public class LeaderboardService {

    // Logger
    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);

    // Liderlik tablosu Redis anahtarı - uygulama.yml'den okunur
    @Value("${app.redis.leaderboard-key:product:leaderboard}")
    private String leaderboardAnahtari;

    // Redis template - ZSet işlemleri için
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public LeaderboardService(RedisTemplate<String, Object> redisTemplate) {
        // RedisTemplate bağımlılığını ata
        this.redisTemplate = redisTemplate;
    }

    // ── SORTED SET ACCESSOR ───────────────────────────────────────────────────

    /**
     * ZSet işlemleri için yardımcı accessor.
     *
     * @return ZSetOperations nesnesi
     */
    private ZSetOperations<String, Object> zsetOps() {
        // RedisTemplate'den ZSet işlem nesnesini al
        return redisTemplate.opsForZSet();
    }

    // ── ÜRÜN SKOR GÜNCELLEME ──────────────────────────────────────────────────

    /**
     * Ürün satıldığında skoru artırır.
     *
     * ZINCRBY komutu:
     * - Atomiktir (thread-safe)
     * - Ürün yoksa otomatik oluşturur (skor: adet)
     * - Ürün varsa skoru artırır
     * - Sıralama otomatik güncellenir
     *
     * @param urunId Satılan ürün ID'si
     * @param adet   Satılan adet
     */
    public void satisKaydedildi(Long urunId, int adet) {
        // Ürün üyesi adını oluştur
        var uyeAdi = "product:" + urunId;

        // ZINCRBY: skoru atomik olarak artır
        var yeniSkor = zsetOps().incrementScore(leaderboardAnahtari, uyeAdi, adet);

        // İşlemi kayıt al
        log.info("Satış kaydedildi: urunId={}, adet={}, yeniToplam={}", urunId, adet, yeniSkor);
    }

    /**
     * Ürünün skorunu belirli bir değere set eder.
     * Toplu güncelleme veya başlangıç skoru ayarlamak için.
     *
     * ZADD komutu: Ürünü ekler veya skoru günceller.
     *
     * @param urunId Ürün ID'si
     * @param skor   Yeni skor değeri
     */
    public void skorAyarla(Long urunId, double skor) {
        // Ürün üyesi adı
        var uyeAdi = "product:" + urunId;

        // ZADD ile skoru set et
        zsetOps().add(leaderboardAnahtari, uyeAdi, skor);

        log.debug("Ürün skoru ayarlandı: urunId={}, skor={}", urunId, skor);
    }

    // ── LIDERLIK TABLOSU OKUMA ────────────────────────────────────────────────

    /**
     * En çok satan ilk N ürünü getirir (descending sırada).
     *
     * ZREVRANGE: Sorted Set'i yüksekten düşüğe sıralar.
     * WITHSCORES: Skor değerleriyle birlikte döndürür.
     *
     * @param ilkN İlk kaç ürün alınacak (örn: top 10)
     * @return Sıralı ürün listesi (skor ile birlikte)
     */
    public List<LeaderboardKayit> enCokSatanlar(int ilkN) {
        // ZREVRANGEBYSCORE: Yüksek skordan düşüğe sıralı ilk N üye
        // 0 başlangıç index, ilkN-1 bitiş index
        var sonuclar = zsetOps().reverseRangeWithScores(leaderboardAnahtari, 0, ilkN - 1);

        // Sonuç yoksa boş liste döndür
        if (sonuclar == null || sonuclar.isEmpty()) {
            return Collections.emptyList();
        }

        // ZSetOperations.TypedTuple'ı LeaderboardKayit'e dönüştür
        var liste = new ArrayList<LeaderboardKayit>();
        int sira = 1; // Sıra numarası (1'den başlar)

        for (var tuple : sonuclar) {
            // Ürün üye adından ID çıkar ("product:5" → 5)
            var uyeAdi = tuple.getValue() != null ? tuple.getValue().toString() : "";
            var urunId = extractUrunId(uyeAdi); // "product:5" → 5L

            // LeaderboardKayit record'u oluştur
            var kayit = new LeaderboardKayit(
                    sira,                                              // Sıra
                    urunId,                                           // Ürün ID
                    uyeAdi,                                           // Üye adı
                    tuple.getScore() != null ? tuple.getScore() : 0.0 // Skor
            );

            liste.add(kayit); // Listeye ekle
            sira++; // Sıra numarasını artır
        }

        log.debug("Liderlik tablosu getirildi: ilkN={}, bulunan={}", ilkN, liste.size());
        return liste;
    }

    /**
     * Tek bir ürünün liderlik tablosundaki sıralamasını döndürür.
     *
     * ZREVRANK: Yüksekten düşüğe sıralamada index (0-tabanlı).
     * 0 → en iyi, 1 → ikinci, null → listede değil.
     *
     * @param urunId Sorgulanacak ürün ID'si
     * @return Sıralama (1-tabanlı) veya -1 (listede yok)
     */
    public long urunSiralamasi(Long urunId) {
        // Üye adını oluştur
        var uyeAdi = "product:" + urunId;

        // ZREVRANK: yüksekten düşüğe sıralamadaki indeks (0-tabanlı)
        var siralama = zsetOps().reverseRank(leaderboardAnahtari, uyeAdi);

        // null ise listede yok
        if (siralama == null) {
            return -1L; // Listede yok
        }

        // 0-tabanlı indeksi 1-tabanlıya çevir
        return siralama + 1;
    }

    /**
     * Ürünün toplam satış skorunu döndürür.
     *
     * ZSCORE komutu: Belirli üyenin skorunu döndürür.
     *
     * @param urunId Sorgulanacak ürün ID'si
     * @return Toplam satış adedi (skoru), 0 ise satış yok
     */
    public double urunSkoru(Long urunId) {
        // Üye adı
        var uyeAdi = "product:" + urunId;

        // ZSCORE: Üyenin skorunu oku
        var skor = zsetOps().score(leaderboardAnahtari, uyeAdi);

        // null ise 0 döndür (ürün listede değil)
        return skor != null ? skor : 0.0;
    }

    /**
     * Liderlik tablosundaki toplam ürün sayısını döndürür.
     *
     * @return Toplam ürün sayısı
     */
    public long toplamUrunSayisi() {
        // ZCARD: Sorted Set'teki üye sayısı
        var sayi = zsetOps().zCard(leaderboardAnahtari);
        return sayi != null ? sayi : 0L;
    }

    /**
     * Belirli skor aralığındaki ürünleri getirir.
     * Örn: 100 ile 500 adet arası satışı olan ürünler.
     *
     * @param minSkor Minimum skor
     * @param maxSkor Maksimum skor
     * @return Skor aralığındaki ürünler
     */
    public List<LeaderboardKayit> skorAraligindakiUrunler(double minSkor, double maxSkor) {
        // ZRANGEBYSCORE ile aralıktaki üyeleri getir (skor ile)
        var sonuclar = zsetOps().rangeByScoreWithScores(leaderboardAnahtari, minSkor, maxSkor);

        // Sonuç yoksa boş döndür
        if (sonuclar == null || sonuclar.isEmpty()) {
            return Collections.emptyList();
        }

        // Dönüşüm yap ve döndür
        var liste = new ArrayList<LeaderboardKayit>();
        int sira = 1;

        for (var tuple : sonuclar) {
            var uyeAdi = tuple.getValue() != null ? tuple.getValue().toString() : "";
            liste.add(new LeaderboardKayit(sira++, extractUrunId(uyeAdi), uyeAdi,
                    tuple.getScore() != null ? tuple.getScore() : 0.0));
        }

        return liste;
    }

    // ── SİLME / SIFIRLAMA ─────────────────────────────────────────────────────

    /**
     * Ürünü liderlik tablosundan kaldırır.
     *
     * @param urunId Kaldırılacak ürün ID'si
     */
    public void urunKaldir(Long urunId) {
        // Üye adı
        var uyeAdi = "product:" + urunId;

        // ZREM: Sorted Set'ten üyeyi sil
        zsetOps().remove(leaderboardAnahtari, uyeAdi);

        log.info("Ürün liderlik tablosundan kaldırıldı: urunId={}", urunId);
    }

    /**
     * Liderlik tablosunu tamamen sıfırlar.
     * Periyodik sıfırlama için (haftalık/aylık liderlik).
     */
    public void tabloSifirla() {
        // Tüm liderlik tablosunu sil
        redisTemplate.delete(leaderboardAnahtari);

        log.warn("Liderlik tablosu tamamen sıfırlandı!");
    }

    // ── YARDIMCI METODLAR ─────────────────────────────────────────────────────

    /**
     * "product:5" formatındaki üye adından ürün ID'sini çıkarır.
     *
     * @param uyeAdi Üye adı (örn: "product:5")
     * @return Ürün ID'si
     */
    private Long extractUrunId(String uyeAdi) {
        // "product:" önekini kaldır ve Long'a çevir
        try {
            return Long.parseLong(uyeAdi.replace("product:", ""));
        } catch (NumberFormatException e) {
            log.warn("Geçersiz üye adı: {}", uyeAdi);
            return 0L; // Geçersiz format
        }
    }

    // ── LIDERLIK KAYIT RECORD'U ───────────────────────────────────────────────

    /**
     * Liderlik tablosu kayıt verisi - Java 21 Record.
     *
     * @param sira    Sıralama pozisyonu (1 en iyi)
     * @param urunId  Ürün ID'si
     * @param uyeAdi  Redis Sorted Set'teki üye adı
     * @param skor    Toplam satış skoru
     */
    public record LeaderboardKayit(
            int sira,       // Sıralama (1=birinci)
            Long urunId,    // Ürün ID
            String uyeAdi,  // Redis üye adı ("product:5")
            double skor     // Toplam skor (satış adedi)
    ) {
        /**
         * Madalya ikonunu döndürür.
         * @return Altın/Gümüş/Bronz veya numara
         */
        public String siraMedali() {
            // Java 21 switch expression ile kısa yazım
            return switch (sira) {
                case 1 -> "🥇"; // Birinci: altın madalya
                case 2 -> "🥈"; // İkinci: gümüş madalya
                case 3 -> "🥉"; // Üçüncü: bronz madalya
                default -> "#" + sira; // Diğerleri: numara
            };
        }
    }
}
