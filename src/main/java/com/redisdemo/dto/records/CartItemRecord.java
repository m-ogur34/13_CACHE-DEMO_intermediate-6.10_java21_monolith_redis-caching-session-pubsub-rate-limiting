package com.redisdemo.dto.records;

// Para değerleri için BigDecimal
import java.math.BigDecimal;

/**
 * Sepet Ürün Kalemi - Java 21 Record
 *
 * Alışveriş sepetindeki tek bir ürün kalemini temsil eder.
 * Redis Hash'te saklanır - her sepet kalemi, kullanıcı sepetinin bir field'ıdır.
 *
 * Redis'te şu şekilde saklanır:
 *   HSET cart:{userId} product:{productId} {JSON değeri}
 *
 * Örnek Redis yapısı:
 *   cart:user123 → {
 *     "product:1" → {"urunId":1, "urunAdi":"Laptop", "miktar":1, "birimFiyat":15000.00}
 *     "product:5" → {"urunId":5, "urunAdi":"Mouse", "miktar":2, "birimFiyat":250.00}
 *   }
 *
 * @param urunId      Ürün ID'si (Hash field anahtarı olarak kullanılır)
 * @param urunAdi     Ürün adı (görüntüleme için)
 * @param miktar      Sepetteki adet sayısı
 * @param birimFiyat  Birim fiyat (ekleme anındaki fiyat)
 * @param kategori    Ürün kategorisi
 */
public record CartItemRecord(
        Long urunId,            // Ürün benzersiz kimliği
        String urunAdi,         // Ürün adı
        int miktar,             // Miktar (kaç adet)
        BigDecimal birimFiyat,  // Birim fiyat
        String kategori         // Kategori
) {

    /**
     * Compact constructor - veri doğrulama.
     */
    public CartItemRecord {
        // Miktar en az 1 olmalı
        if (miktar <= 0) {
            throw new IllegalArgumentException("Miktar en az 1 olmalıdır: " + miktar);
        }
        // Birim fiyat negatif olamaz
        if (birimFiyat != null && birimFiyat.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Birim fiyat negatif olamaz");
        }
    }

    /**
     * Bu kalemin toplam tutarını hesaplar (miktar × birimFiyat).
     *
     * @return Toplam tutar
     */
    public BigDecimal toplamTutar() {
        // Birim fiyatı miktar ile çarp
        return birimFiyat.multiply(BigDecimal.valueOf(miktar));
    }

    /**
     * Redis Hash'teki field anahtarını döndürür.
     * Her ürün, sepetin Hash'inde "product:{id}" anahtarıyla saklanır.
     *
     * @return Hash field anahtarı
     */
    public String hashField() {
        // "product:{urunId}" formatında alan anahtarı
        return "product:" + urunId;
    }

    /**
     * Miktarı artırarak yeni bir CartItemRecord döndürür.
     * Record'lar immutable olduğundan, güncelleme yeni nesne döndürür.
     *
     * @param eklenecekMiktar Eklenecek adet
     * @return Güncellenmiş sepet kalemi
     */
    public CartItemRecord miktarEkle(int eklenecekMiktar) {
        // Yeni miktar ile yeni bir record oluştur (immutability korunur)
        return new CartItemRecord(urunId, urunAdi, miktar + eklenecekMiktar, birimFiyat, kategori);
    }
}
