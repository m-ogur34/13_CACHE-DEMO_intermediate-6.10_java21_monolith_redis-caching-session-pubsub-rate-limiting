package com.redisdemo.dto.records;

// JSON serileştirme anotasyonları için Jackson
import com.fasterxml.jackson.annotation.JsonInclude;
// Boş değerleri JSON'a dahil etme kuralı
import com.fasterxml.jackson.annotation.JsonInclude.Include;

// Java 8 tarih/saat tipleri
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ürün Veri Transfer Nesnesi - Java 21 Record Sınıfı
 *
 * Record sınıfları Java 16'da stabilize edilmiştir.
 * Otomatik olarak şunları sağlar:
 * - Tüm alanlar için final ve private
 * - Tüm alanlar için getter metodları (alan adıyla aynı)
 * - equals(), hashCode(), toString() metodları
 * - Tüm alanları alan canonical constructor
 *
 * Cache'de JSON olarak saklanır ve Redis'te şöyle görünür:
 * {
 *   "id": 1,
 *   "ad": "Laptop",
 *   "fiyat": 15000.00,
 *   "kategori": "Elektronik",
 *   ...
 * }
 *
 * @param id             Ürün benzersiz kimliği
 * @param ad             Ürün adı
 * @param aciklama       Ürün açıklaması (null olabilir)
 * @param fiyat          Ürün fiyatı
 * @param stokMiktari    Mevcut stok miktarı
 * @param kategori       Ürün kategorisi
 * @param resimUrl       Ürün resim URL'si (null olabilir)
 * @param aktif          Ürün satışta mı?
 * @param olusturmaTarihi Ürünün oluşturulma tarihi
 * @param guncellemeTarihi Son güncelleme tarihi (null olabilir)
 */
// Null olan alanları JSON çıktısına dahil etme (bellek ve bant genişliği tasarrufu)
@JsonInclude(Include.NON_NULL)
public record ProductRecord(
        Long id,                         // Ürün ID
        String ad,                       // Ürün adı
        String aciklama,                 // Açıklama (opsiyonel)
        BigDecimal fiyat,                // Fiyat - BigDecimal para değerleri için güvenli
        Integer stokMiktari,             // Stok adedi
        String kategori,                 // Kategori adı
        String resimUrl,                 // Resim URL (opsiyonel)
        boolean aktif,                   // Satışta mı?
        LocalDateTime olusturmaTarihi,   // Oluşturma zamanı
        LocalDateTime guncellemeTarihi   // Son güncelleme (opsiyonel)
) {

    /**
     * Compact constructor - veri doğrulama için.
     * Record'larda constructor içinde alanlar henüz atanmamıştır,
     * doğrulama sonrası normal atama gerçekleşir.
     */
    public ProductRecord {
        // Ürün adı boş olamaz
        if (ad == null || ad.isBlank()) {
            throw new IllegalArgumentException("Ürün adı boş olamaz");
        }
        // Fiyat negatif olamaz
        if (fiyat != null && fiyat.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Ürün fiyatı negatif olamaz");
        }
        // Stok miktarı negatif olamaz
        if (stokMiktari != null && stokMiktari < 0) {
            throw new IllegalArgumentException("Stok miktarı negatif olamaz");
        }
    }

    /**
     * Ürünün indirimli fiyatını hesaplar.
     *
     * @param indirimYuzdesi İndirim yüzdesi (0-100 arası)
     * @return İndirimli fiyat
     */
    public BigDecimal indirimliFixat(double indirimYuzdesi) {
        // İndirim çarpanını hesapla (örn: %20 indirim → 0.80 çarpanı)
        var carpan = BigDecimal.valueOf(1.0 - (indirimYuzdesi / 100.0));
        // Orijinal fiyatı çarpanla çarp
        return fiyat.multiply(carpan);
    }

    /**
     * Ürünün stokta olup olmadığını kontrol eder.
     *
     * @return true ise stokta var
     */
    public boolean stoktaVar() {
        // Stok miktarı null değilse ve 0'dan büyükse stokta var
        return stokMiktari != null && stokMiktari > 0;
    }

    /**
     * Cache anahtarı oluşturur.
     * Redis'te "product:1" şeklinde bir anahtar kullanılır.
     *
     * @return Redis anahtar dizgesi
     */
    public String cacheAnahtari() {
        // "product:{id}" formatında anahtar oluştur
        return "product:" + id;
    }
}
