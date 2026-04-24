package com.redisdemo.controller;

// Uygulama DTO ve servisler
import com.redisdemo.dto.ApiResponse;
import com.redisdemo.dto.LoginRequest;
import com.redisdemo.dto.LoginResponse;
import com.redisdemo.service.cache.OtpService;
import com.redisdemo.service.cache.RateLimiterService;
import com.redisdemo.service.cache.SessionCacheService;
import com.redisdemo.service.cache.TokenCacheService;

// Spring MVC
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Jakarta Validation
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

// Koleksiyon tipleri
import java.util.List;
import java.util.Map;

/**
 * Kimlik Doğrulama REST Controller
 *
 * Redis token ve session yönetimini gösteren endpoint'ler:
 * POST /auth/login         → Giriş + JWT + Session oluşturma
 * POST /auth/logout        → Çıkış + Token blacklist + Session silme
 * POST /auth/refresh       → Token yenileme (Refresh token)
 * POST /auth/otp/generate  → OTP oluşturma
 * POST /auth/otp/verify    → OTP doğrulama
 * GET  /auth/session       → Session bilgisi
 * GET  /auth/rate-limit    → Rate limit durumu
 */
@RestController // REST controller
@RequestMapping("/auth") // Tüm auth endpoint'leri
public class AuthController {

    // Token cache servisi (JWT blacklist, refresh token)
    private final TokenCacheService tokenCacheService;
    // Session cache servisi
    private final SessionCacheService sessionCacheService;
    // OTP servisi
    private final OtpService otpService;
    // Rate limiter servisi
    private final RateLimiterService rateLimiterService;

    @Autowired
    public AuthController(TokenCacheService tokenCacheService,
                          SessionCacheService sessionCacheService,
                          OtpService otpService,
                          RateLimiterService rateLimiterService) {
        // Bağımlılıkları ata
        this.tokenCacheService = tokenCacheService;
        this.sessionCacheService = sessionCacheService;
        this.otpService = otpService;
        this.rateLimiterService = rateLimiterService;
    }

    /**
     * Kullanıcı girişi - JWT + Refresh Token + Session oluşturur.
     *
     * POST /api/auth/login
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> girisYap(
            @Valid @RequestBody LoginRequest loginRequest,
            HttpServletRequest request) {

        // IP adresini al
        var ipAdresi = request.getRemoteAddr();

        // IP engelli mi kontrol et
        if (rateLimiterService.ipEngelliMi(ipAdresi)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.hata("Bu IP adresi geçici olarak engellenmiştir"));
        }

        // Giriş denemesi rate limit kontrolü
        if (!rateLimiterService.girisDenemesiIzniVarMi(loginRequest.kullaniciAdi())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.hata("Çok fazla hatalı giriş denemesi. 5 dakika bekleyin."));
        }

        // DEMO: Gerçek uygulamada veritabanından kullanıcıyı doğrula
        // Burada statik demo kimlik bilgileri kullanılıyor
        if (!"admin".equals(loginRequest.kullaniciAdi()) || !"password123".equals(loginRequest.sifre())) {
            // Hatalı kimlik bilgileri - giriş denemesi sayılır
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.hata("Kullanıcı adı veya şifre hatalı"));
        }

        // Başarılı giriş - deneme sayacını sıfırla
        rateLimiterService.basariliGirisSonrasiSifirla(loginRequest.kullaniciAdi());

        // DEMO token değerleri (gerçekte JWT kütüphanesiyle üretilir)
        var userId = "user-001";
        var accessToken = "eyJhbGciOiJIUzI1NiJ9.demo_access_token." + System.currentTimeMillis();
        var refreshToken = "refresh_" + userId + "_" + System.currentTimeMillis();

        // Refresh token'ı Redis'e kaydet
        tokenCacheService.refreshTokenKaydet(userId, refreshToken);

        // Session oluştur
        sessionCacheService.sessionOlustur(
                userId,
                loginRequest.kullaniciAdi(),
                loginRequest.kullaniciAdi() + "@demo.com",
                List.of("ROLE_USER", "ROLE_ADMIN"),
                ipAdresi,
                request.getHeader("User-Agent")
        );

        // Yanıt oluştur
        var yanit = LoginResponse.bearer(accessToken, refreshToken, 900L, loginRequest.kullaniciAdi());

        return ResponseEntity.ok(ApiResponse.basarili("Giriş başarılı", yanit));
    }

    /**
     * Çıkış - token kara listeye eklenir, session silinir.
     *
     * POST /api/auth/logout
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> cikisYap(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(required = false, defaultValue = "user-001") String userId) {

        // JWT token'ı header'dan çıkar ("Bearer TOKEN" formatından TOKEN al)
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            var token = authHeader.substring(7); // "Bearer " kısmını çıkar

            // DEMO: Gerçekte JWT'den JTI çıkarılır
            var jti = "demo-jti-" + System.currentTimeMillis();

            // Token'ı kara listeye ekle (kalan süre: 900 saniye = 15 dk)
            tokenCacheService.tokenKaraListeyeEkle(jti, 900);
        }

        // Refresh token'ı sil
        tokenCacheService.refreshTokenSil(userId);

        // Session'ı sonlandır
        sessionCacheService.sessionSonlandir(userId);

        return ResponseEntity.ok(ApiResponse.basarili("Başarıyla çıkış yapıldı"));
    }

    /**
     * Refresh token ile yeni access token alır.
     *
     * POST /api/auth/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<Map<String, String>>> tokenYenile(
            @RequestParam String userId,
            @RequestParam String refreshToken) {

        // Token rotasyonu: eski refresh token ile yeni oluştur
        var yeniRefreshToken = tokenCacheService.tokenRotasyonu(userId, refreshToken);

        if (yeniRefreshToken.isEmpty()) {
            // Geçersiz veya süresi dolmuş refresh token
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.hata("Geçersiz refresh token. Lütfen tekrar giriş yapın."));
        }

        // Yeni access token (demo)
        var yeniAccessToken = "eyJhbGciOiJIUzI1NiJ9.new_access." + System.currentTimeMillis();

        return ResponseEntity.ok(ApiResponse.basarili("Token yenilendi", Map.of(
                "accessToken", yeniAccessToken,
                "refreshToken", yeniRefreshToken.get()
        )));
    }

    /**
     * OTP oluşturur ve "gönderir" (demo).
     *
     * POST /api/auth/otp/generate?email=user@example.com
     */
    @PostMapping("/otp/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> otpOlustur(
            @RequestParam String email) {

        // OTP isteme rate limit kontrolü
        if (!rateLimiterService.otpIstegiIzniVarMi(email)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.hata("15 dakikada en fazla 3 OTP talep edebilirsiniz"));
        }

        // OTP oluştur (6 haneli)
        var otp = otpService.otpOlustur(email);

        // DEMO: Gerçek uygulamada e-posta/SMS ile gönder
        // emailService.gonder(email, "OTP Kodunuz: " + otp);

        return ResponseEntity.ok(ApiResponse.basarili(
                "OTP gönderildi (demo modunda OTP görünür)", Map.of(
                        "email", email,
                        "otp", otp, // DEMO: gerçekte OTP response'a yazılmaz!
                        "sureSaniye", otpService.kalanSure(email)
                )
        ));
    }

    /**
     * OTP'yi doğrular.
     *
     * POST /api/auth/otp/verify?email=user@example.com&otp=123456
     */
    @PostMapping("/otp/verify")
    public ResponseEntity<ApiResponse<String>> otpDogrula(
            @RequestParam String email,
            @RequestParam String otp) {

        // OTP'yi doğrula
        var sonuc = otpService.otpDogrula(email, otp);

        // Sonucu Java switch expression ile işle
        return switch (sonuc) {
            case BASARILI -> ResponseEntity.ok(
                    ApiResponse.basarili("E-posta doğrulandı", sonuc.getMesaj()));
            case YANLIS_OTP -> ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.hata(sonuc.getMesaj()));
            case SURE_DOLDU -> ResponseEntity.status(HttpStatus.GONE)
                    .body(ApiResponse.hata(sonuc.getMesaj()));
            case DENEME_LIMITI_ASILDI -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(ApiResponse.hata(sonuc.getMesaj()));
        };
    }

    /**
     * Kullanıcı session bilgisini döndürür.
     *
     * GET /api/auth/session/{userId}
     */
    @GetMapping("/session/{userId}")
    public ResponseEntity<ApiResponse<Object>> sessionBilgisi(
            @PathVariable String userId) {

        // Session'ı getir
        var session = sessionCacheService.sessionGetir(userId);

        if (session.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.hata("Aktif session bulunamadı"));
        }

        return ResponseEntity.ok(ApiResponse.basarili("Session bilgisi", session.get()));
    }

    /**
     * Rate limit durumunu döndürür.
     *
     * GET /api/auth/rate-limit/{kimlik}
     */
    @GetMapping("/rate-limit/{kimlik}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rateLimitDurumu(
            @PathVariable String kimlik) {

        // Mevcut durum
        var mevcutIstek = rateLimiterService.mevcutIstemSayisi(kimlik);
        var kalanSure = rateLimiterService.kalanSure(kimlik);

        var durum = Map.<String, Object>of(
                "kimlik", kimlik,
                "mevcutIstek", mevcutIstek,
                "kalanSure", kalanSure + " saniye",
                "durum", mevcutIstek > 100 ? "LIMIT_ASILDI" : "NORMAL"
        );

        return ResponseEntity.ok(ApiResponse.basarili("Rate limit durumu", durum));
    }
}
