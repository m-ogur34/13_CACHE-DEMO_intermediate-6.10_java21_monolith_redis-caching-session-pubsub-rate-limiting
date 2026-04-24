package com.redisdemo.controller;

// Uygulama DTO ve servisler
import com.redisdemo.dto.ApiResponse;
import com.redisdemo.dto.records.CacheResult;
import com.redisdemo.dto.records.ProductRecord;
import com.redisdemo.entity.Product;
import com.redisdemo.service.cache.LeaderboardService;
import com.redisdemo.service.cache.ProductCacheService;

// Spring MVC anotasyonları
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Zaman ve koleksiyon
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Ürün REST Controller
 *
 * Cache Demo endpoint'leri:
 * GET  /products          → Tüm ürünler (@Cacheable)
 * GET  /products/{id}     → Tek ürün (@Cacheable)
 * POST /products          → Ürün oluştur (@CachePut)
 * PUT  /products/{id}     → Ürün güncelle (@CachePut)
 * DELETE /products/{id}   → Ürün sil (@CacheEvict)
 * DELETE /products/cache  → Cache temizle (@CacheEvict allEntries)
 * GET  /products/{id}/cache-check → Manuel cache kontrolü (CacheResult)
 */
@RestController // @Controller + @ResponseBody
@RequestMapping("/products") // Tüm endpoint'ler /products ile başlar
public class ProductController {

    // Logger
    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    // Ürün cache servisi
    private final ProductCacheService productCacheService;
    // Liderlik tablosu servisi
    private final LeaderboardService leaderboardService;

    @Autowired
    public ProductController(ProductCacheService productCacheService,
                             LeaderboardService leaderboardService) {
        // Bağımlılıkları ata
        this.productCacheService = productCacheService;
        this.leaderboardService = leaderboardService;
    }

    /**
     * Tüm ürünleri getir - @Cacheable ile cache destekli.
     * İlk istekte DB sorgusu, sonraki isteklerde cache'den döner.
     *
     * GET /api/products
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ProductRecord>>> tumUrunler() {
        // @Cacheable - cache'de varsa DB'ye gitmez
        var urunler = productCacheService.tumUrunleriGetir();
        // Başarılı yanıt döndür
        return ResponseEntity.ok(ApiResponse.basarili("Ürünler getirildi", urunler));
    }

    /**
     * Belirli bir ürünü getir - @Cacheable ile.
     *
     * GET /api/products/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductRecord>> tekUrun(@PathVariable Long id) {
        // Cache'den veya DB'den ürünü getir
        var urun = productCacheService.urunGetir(id);

        // Ürün bulunamadıysa 404 döndür
        if (urun == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.hata("Ürün bulunamadı: id=" + id));
        }

        // Başarılı yanıt
        return ResponseEntity.ok(ApiResponse.basarili("Ürün getirildi", urun));
    }

    /**
     * Kategoriye göre ürün listesi.
     *
     * GET /api/products/kategori/{kategori}
     */
    @GetMapping("/kategori/{kategori}")
    public ResponseEntity<ApiResponse<List<ProductRecord>>> kategorideUrunler(
            @PathVariable String kategori) {
        // Kategori bazlı cache sorgusu
        var urunler = productCacheService.kategoriUrunleri(kategori);
        return ResponseEntity.ok(ApiResponse.basarili(kategori + " kategorisi ürünleri", urunler));
    }

    /**
     * Yeni ürün oluşturur - @CachePut ile cache güncelleme.
     *
     * POST /api/products
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ProductRecord>> urunOlustur(
            @RequestBody Product urun) {
        // @CachePut - kaydeder VE cache'i günceller
        var kaydedilenUrun = productCacheService.urunKaydet(urun);
        // 201 Created yanıtı
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.basarili("Ürün oluşturuldu", kaydedilenUrun));
    }

    /**
     * Mevcut ürünü günceller - @CachePut ile cache senkronizasyonu.
     *
     * PUT /api/products/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductRecord>> urunGuncelle(
            @PathVariable Long id,
            @RequestBody Product urun) {
        try {
            // @CachePut - günceller VE cache'i yazar
            var guncellenenUrun = productCacheService.urunGuncelle(id, urun);
            return ResponseEntity.ok(ApiResponse.basarili("Ürün güncellendi", guncellenenUrun));
        } catch (RuntimeException e) {
            // Ürün bulunamadı
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.hata(e.getMessage()));
        }
    }

    /**
     * Ürünü siler - @CacheEvict ile cache temizleme.
     *
     * DELETE /api/products/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> urunSil(@PathVariable Long id) {
        // @CacheEvict - siler VE cache'den kaldırır
        productCacheService.urunSil(id);
        return ResponseEntity.ok(ApiResponse.basarili("Ürün silindi"));
    }

    /**
     * Tüm ürün cache'ini temizler - @CacheEvict allEntries=true.
     *
     * DELETE /api/products/cache/all
     */
    @DeleteMapping("/cache/all")
    public ResponseEntity<ApiResponse<Void>> tumCacheTemizle() {
        // Tüm cache girdilerini temizle
        productCacheService.tumUrunCachiniTemizle();
        return ResponseEntity.ok(ApiResponse.basarili("Tüm ürün cache'leri temizlendi"));
    }

    /**
     * Manuel cache kontrolü - CacheResult sealed interface kullanımı.
     * Java 21 Pattern Matching for switch gösterimi.
     *
     * GET /api/products/{id}/cache-info
     */
    @GetMapping("/{id}/cache-info")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cacheKontrol(@PathVariable Long id) {
        // Manuel cache kontrolü - CacheResult döner
        var cacheResult = productCacheService.manuelCacheKontrolu(id);

        // Java 21 Pattern Matching for switch ile sonucu işle
        var bilgi = switch (cacheResult) {
            // Cache HIT: veri bulundu
            case CacheResult.Hit<ProductRecord> hit -> Map.of(
                    "durum", "HIT",
                    "mesaj", "Cache'de bulundu",
                    "cacheAnahtari", hit.cacheAnahtari(),
                    "kalanTtl", hit.kalanTtlSaniye() + " saniye",
                    "urunAdi", hit.deger().ad()
            );
            // Cache MISS: veri yok
            case CacheResult.Miss<ProductRecord> miss -> Map.of(
                    "durum", "MISS",
                    "mesaj", miss.neden(),
                    "cacheAnahtari", miss.cacheAnahtari()
            );
            // Cache EXPIRED: süre dolmuş
            case CacheResult.Expired<ProductRecord> expired -> Map.of(
                    "durum", "EXPIRED",
                    "mesaj", "Cache süresi doldu",
                    "cacheAnahtari", expired.cacheAnahtari(),
                    "sureDolumu", expired.sureDolumZamani().toString()
            );
        };

        return ResponseEntity.ok(ApiResponse.basarili("Cache bilgisi", bilgi));
    }
}
