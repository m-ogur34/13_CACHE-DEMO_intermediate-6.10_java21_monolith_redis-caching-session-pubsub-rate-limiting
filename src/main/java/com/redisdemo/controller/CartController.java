package com.redisdemo.controller;

// Uygulama DTO ve servisler
import com.redisdemo.dto.ApiResponse;
import com.redisdemo.dto.records.CartItemRecord;
import com.redisdemo.service.cache.CartCacheService;
import com.redisdemo.service.cache.LeaderboardService;

// Spring MVC
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Koleksiyon ve para tipleri
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Alışveriş Sepeti REST Controller
 *
 * Redis Hash ile sepet yönetimi endpoint'leri:
 * GET    /cart/{userId}           → Sepeti getir
 * POST   /cart/{userId}/items     → Sepete ürün ekle
 * PUT    /cart/{userId}/items/{urunId} → Miktar güncelle
 * DELETE /cart/{userId}/items/{urunId} → Ürünü kaldır
 * DELETE /cart/{userId}           → Sepeti temizle
 * GET    /cart/{userId}/summary   → Sepet özeti (toplam, adet)
 */
@RestController // REST controller
@RequestMapping("/cart") // Tüm endpoint'ler /cart ile başlar
public class CartController {

    // Sepet cache servisi
    private final CartCacheService cartCacheService;
    // Liderlik tablosu servisi (satış kaydı için)
    private final LeaderboardService leaderboardService;

    @Autowired
    public CartController(CartCacheService cartCacheService,
                          LeaderboardService leaderboardService) {
        // Bağımlılıkları ata
        this.cartCacheService = cartCacheService;
        this.leaderboardService = leaderboardService;
    }

    /**
     * Kullanıcının sepetini getirir.
     *
     * GET /api/cart/{userId}
     */
    @GetMapping("/{userId}")
    public ResponseEntity<ApiResponse<List<CartItemRecord>>> sepetiGetir(
            @PathVariable String userId) {
        // Redis Hash'ten tüm sepet kalemlerini getir
        var kalemler = cartCacheService.sepetiGetir(userId);
        return ResponseEntity.ok(ApiResponse.basarili("Sepet getirildi", kalemler));
    }

    /**
     * Sepete ürün ekler veya mevcut ürünün miktarını artırır.
     *
     * POST /api/cart/{userId}/items
     * Body: CartItemRecord JSON
     */
    @PostMapping("/{userId}/items")
    public ResponseEntity<ApiResponse<String>> sepeteEkle(
            @PathVariable String userId,
            @RequestBody CartItemRecord urun) {
        // Hash'e ürünü ekle veya miktarı artır
        cartCacheService.sepeteEkle(userId, urun);
        return ResponseEntity.ok(
                ApiResponse.basarili(urun.urunAdi() + " sepete eklendi"));
    }

    /**
     * Sepetteki ürünün miktarını günceller.
     *
     * PUT /api/cart/{userId}/items/{urunId}?miktar=3
     */
    @PutMapping("/{userId}/items/{urunId}")
    public ResponseEntity<ApiResponse<String>> miktarGuncelle(
            @PathVariable String userId,
            @PathVariable Long urunId,
            @RequestParam int miktar) {
        // Miktar güncelleme (0 ise ürünü kaldırır)
        cartCacheService.miktarGuncelle(userId, urunId, miktar);

        var mesaj = miktar <= 0
                ? "Ürün sepetten kaldırıldı"
                : "Miktar güncellendi: " + miktar + " adet";

        return ResponseEntity.ok(ApiResponse.basarili(mesaj));
    }

    /**
     * Sepetten belirli ürünü kaldırır.
     *
     * DELETE /api/cart/{userId}/items/{urunId}
     */
    @DeleteMapping("/{userId}/items/{urunId}")
    public ResponseEntity<ApiResponse<String>> sepettenCikar(
            @PathVariable String userId,
            @PathVariable Long urunId) {
        // Hash'ten ürünü sil
        cartCacheService.sepettenCikar(userId, urunId);
        return ResponseEntity.ok(ApiResponse.basarili("Ürün sepetten kaldırıldı"));
    }

    /**
     * Sepeti tamamen temizler.
     *
     * DELETE /api/cart/{userId}
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<ApiResponse<String>> sepetiTemizle(
            @PathVariable String userId) {
        // Tüm sepet Hash'ini sil
        cartCacheService.sepetiTemizle(userId);
        return ResponseEntity.ok(ApiResponse.basarili("Sepet temizlendi"));
    }

    /**
     * Sepet özetini döndürür (toplam tutar, adet, ürün sayısı).
     *
     * GET /api/cart/{userId}/summary
     */
    @GetMapping("/{userId}/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> sepetOzeti(
            @PathVariable String userId) {

        // Sepet bilgilerini hesapla
        var toplamTutar = cartCacheService.sepetToplamTutar(userId);
        var toplamAdet = cartCacheService.sepetToplamAdet(userId);
        var urunCesidi = cartCacheService.sepetUrunCesidiSayisi(userId);

        // Özet map oluştur
        var ozet = Map.<String, Object>of(
                "toplamTutar", toplamTutar,       // Toplam tutar
                "toplamAdet", toplamAdet,          // Toplam ürün adedi
                "urunCesidi", urunCesidi,          // Farklı ürün sayısı
                "sepetDolu", urunCesidi > 0        // Sepet boş mu?
        );

        return ResponseEntity.ok(ApiResponse.basarili("Sepet özeti", ozet));
    }

    /**
     * Siparişi tamamlar - sepeti temizler ve liderlik tablosunu günceller.
     *
     * POST /api/cart/{userId}/checkout
     */
    @PostMapping("/{userId}/checkout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> odemeYap(
            @PathVariable String userId) {

        // Sepet boş mu kontrol et
        if (!cartCacheService.aktifSepetVarMi(userId)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.hata("Sepet boş, ödeme yapılamaz"));
        }

        // Sepetteki ürünleri al (liderlik tablosunu güncellemek için)
        var kalemler = cartCacheService.sepetiGetir(userId);

        // Her ürünün satışını liderlik tablosuna kaydet
        for (var kalem : kalemler) {
            // Liderlik tablosu: bu ürünün skoru satış adedi kadar artır
            leaderboardService.satisKaydedildi(kalem.urunId(), kalem.miktar());
        }

        // Toplam tutarı hesapla (sepeti temizlemeden önce)
        var toplamTutar = cartCacheService.sepetToplamTutar(userId);

        // Ödeme sonrası sepeti temizle
        cartCacheService.sepetiTemizle(userId);

        // Ödeme sonucu özeti
        var sonuc = Map.<String, Object>of(
                "mesaj", "Ödeme başarıyla tamamlandı",
                "toplamTutar", toplamTutar,
                "alinanUrunSayisi", kalemler.size()
        );

        return ResponseEntity.ok(ApiResponse.basarili("Ödeme tamamlandı", sonuc));
    }
}
