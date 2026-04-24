package com.redisdemo.service.cache;

// Sepet veri transfer nesnesi
import com.redisdemo.dto.records.CartItemRecord;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

// Redis işlemleri
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.HashOperations;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Koleksiyon ve zaman tipleri
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Alışveriş Sepeti Cache Servisi
 *
 * Redis Hash veri yapısı kullanarak alışveriş sepeti yönetimi yapar.
 *
 * Redis Hash neden ideal?
 * - Her kullanıcının sepeti ayrı bir Hash: "cart:{userId}"
 * - Her ürün, hash'in bir field'ı: "product:{productId}"
 * - O(1) karmaşıklıkla ekleme, silme, okuma
 * - Tek komutla tüm sepeti getirme (HGETALL)
 * - Atomik güncelleme (HSET)
 *
 * Redis Hash Komutları Kullanımı:
 * - HSET cart:user1 product:5 '{"urunId":5,"miktar":2,...}'
 * - HGET cart:user1 product:5
 * - HDEL cart:user1 product:5
 * - HGETALL cart:user1
 * - HLEN cart:user1 (kaç ürün var?)
 * - EXPIRE cart:user1 86400 (24 saat TTL)
 */
@Service // Spring servis bean'i
public class CartCacheService {

    // Logger - sepet işlemlerini izle
    private static final Logger log = LoggerFactory.getLogger(CartCacheService.class);

    // Sepet anahtarı öneki - her kullanıcı için "cart:{userId}"
    private static final String SEPET_ONEKI = "cart:";

    // Sepet TTL değeri - uygulama.yml'den okunur (varsayılan: 24 saat)
    @Value("${app.redis.cart-ttl:86400}")
    private long sepetTtlSaniye;

    // Redis işlemleri için genel template
    private final RedisTemplate<String, Object> redisTemplate;

    @Autowired
    public CartCacheService(RedisTemplate<String, Object> redisTemplate) {
        // RedisTemplate bağımlılığını ata
        this.redisTemplate = redisTemplate;
    }

    // ── HASH OPERATIONS ERİŞİMİ ───────────────────────────────────────────────

    /**
     * Hash işlemleri için yardımcı accessor.
     * Her çağrıda redisTemplate'den opsForHash() ile alınır.
     *
     * @return String anahtarlı, CartItemRecord değerli HashOperations
     */
    private HashOperations<String, String, CartItemRecord> hashOps() {
        // RedisTemplate'den Hash işlem nesnesini al
        return redisTemplate.opsForHash();
    }

    // ── SEPET EKLEME / GÜNCELLEME ─────────────────────────────────────────────

    /**
     * Sepete ürün ekler veya mevcut ürünün miktarını artırır.
     *
     * Redis komutu: HSET cart:{userId} product:{productId} {JSON}
     *
     * @param userId   Kullanıcı ID'si
     * @param urun     Eklenecek sepet kalemi
     */
    public void sepeteEkle(String userId, CartItemRecord urun) {
        // Sepet Redis anahtarını oluştur
        var sepetAnahtari = SEPET_ONEKI + userId;

        // Hash field anahtarı: "product:{urunId}"
        var fieldAnahtari = urun.hashField();

        // Bu ürün sepette zaten var mı kontrol et
        var mevcutUrun = (CartItemRecord) hashOps().get(sepetAnahtari, fieldAnahtari);

        if (mevcutUrun != null) {
            // Ürün zaten var - miktarı artır (immutable record, yeni nesne oluştur)
            var guncellenenUrun = mevcutUrun.miktarEkle(urun.miktar());
            // Güncellenmiş kalemi Hash'e yaz
            hashOps().put(sepetAnahtari, fieldAnahtari, guncellenenUrun);
            log.info("Sepet güncellendi: userId={}, urunId={}, yeniMiktar={}",
                    userId, urun.urunId(), guncellenenUrun.miktar());
        } else {
            // Ürün yok - yeni ekle
            hashOps().put(sepetAnahtari, fieldAnahtari, urun);
            log.info("Sepete yeni ürün eklendi: userId={}, urunId={}", userId, urun.urunId());
        }

        // Sepet TTL'ini (sona erme süresini) yenile - kullanım sonrası süre uzasın
        redisTemplate.expire(sepetAnahtari, sepetTtlSaniye, TimeUnit.SECONDS);
    }

    /**
     * Sepetteki ürünün miktarını değiştirir.
     *
     * @param userId     Kullanıcı ID'si
     * @param urunId     Güncellenecek ürün ID'si
     * @param yeniMiktar Yeni miktar (0 ise ürünü sil)
     */
    public void miktarGuncelle(String userId, Long urunId, int yeniMiktar) {
        // Sepet anahtarı
        var sepetAnahtari = SEPET_ONEKI + userId;

        // Ürün field anahtarı
        var fieldAnahtari = "product:" + urunId;

        // Miktar 0 veya negatifse ürünü sil
        if (yeniMiktar <= 0) {
            sepettenCikar(userId, urunId); // Ürünü kaldır
            return;
        }

        // Mevcut ürünü getir
        var mevcutUrun = (CartItemRecord) hashOps().get(sepetAnahtari, fieldAnahtari);

        if (mevcutUrun != null) {
            // Yeni miktarla yeni record oluştur (immutable record pattern)
            var guncellenenUrun = new CartItemRecord(
                    mevcutUrun.urunId(),
                    mevcutUrun.urunAdi(),
                    yeniMiktar,              // Yeni miktar
                    mevcutUrun.birimFiyat(),
                    mevcutUrun.kategori()
            );

            // Hash'e güncellemeyi yaz
            hashOps().put(sepetAnahtari, fieldAnahtari, guncellenenUrun);

            // TTL'i yenile
            redisTemplate.expire(sepetAnahtari, sepetTtlSaniye, TimeUnit.SECONDS);

            log.info("Sepet miktarı güncellendi: userId={}, urunId={}, miktar={}", userId, urunId, yeniMiktar);
        }
    }

    // ── SEPETTEN KALDIRMA ─────────────────────────────────────────────────────

    /**
     * Sepetten belirli bir ürünü kaldırır.
     *
     * Redis komutu: HDEL cart:{userId} product:{productId}
     *
     * @param userId Kullanıcı ID'si
     * @param urunId Kaldırılacak ürün ID'si
     */
    public void sepettenCikar(String userId, Long urunId) {
        // Sepet anahtarı
        var sepetAnahtari = SEPET_ONEKI + userId;

        // Hash'ten ürünü sil
        var silinenSayi = hashOps().delete(sepetAnahtari, "product:" + urunId);

        // İşlemi kayıt al
        log.info("Sepetten ürün çıkarıldı: userId={}, urunId={}, silinen={}", userId, urunId, silinenSayi);
    }

    /**
     * Sepeti tamamen temizler (ödeme sonrası veya kullanıcı isteğiyle).
     *
     * Redis komutu: DEL cart:{userId}
     *
     * @param userId Kullanıcı ID'si
     */
    public void sepetiTemizle(String userId) {
        // Sepet anahtarı
        var sepetAnahtari = SEPET_ONEKI + userId;

        // Tüm sepet hash'ini sil
        redisTemplate.delete(sepetAnahtari);

        // İşlemi kayıt al
        log.info("Sepet tamamen temizlendi: userId={}", userId);
    }

    // ── SEPET OKUMA ───────────────────────────────────────────────────────────

    /**
     * Kullanıcının tüm sepetini getirir.
     *
     * Redis komutu: HGETALL cart:{userId}
     * Tüm field-value çiftlerini tek seferde döndürür.
     *
     * @param userId Kullanıcı ID'si
     * @return Sepetteki tüm ürün kalemleri listesi
     */
    public List<CartItemRecord> sepetiGetir(String userId) {
        // Sepet anahtarı
        var sepetAnahtari = SEPET_ONEKI + userId;

        // Tüm hash girdilerini getir (field → CartItemRecord map'i)
        Map<String, CartItemRecord> sepetMap = hashOps().entries(sepetAnahtari);

        // Map boş ise boş liste döndür
        if (sepetMap == null || sepetMap.isEmpty()) {
            log.debug("Sepet boş: userId={}", userId);
            return Collections.emptyList(); // Değiştirilemez boş liste
        }

        // Map değerlerinden liste oluştur ve döndür
        var sepetListesi = new ArrayList<>(sepetMap.values());
        log.debug("Sepet getirildi: userId={}, kalemSayisi={}", userId, sepetListesi.size());

        return sepetListesi;
    }

    /**
     * Belirli bir ürünün sepetteki durumunu getirir.
     *
     * Redis komutu: HGET cart:{userId} product:{productId}
     *
     * @param userId Kullanıcı ID'si
     * @param urunId Ürün ID'si
     * @return Sepet kalemi (yoksa boş Optional)
     */
    public Optional<CartItemRecord> sepetKaleminiGetir(String userId, Long urunId) {
        // Sepet ve ürün anahtarları
        var sepetAnahtari = SEPET_ONEKI + userId;
        var fieldAnahtari = "product:" + urunId;

        // Hash'ten tek ürünü getir
        var kalem = (CartItemRecord) hashOps().get(sepetAnahtari, fieldAnahtari);

        // Optional sarmalayıcısına al
        return Optional.ofNullable(kalem);
    }

    /**
     * Sepetteki toplam ürün çeşidi sayısını döndürür.
     *
     * Redis komutu: HLEN cart:{userId}
     *
     * @param userId Kullanıcı ID'si
     * @return Sepetteki farklı ürün sayısı
     */
    public long sepetUrunCesidiSayisi(String userId) {
        // Sepet anahtarı
        var sepetAnahtari = SEPET_ONEKI + userId;

        // Hash'teki field sayısını döndür
        var sayi = hashOps().size(sepetAnahtari);

        // null kontrolü
        return sayi != null ? sayi : 0L;
    }

    // ── HESAPLAMA ─────────────────────────────────────────────────────────────

    /**
     * Sepetin toplam tutarını hesaplar.
     *
     * @param userId Kullanıcı ID'si
     * @return Toplam tutar (tüm kalemlerin toplamı)
     */
    public BigDecimal sepetToplamTutar(String userId) {
        // Tüm sepet kalemlerini getir
        var kalemler = sepetiGetir(userId);

        // Her kalemin tutarını topla (BigDecimal stream işlemi)
        return kalemler.stream()
                .map(CartItemRecord::toplamTutar) // Her kalemin tutarı
                .reduce(BigDecimal.ZERO, BigDecimal::add); // Toplam
    }

    /**
     * Sepetteki toplam ürün adedini hesaplar (farklı ürün sayısı değil, adet toplamı).
     *
     * @param userId Kullanıcı ID'si
     * @return Toplam ürün adedi
     */
    public int sepetToplamAdet(String userId) {
        // Tüm sepet kalemlerini getir
        var kalemler = sepetiGetir(userId);

        // Her kalemin miktarını topla
        return kalemler.stream()
                .mapToInt(CartItemRecord::miktar) // int stream
                .sum(); // Toplam adet
    }

    /**
     * Kullanıcının aktif sepeti var mı kontrol eder.
     *
     * @param userId Kullanıcı ID'si
     * @return true ise sepette en az bir ürün var
     */
    public boolean aktifSepetVarMi(String userId) {
        // Sepet anahtar kontrolü
        var sepetAnahtari = SEPET_ONEKI + userId;
        // Redis'te bu anahtar var mı?
        return Boolean.TRUE.equals(redisTemplate.hasKey(sepetAnahtari));
    }
}
