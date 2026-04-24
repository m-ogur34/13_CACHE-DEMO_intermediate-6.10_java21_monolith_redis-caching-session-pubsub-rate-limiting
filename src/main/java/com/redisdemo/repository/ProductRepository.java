package com.redisdemo.repository;

// JPA entity
import com.redisdemo.entity.Product;
// Spring Data JPA temel repository arayüzü
import org.springframework.data.jpa.repository.JpaRepository;
// Özel JPQL sorguları için
import org.springframework.data.jpa.repository.Query;
// Sorgu parametreleri için
import org.springframework.data.repository.query.Param;
// Spring stereotype
import org.springframework.stereotype.Repository;

// Dönen tipler için
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Ürün Repository'si
 *
 * JpaRepository<Product, Long> şu metodları otomatik sağlar:
 * - save(), saveAll(), findById(), findAll()
 * - delete(), deleteById(), existsById()
 * - count()
 *
 * Bu arayüzdeki metodlar Spring Data JPA tarafından
 * metod adından otomatik SQL üretilerek gerçeklenir.
 */
@Repository // Spring'e repository bean'i olduğunu bildir
public interface ProductRepository extends JpaRepository<Product, Long> {

    // Kategoriye göre aktif ürünleri bul
    List<Product> findByKategoriAndAktifTrue(String kategori);

    // Ürün adını içeren aktif ürünleri bul (büyük/küçük harf duyarsız)
    List<Product> findByAdContainingIgnoreCaseAndAktifTrue(String ad);

    // Fiyat aralığına göre ürün bul
    List<Product> findByFiyatBetweenAndAktifTrue(BigDecimal minFiyat, BigDecimal maxFiyat);

    // Sadece aktif ürünleri getir
    List<Product> findByAktifTrue();

    // Stokta olan aktif ürünleri getir (stok > 0)
    @Query("SELECT p FROM Product p WHERE p.aktif = true AND p.stokMiktari > 0")
    List<Product> stoktaOlanUrunler();

    // Kategoriye göre ürün sayısını getir
    @Query("SELECT COUNT(p) FROM Product p WHERE p.kategori = :kategori AND p.aktif = true")
    long kategoridekiUrunSayisi(@Param("kategori") String kategori);

    // En pahalı ürünleri bul (fiyata göre azalan sıra)
    @Query("SELECT p FROM Product p WHERE p.aktif = true ORDER BY p.fiyat DESC")
    List<Product> enPahalıUrunler();

    // Belirtilen fiyatın altındaki ürünleri bul
    List<Product> findByFiyatLessThanAndAktifTrue(BigDecimal maxFiyat);

    // E-ticaret için: birden fazla ID ile ürün getir
    List<Product> findByIdInAndAktifTrue(List<Long> ids);

    // Stok miktarı sıfır olan ürünleri bul (stok uyarısı için)
    @Query("SELECT p FROM Product p WHERE p.stokMiktari = 0 AND p.aktif = true")
    List<Product> stokBitenUrunler();

    // Ürünün var olup olmadığını kontrol et (cache eviction öncesi)
    boolean existsByIdAndAktifTrue(Long id);
}
