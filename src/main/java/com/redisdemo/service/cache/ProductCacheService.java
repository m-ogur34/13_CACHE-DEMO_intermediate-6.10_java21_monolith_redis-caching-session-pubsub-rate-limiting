package com.redisdemo.service.cache;

// Uygulama entity ve DTO'ları
import com.redisdemo.dto.records.CacheResult;
import com.redisdemo.dto.records.ProductRecord;
import com.redisdemo.entity.Product;
import com.redisdemo.repository.ProductRepository;

// Spring Cache anotasyonları - @Cacheable, @CachePut, @CacheEvict, @Caching
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;

// Spring stereotypes ve bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// Redis template manuel işlemler için
import org.springframework.data.redis.core.RedisTemplate;

// Loglama için
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Zaman ve koleksiyon için
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Ürün Cache Servisi
 *
 * Spring Cache anotasyonlarının TÜM kullanım şekillerini gösterir:
 *
 * @Cacheable   → Önce cache'e bak, yoksa metodu çalıştır ve sonucu cache'le
 * @CachePut    → Her zaman metodu çalıştır ve sonucu cache'e yaz (güncelleme)
 * @CacheEvict  → Cache'den sil (veri silme veya güncelleme sonrası)
 * @Caching     → Birden fazla cache anotasyonu birleştir
 *
 * SpEL (Spring Expression Language) örnekleri:
 * - key = "#id"            → parametre adı
 * - key = "#product.id"    → nesne alanı
 * - key = "'all'"          → sabit string
 * - condition = "#id > 0"  → koşul (false ise cache'lenme)
 * - unless = "#result == null" → sonuç null ise cache'leme
 */
@Service // Spring servis bean'i
public class ProductCacheService {

    // Log kaydı için logger
    private static final Logger log = LoggerFactory.getLogger(ProductCacheService.class);

    // Ürün repository'si - veritabanı işlemleri için
    private final ProductRepository productRepository;

    // Manuel Redis işlemleri için RedisTemplate
    private final RedisTemplate<String, Object> redisTemplate;

    // Constructor injection (field injection yerine önerilir)
    @Autowired
    public ProductCacheService(ProductRepository productRepository,
                               RedisTemplate<String, Object> redisTemplate) {
        // Repository bağımlılığını ata
        this.productRepository = productRepository;
        // RedisTemplate bağımlılığını ata
        this.redisTemplate = redisTemplate;
    }

    // ── @CACHEABLE ──────────────────────────────────────────────────────────────

    /**
     * Tüm ürünleri getirir - sonuç cache'de saklanır.
     *
     * @Cacheable nasıl çalışır:
     * 1. İlk istek → cache boş → metodu çalıştır → sonucu cache'e yaz
     * 2. Sonraki istek → cache'de var → metodu ÇALIŞTRMA → cache'den dön
     *
     * value="products" → CacheConfig'de tanımlanan cache adı (TTL: 5 dk)
     * key="'all'"      → Sabit anahtar - tüm ürünler için tek cache girişi
     *
     * @return Tüm aktif ürünlerin listesi
     */
    @Cacheable(value = "products", key = "'all'")
    public List<ProductRecord> tumUrunleriGetir() {
        // Cache'de yoksa bu satır çalışır - veritabanından oku
        log.info("CACHE MISS - Tüm ürünler veritabanından yükleniyor...");

        // var keyword (Java 10+) - tip çıkarımı, List<Product> yazmaya gerek yok
        var urunler = productRepository.findByAktifTrue();

        // Ürün entity listesini ProductRecord listesine dönüştür
        return urunler.stream()
                .map(this::entitydenRecordaDonustur) // method reference
                .toList(); // Java 16+ immutable liste
    }

    /**
     * Tek ürün detayını getirir.
     *
     * key="#id" → parametre "id" değeri cache anahtarı olur (örn: "product::1")
     * unless="#result == null" → sonuç null ise cache'leme (ürün yoksa cache kirlenmesin)
     * condition="#id > 0" → ID pozitif değilse cache'leme
     *
     * @param id Ürün ID'si
     * @return Ürün kaydı (bulunamazsa boş)
     */
    @Cacheable(
            value = "product",        // Cache adı (TTL: 10 dk)
            key = "#id",              // SpEL: parametre adıyla anahtar
            unless = "#result == null", // Sonuç null ise cache'leme
            condition = "#id > 0"     // Geçersiz ID için cache'leme
    )
    public ProductRecord urunGetir(Long id) {
        // Cache'de yoksa veritabanından oku
        log.info("CACHE MISS - Ürün veritabanından yükleniyor: id={}", id);

        // findById Optional<Product> döndürür - map ile dönüştür
        return productRepository.findById(id)
                .map(this::entitydenRecordaDonustur) // Dönüşüm
                .orElse(null); // Bulunamazsa null (unless koşulu cache'lemeyi önler)
    }

    /**
     * Kategoriye göre ürün listesi - cache destekli.
     *
     * key="#kategori" → her kategori için ayrı cache girişi
     * Örn: "products::Elektronik", "products::Giyim" gibi farklı cache'ler
     *
     * @param kategori Kategori adı
     * @return Kategoriye ait ürünler
     */
    @Cacheable(value = "products", key = "'kategori:' + #kategori")
    public List<ProductRecord> kategoriUrunleri(String kategori) {
        // Cache'de yoksa veritabanından sorgula
        log.info("CACHE MISS - Kategori ürünleri yükleniyor: {}", kategori);

        // Kategoriye ait ürünleri getir ve dönüştür
        return productRepository.findByKategoriAndAktifTrue(kategori)
                .stream()
                .map(this::entitydenRecordaDonustur)
                .toList();
    }

    // ── @CACHEPUT ──────────────────────────────────────────────────────────────

    /**
     * Ürün kaydeder ve cache'i günceller.
     *
     * @CachePut: Metod her zaman çalışır, sonuç cache'e yazılır.
     * @Cacheable'dan farkı: metodu ATLAMAZ, her seferinde çalıştırır.
     * Kullanım amacı: Yeni kayıt veya güncelleme sonrası cache'i senkronize et.
     *
     * key="#result.id()" → dönüş değerinin id() metodunu çağır (record method)
     *
     * @param urun Kaydedilecek ürün entity'si
     * @return Kaydedilen ürünün record'u
     */
    @CachePut(value = "product", key = "#result.id()")
    public ProductRecord urunKaydet(Product urun) {
        // Her zaman çalışır - kaydedip cache'i günceller
        log.info("Ürün kaydediliyor ve cache güncelleniyor: {}", urun.getAd());

        // Veritabanına kaydet
        var kaydedilenUrun = productRepository.save(urun);

        // Ürün listesi cache'ini de temizle (yeni ürün eklendi)
        redisTemplate.delete("redis-demo:products::all");

        // Kaydedilen entity'yi record'a çevir ve döndür (cache'e bu yazılır)
        return entitydenRecordaDonustur(kaydedilenUrun);
    }

    /**
     * Mevcut ürünü günceller, cache'i senkronize eder.
     *
     * @CachePut ile cache her zaman güncel kalır.
     * Güncelleme sonrası eski cache değeri silinmez - üzerine yazılır.
     *
     * @param id   Güncellenecek ürün ID'si
     * @param urun Yeni değerler
     * @return Güncellenmiş ürün record'u
     */
    @CachePut(value = "product", key = "#id")
    public ProductRecord urunGuncelle(Long id, Product urun) {
        // Ürün var mı kontrol et
        var mevcutUrun = productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Ürün bulunamadı: " + id));

        // Güncellenecek alanları set et
        mevcutUrun.setAd(urun.getAd());                     // Ad güncelle
        mevcutUrun.setAciklama(urun.getAciklama());         // Açıklama güncelle
        mevcutUrun.setFiyat(urun.getFiyat());               // Fiyat güncelle
        mevcutUrun.setStokMiktari(urun.getStokMiktari());   // Stok güncelle
        mevcutUrun.setKategori(urun.getKategori());         // Kategori güncelle

        // Veritabanına kaydet
        var guncellenenUrun = productRepository.save(mevcutUrun);

        // Liste cache'lerini temizle (içerik değişti)
        redisTemplate.delete("redis-demo:products::all");
        redisTemplate.delete("redis-demo:products::kategori:" + urun.getKategori());

        // Güncellenmiş record'u döndür (cache'e bu değer yazılır)
        return entitydenRecordaDonustur(guncellenenUrun);
    }

    // ── @CACHEEVICT ────────────────────────────────────────────────────────────

    /**
     * Tek ürünü siler ve cache'den kaldırır.
     *
     * @CacheEvict: Belirtilen cache girdisini siler.
     * key="#id" → Sadece bu ID'nin cache'ini sil
     * beforeInvocation=false (varsayılan) → Metod başarıyla tamamlandıktan sonra sil
     *
     * @param id Silinecek ürün ID'si
     */
    @CacheEvict(value = "product", key = "#id")
    public void urunSil(Long id) {
        // Önce veritabanından sil, sonra @CacheEvict cache'i temizler
        log.info("Ürün siliniyor ve cache'den kaldırılıyor: id={}", id);

        // Ürünü pasife al (hard delete yerine soft delete)
        productRepository.findById(id).ifPresent(urun -> {
            urun.setAktif(false); // Aktifliği kaldır
            productRepository.save(urun); // Güncelle
        });

        // Tüm ürün listesi cache'ini de temizle
        redisTemplate.delete("redis-demo:products::all");
    }

    /**
     * Belirli kategorideki tüm cache'i temizler.
     *
     * allEntries=true → Bu cache altındaki TÜM girişleri sil
     * (belirli bir key yerine)
     */
    @CacheEvict(value = "products", allEntries = true)
    public void tumUrunCachiniTemizle() {
        // Cache sıfırlama - örn: toplu güncelleme sonrası
        log.warn("Tüm ürün cache'leri temizlendi!");
        // Metod gövdesi boş olabilir - sadece cache temizleme yapılır
    }

    // ── @CACHING ───────────────────────────────────────────────────────────────

    /**
     * @Caching: Birden fazla cache anotasyonunu tek metotta birleştirir.
     *
     * Bu örnekte:
     * - "product" cache'inden sil (bu ürünün detay cache'i)
     * - "products" cache'inden tüm girişleri sil (liste cache'leri)
     *
     * @param id Silinecek ürün ID'si
     */
    @Caching(evict = {
            // Tekil ürün cache'ini sil
            @CacheEvict(value = "product", key = "#id"),
            // Tüm ürün listesi cache'lerini sil
            @CacheEvict(value = "products", allEntries = true)
    })
    public void urunSilVeTumCacheTemizle(Long id) {
        // Her iki cache'i de temizleyerek sil
        log.info("Ürün ve tüm ilgili cache'ler temizleniyor: id={}", id);

        // Ürünü veritabanından kalıcı sil (hard delete)
        productRepository.deleteById(id);
    }

    // ── MANüEL REDİS TEMPLATE ─────────────────────────────────────────────────

    /**
     * RedisTemplate ile manuel cache işlemi.
     * @Cacheable kullAnmadan doğrudan Redis'e yazma/okuma yapar.
     *
     * Bu yöntem şu durumlarda kullanılır:
     * - Özel TTL belirleme
     * - Koşullu cache yazma
     * - Cache durumunu programatik kontrol
     *
     * @param id Ürün ID'si
     * @return CacheResult (sealed interface) - Hit, Miss veya Expired
     */
    public CacheResult<ProductRecord> manuelCacheKontrolu(Long id) {
        // Redis anahtarını oluştur
        var anahtar = "manual:product:" + id;

        // Redis'ten değeri oku
        var redisValue = redisTemplate.opsForValue().get(anahtar);

        // Değer cache'de var mı?
        if (redisValue instanceof ProductRecord urun) {
            // Cache HIT - kalan TTL süresini bul
            var kalanTtl = redisTemplate.getExpire(anahtar, TimeUnit.SECONDS);

            // Pattern matching ile tip dönüşümü yapıldı (instanceof + cast tek satırda)
            log.debug("Manuel cache HIT: anahtar={}, kalanTtl={}s", anahtar, kalanTtl);

            // Sealed interface factory metodu ile HIT sonucu döndür
            return CacheResult.hit(urun, anahtar, kalanTtl != null ? kalanTtl : 0);
        }

        // Cache MISS - veritabanından yükle
        log.info("Manuel cache MISS: anahtar={}", anahtar);

        // Veritabanından ürünü bul
        var urunOpt = productRepository.findById(id);

        if (urunOpt.isEmpty()) {
            // Ürün hiç yok - Miss döndür
            return CacheResult.miss(anahtar, "Veritabanında ürün bulunamadı: id=" + id);
        }

        // Ürünü record'a çevir
        var urunRecord = entitydenRecordaDonustur(urunOpt.get());

        // Manuel olarak Redis'e yaz (10 dakika TTL)
        redisTemplate.opsForValue().set(anahtar, urunRecord, Duration.ofMinutes(10));

        // İlk kez yüklendiğinden Miss döndür (ama artık cache'de var)
        return CacheResult.miss(anahtar, "Veritabanından yüklendi, cache'e yazıldı");
    }

    // ── YARDIMCI METODLAR ─────────────────────────────────────────────────────

    /**
     * Product entity'sini ProductRecord'a dönüştürür.
     * Bu dönüşüm cache'de nesnelerin record olarak saklanmasını sağlar.
     *
     * @param urun Dönüştürülecek entity
     * @return Değiştirilemez (immutable) ProductRecord
     */
    private ProductRecord entitydenRecordaDonustur(Product urun) {
        // Record'un tüm alanlarını entity'den doldur
        return new ProductRecord(
                urun.getId(),                // Benzersiz kimlik
                urun.getAd(),                // Ürün adı
                urun.getAciklama(),          // Açıklama
                urun.getFiyat(),             // Fiyat
                urun.getStokMiktari(),       // Stok
                urun.getKategori(),          // Kategori
                urun.getResimUrl(),          // Resim URL
                urun.isAktif(),              // Aktiflik
                urun.getOlusturmaTarihi(),   // Oluşturma tarihi
                urun.getGuncellemeTarihi()   // Güncelleme tarihi
        );
    }
}
