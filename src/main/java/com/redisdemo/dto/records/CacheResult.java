package com.redisdemo.dto.records;

// Java 8 anlık zaman bilgisi için
import java.time.Instant;

/**
 * Cache Sonuç Arayüzü - Java 21 Sealed Interface + Record Birleşimi
 *
 * Sealed Interface: Yalnızca bu dosyada tanımlanan sınıflar bu arayüzü uygulayabilir.
 * Bu sayede cache durumu (hit/miss/expired) tip güvenli şekilde temsil edilir.
 *
 * Kullanım:
 * <pre>
 *   CacheResult<Product> result = productCacheService.getFromCache("123");
 *
 *   // Pattern Matching for switch (Java 21)
 *   String mesaj = switch (result) {
 *       case CacheResult.Hit<Product> hit     -> "Bulundu: " + hit.deger().getAd();
 *       case CacheResult.Miss<Product> miss   -> "Cache'de yok: " + miss.neden();
 *       case CacheResult.Expired<Product> exp -> "Süresi doldu: " + exp.sureDolumZamani();
 *   };
 * </pre>
 *
 * @param <T> Cache'lenen verinin tipi
 */
public sealed interface CacheResult<T> permits CacheResult.Hit, CacheResult.Miss, CacheResult.Expired {

    /**
     * Cache HIT - İstenen veri cache'de bulundu.
     *
     * @param <T>                Cache'lenen verinin tipi
     * @param deger              Cache'den okunan değer
     * @param cacheAnahtari      Redis'teki anahtar
     * @param kalanTtlSaniye     Cache'in sona ermesine kalan süre (saniye)
     */
    record Hit<T>(
            T deger,           // Cache'den dönen veri
            String cacheAnahtari,    // Redis anahtar değeri
            long kalanTtlSaniye      // Kalan yaşam süresi (saniye)
    ) implements CacheResult<T> {}

    /**
     * Cache MISS - İstenen veri cache'de bulunamadı.
     * Genellikle ilk istekte veya cache temizlendikten sonra gerçekleşir.
     *
     * @param <T>            Cache'lenen verinin tipi
     * @param cacheAnahtari  Aranan Redis anahtarı
     * @param neden          Neden bulunamadığının açıklaması
     */
    record Miss<T>(
            String cacheAnahtari,    // Bulunamayan anahtar
            String neden             // Neden bulunamadığı
    ) implements CacheResult<T> {}

    /**
     * Cache EXPIRED - Cache kaydı mevcuttu ama süresi doldu.
     * TTL (Time To Live) sona erdiğinde Redis otomatik siler.
     *
     * @param <T>                Cache'lenen verinin tipi
     * @param cacheAnahtari      Süresi dolan anahtar
     * @param sureDolumZamani    Cache'in ne zaman sona erdiği
     */
    record Expired<T>(
            String cacheAnahtari,    // Süresi dolan anahtar
            Instant sureDolumZamani  // Süre dolum zamanı
    ) implements CacheResult<T> {}

    // ── FACTORY METODLARI ────────────────────────────────────────────────────────

    /**
     * Cache HIT sonucu oluşturur.
     *
     * @param deger           Bulunan değer
     * @param anahtar         Redis anahtarı
     * @param kalanTtl        Kalan süre (saniye)
     * @return Hit örneği
     */
    static <T> CacheResult<T> hit(T deger, String anahtar, long kalanTtl) {
        // Yeni Hit record'u oluştur ve döndür
        return new Hit<>(deger, anahtar, kalanTtl);
    }

    /**
     * Cache MISS sonucu oluşturur.
     *
     * @param anahtar  Aranan anahtar
     * @param neden    Neden bulunamadığı
     * @return Miss örneği
     */
    static <T> CacheResult<T> miss(String anahtar, String neden) {
        // Yeni Miss record'u oluştur ve döndür
        return new Miss<>(anahtar, neden);
    }

    /**
     * Cache EXPIRED sonucu oluşturur.
     *
     * @param anahtar         Süresi dolan anahtar
     * @param sureDolumZamani Süre dolum zamanı
     * @return Expired örneği
     */
    static <T> CacheResult<T> expired(String anahtar, Instant sureDolumZamani) {
        // Yeni Expired record'u oluştur ve döndür
        return new Expired<>(anahtar, sureDolumZamani);
    }

    /**
     * Pattern Matching ile sonucu açıklayan metin üretir.
     * Java 21 switch pattern matching kullanımını gösterir.
     *
     * @return İnsan okunabilir sonuç açıklaması
     */
    default String acikla() {
        // Java 21 Pattern Matching for switch - exhaustive (tüm durumlar kapsanır)
        return switch (this) {
            // Hit durumu: değer ve kalan süre ile birlikte raporla
            case Hit<?> hit ->
                    "✅ CACHE HIT | Anahtar: %s | Kalan TTL: %d saniye"
                            .formatted(hit.cacheAnahtari(), hit.kalanTtlSaniye());

            // Miss durumu: neden bulunamadığını raporla
            case Miss<?> miss ->
                    "❌ CACHE MISS | Anahtar: %s | Neden: %s"
                            .formatted(miss.cacheAnahtari(), miss.neden());

            // Expired durumu: ne zaman sona erdiğini raporla
            case Expired<?> expired ->
                    "⏰ CACHE EXPIRED | Anahtar: %s | Dolum Zamanı: %s"
                            .formatted(expired.cacheAnahtari(), expired.sureDolumZamani());
        };
    }

    /**
     * Cache sonucunun başarılı (Hit) olup olmadığını kontrol eder.
     *
     * @return true ise cache'de veri bulundu
     */
    default boolean basarili() {
        // Sadece Hit durumu başarılı sayılır
        return this instanceof Hit<?>;
    }
}
