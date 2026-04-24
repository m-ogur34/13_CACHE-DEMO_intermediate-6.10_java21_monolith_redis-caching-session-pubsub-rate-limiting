package com.redisdemo.dto;

// Java 8 tarih/saat tipleri
import java.time.LocalDateTime;

/**
 * Genel API Yanıt Sarmalayıcısı - Java 21 Record
 *
 * Tüm REST endpoint'ler bu yapıyı döndürür.
 * Tutarlı bir API yanıt formatı sağlar:
 *
 * Başarı:
 * {
 *   "basarili": true,
 *   "mesaj": "Ürün başarıyla getirildi",
 *   "data": { ... },
 *   "zaman": "2024-01-15T10:30:00"
 * }
 *
 * Hata:
 * {
 *   "basarili": false,
 *   "mesaj": "Ürün bulunamadı",
 *   "data": null,
 *   "zaman": "2024-01-15T10:30:00"
 * }
 *
 * @param <T>      Yanıt verisi tipi
 * @param basarili İşlem başarılı mı?
 * @param mesaj    Kullanıcıya gösterilecek mesaj
 * @param data     Döndürülen veri (null olabilir)
 * @param zaman    Yanıt oluşturma zamanı
 */
public record ApiResponse<T>(
        boolean basarili,       // İşlem durumu
        String mesaj,           // Açıklama mesajı
        T data,                 // Veri yükü
        LocalDateTime zaman     // İşlem zamanı
) {

    /**
     * Başarılı yanıt oluşturur (veriyle birlikte).
     *
     * @param mesaj Başarı mesajı
     * @param data  Döndürülecek veri
     * @return Başarılı ApiResponse
     */
    public static <T> ApiResponse<T> basarili(String mesaj, T data) {
        // Başarılı, şimdiki zaman damgalı yanıt oluştur
        return new ApiResponse<>(true, mesaj, data, LocalDateTime.now());
    }

    /**
     * Başarılı yanıt oluşturur (veri olmadan).
     *
     * @param mesaj Başarı mesajı
     * @return Başarılı ApiResponse (data=null)
     */
    public static <T> ApiResponse<T> basarili(String mesaj) {
        // Veri olmadan başarılı yanıt
        return new ApiResponse<>(true, mesaj, null, LocalDateTime.now());
    }

    /**
     * Hata yanıtı oluşturur.
     *
     * @param mesaj Hata mesajı
     * @return Başarısız ApiResponse
     */
    public static <T> ApiResponse<T> hata(String mesaj) {
        // Başarısız, veri içermeyen yanıt
        return new ApiResponse<>(false, mesaj, null, LocalDateTime.now());
    }
}
