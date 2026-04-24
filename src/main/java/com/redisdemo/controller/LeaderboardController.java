package com.redisdemo.controller;

// Uygulama DTO ve servisler
import com.redisdemo.dto.ApiResponse;
import com.redisdemo.service.cache.LeaderboardService;
import com.redisdemo.service.cache.LeaderboardService.LeaderboardKayit;
import com.redisdemo.service.pubsub.NotificationPublisher;

// Spring MVC
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Koleksiyon ve tipler
import java.util.List;
import java.util.Map;

/**
 * Liderlik Tablosu REST Controller
 *
 * Redis Sorted Set kullanım örnekleri:
 * GET  /leaderboard/top/{n}         → En çok satan N ürün
 * GET  /leaderboard/rank/{urunId}   → Ürünün sıralaması
 * GET  /leaderboard/score/{urunId}  → Ürünün toplam skoru
 * POST /leaderboard/sale            → Satış kaydı (skor artır)
 * DELETE /leaderboard/{urunId}      → Ürünü tablodan kaldır
 * DELETE /leaderboard/reset         → Tabloyu sıfırla
 */
@RestController // REST controller
@RequestMapping("/leaderboard") // Liderlik tablosu endpoint'leri
public class LeaderboardController {

    // Liderlik tablosu servisi
    private final LeaderboardService leaderboardService;
    // Bildirim yayıncısı
    private final NotificationPublisher notificationPublisher;

    @Autowired
    public LeaderboardController(LeaderboardService leaderboardService,
                                 NotificationPublisher notificationPublisher) {
        // Bağımlılıkları ata
        this.leaderboardService = leaderboardService;
        this.notificationPublisher = notificationPublisher;
    }

    /**
     * En çok satan N ürünü döndürür.
     * Redis ZREVRANGE - yüksek skordan düşüğe sıralı.
     *
     * GET /api/leaderboard/top/10
     */
    @GetMapping("/top/{n}")
    public ResponseEntity<ApiResponse<List<LeaderboardKayit>>> enCokSatanlar(
            @PathVariable int n) {
        // Sorted Set'ten en yüksek skorlu N ürünü al
        var liste = leaderboardService.enCokSatanlar(n);
        return ResponseEntity.ok(
                ApiResponse.basarili("En çok satan " + n + " ürün", liste));
    }

    /**
     * Bir ürünün liderlik tablosundaki sıralamasını döndürür.
     * Redis ZREVRANK komutu.
     *
     * GET /api/leaderboard/rank/{urunId}
     */
    @GetMapping("/rank/{urunId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> urunSiralamasi(
            @PathVariable Long urunId) {

        // Sıralamayı al
        var siralama = leaderboardService.urunSiralamasi(urunId);
        // Toplam skoru al
        var skor = leaderboardService.urunSkoru(urunId);

        // Sıralama sonucu
        var sonuc = Map.<String, Object>of(
                "urunId", urunId,
                "siralama", siralama == -1 ? "Listede yok" : "#" + siralama,
                "toplamSatis", (long) skor
        );

        return ResponseEntity.ok(ApiResponse.basarili("Ürün sıralaması", sonuc));
    }

    /**
     * Satış kaydeder - Sorted Set skoru artırır.
     * Redis ZINCRBY - atomik skor artırma.
     *
     * POST /api/leaderboard/sale?urunId=1&adet=5
     */
    @PostMapping("/sale")
    public ResponseEntity<ApiResponse<Map<String, Object>>> satisKaydet(
            @RequestParam Long urunId,
            @RequestParam(defaultValue = "1") int adet) {

        // ZINCRBY ile skoru atomik artır
        leaderboardService.satisKaydedildi(urunId, adet);

        // Güncel skoru oku
        var yeniSkor = leaderboardService.urunSkoru(urunId);
        var siralama = leaderboardService.urunSiralamasi(urunId);

        // Pub/Sub ile satış bildirimi yayınla
        notificationPublisher.bildirimYayinla(
                new NotificationPublisher.BildirimMesaji(
                        "SATIS",
                        "Ürün #" + urunId + " - " + adet + " adet satıldı",
                        Map.of("urunId", urunId.toString(), "adet", String.valueOf(adet)),
                        java.time.LocalDateTime.now()
                )
        );

        return ResponseEntity.ok(ApiResponse.basarili("Satış kaydedildi", Map.of(
                "urunId", urunId,
                "eklenenAdet", adet,
                "toplamSatis", (long) yeniSkor,
                "siralama", siralama
        )));
    }

    /**
     * Liderlik tablosu istatistikleri.
     *
     * GET /api/leaderboard/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> istatistikler() {

        // Toplam ürün sayısı
        var toplamUrun = leaderboardService.toplamUrunSayisi();
        // İlk 3 sıradaki ürünler
        var podiyum = leaderboardService.enCokSatanlar(3);

        var stats = Map.<String, Object>of(
                "toplamUrunSayisi", toplamUrun,
                "podiyum", podiyum
        );

        return ResponseEntity.ok(ApiResponse.basarili("Liderlik tablosu istatistikleri", stats));
    }

    /**
     * Ürünü liderlik tablosundan kaldırır.
     *
     * DELETE /api/leaderboard/{urunId}
     */
    @DeleteMapping("/{urunId}")
    public ResponseEntity<ApiResponse<Void>> urunKaldir(@PathVariable Long urunId) {
        // ZREM: Sorted Set'ten üyeyi sil
        leaderboardService.urunKaldir(urunId);
        return ResponseEntity.ok(ApiResponse.basarili("Ürün liderlik tablosundan kaldırıldı"));
    }

    /**
     * Liderlik tablosunu sıfırlar (periyodik yenileme için).
     *
     * DELETE /api/leaderboard/reset
     */
    @DeleteMapping("/reset")
    public ResponseEntity<ApiResponse<Void>> tabloSifirla() {
        // Tüm tabloyu sil
        leaderboardService.tabloSifirla();
        return ResponseEntity.ok(ApiResponse.basarili("Liderlik tablosu sıfırlandı"));
    }
}
