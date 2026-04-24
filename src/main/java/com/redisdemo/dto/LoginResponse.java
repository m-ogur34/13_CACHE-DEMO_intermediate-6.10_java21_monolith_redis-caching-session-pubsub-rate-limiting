package com.redisdemo.dto;

/**
 * Giriş Yanıtı DTO'su - Java 21 Record
 *
 * Başarılı giriş sonucunda döndürülen JWT token çifti.
 * Access token kısa süreli (15 dk), refresh token uzun sürelidir (7 gün).
 *
 * @param accessToken   JWT erişim token'ı (API isteklerinde kullanılır)
 * @param refreshToken  JWT yenileme token'ı (access token yenilemek için)
 * @param tokenTipi     Token tipi (genellikle "Bearer")
 * @param sureSaniye    Access token geçerlilik süresi (saniye)
 * @param kullaniciAdi  Giriş yapan kullanıcının adı
 */
public record LoginResponse(
        String accessToken,    // JWT access token
        String refreshToken,   // JWT refresh token (Redis'te saklanır)
        String tokenTipi,      // "Bearer"
        long sureSaniye,       // Token geçerlilik süresi (saniye)
        String kullaniciAdi    // Giriş yapan kullanıcı
) {

    /**
     * Varsayılan token tipiyle "Bearer" oluşturucu.
     *
     * @param accessToken  Access token
     * @param refreshToken Refresh token
     * @param sureSaniye   Geçerlilik süresi
     * @param kullaniciAdi Kullanıcı adı
     * @return Bearer tipinde LoginResponse
     */
    public static LoginResponse bearer(
            String accessToken,
            String refreshToken,
            long sureSaniye,
            String kullaniciAdi) {
        // Token tipi "Bearer" olarak sabit ayarla
        return new LoginResponse(accessToken, refreshToken, "Bearer", sureSaniye, kullaniciAdi);
    }
}
