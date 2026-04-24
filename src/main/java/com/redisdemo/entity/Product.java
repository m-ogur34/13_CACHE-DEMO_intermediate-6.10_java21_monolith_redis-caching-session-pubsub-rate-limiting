package com.redisdemo.entity;

// JPA anotasyonları - veritabanı eşleşmesi için
import jakarta.persistence.*;
// Lombok - otomatik getter/setter/builder üretimi
import lombok.*;

// Para değerleri için
import java.math.BigDecimal;
// Tarih/saat için
import java.time.LocalDateTime;

/**
 * Ürün JPA Entity'si
 *
 * PostgreSQL'deki "products" tablosuna karşılık gelir.
 * Redis'te ProductRecord olarak cache'lenir.
 *
 * Tablo yapısı:
 * CREATE TABLE products (
 *   id BIGSERIAL PRIMARY KEY,
 *   ad VARCHAR(255) NOT NULL,
 *   aciklama TEXT,
 *   fiyat DECIMAL(10,2) NOT NULL,
 *   stok_miktari INT DEFAULT 0,
 *   kategori VARCHAR(100),
 *   resim_url VARCHAR(500),
 *   aktif BOOLEAN DEFAULT true,
 *   olusturma_tarihi TIMESTAMP,
 *   guncelleme_tarihi TIMESTAMP
 * );
 */
@Entity // Bu sınıf JPA entity'si - veritabanı tablosuna eşlenir
@Table(name = "products") // Tablo adı
@Getter // Lombok: tüm alanlar için getter üret
@Setter // Lombok: tüm alanlar için setter üret
@Builder // Lombok: builder pattern oluştur
@NoArgsConstructor // Lombok: parametresiz constructor (JPA için zorunlu)
@AllArgsConstructor // Lombok: tüm alanları alan constructor
public class Product {

    // Birincil anahtar - otomatik artan
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Ürün adı - boş olamaz, en fazla 255 karakter
    @Column(nullable = false, length = 255)
    private String ad;

    // Açıklama - uzun metin olabileceğinden TEXT tipi
    @Column(columnDefinition = "TEXT")
    private String aciklama;

    // Fiyat - para değerleri için hassas tip: DECIMAL(10,2)
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fiyat;

    // Stok miktarı - varsayılan 0
    @Column(name = "stok_miktari")
    @Builder.Default
    private Integer stokMiktari = 0;

    // Kategori - maksimum 100 karakter
    @Column(length = 100)
    private String kategori;

    // Resim URL - maksimum 500 karakter
    @Column(name = "resim_url", length = 500)
    private String resimUrl;

    // Aktiflik durumu - varsayılan true (satışta)
    @Column(nullable = false)
    @Builder.Default
    private boolean aktif = true;

    // Oluşturma tarihi - ilk kayıtta otomatik set edilir
    @Column(name = "olusturma_tarihi", nullable = false, updatable = false)
    private LocalDateTime olusturmaTarihi;

    // Güncelleme tarihi - her güncellemede otomatik set edilir
    @Column(name = "guncelleme_tarihi")
    private LocalDateTime guncellemeTarihi;

    /**
     * İlk kayıt öncesi çağrılır - oluşturma tarihini set et.
     */
    @PrePersist
    public void kayitOncesi() {
        // Oluşturma tarihini şimdiki zaman olarak ayarla
        this.olusturmaTarihi = LocalDateTime.now();
        // İlk kaydedildiğinde güncelleme tarihi de aynı olsun
        this.guncellemeTarihi = LocalDateTime.now();
    }

    /**
     * Her güncelleme öncesi çağrılır - güncelleme tarihini yenile.
     */
    @PreUpdate
    public void guncellemOncesi() {
        // Güncelleme tarihini şimdiki zaman yap
        this.guncellemeTarihi = LocalDateTime.now();
    }
}
