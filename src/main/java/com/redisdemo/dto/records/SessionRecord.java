package com.redisdemo.dto.records;

// Java 8 tarih/saat tipleri
import java.time.Instant;
// Değişmez liste için
import java.util.List;

/**
 * Kullanıcı Session Bilgisi - Java 21 Record
 *
 * Kullanıcının aktif oturumuna ait bilgileri saklar.
 * Redis'te String olarak (JSON) saklanır:
 *
 *   SET session:{userId} {JSON} EX 1800
 *
 * Örnek:
 *   session:user123 → {
 *     "kullaniciId": "user123",
 *     "kullaniciAdi": "ahmet.yilmaz",
 *     "roller": ["ROLE_USER", "ROLE_EDITOR"],
 *     "girisZamani": "2024-01-15T10:30:00Z",
 *     "sonAktiviteZamani": "2024-01-15T11:00:00Z",
 *     "ipAdresi": "192.168.1.1"
 *   }
 *
 * @param kullaniciId          Kullanıcı benzersiz kimliği
 * @param kullaniciAdi         Kullanıcı adı (username)
 * @param email                E-posta adresi
 * @param roller               Kullanıcı rolleri (ROLE_USER, ROLE_ADMIN vb.)
 * @param girisZamani          Oturum açma zamanı
 * @param sonAktiviteZamani    Son işlem zamanı
 * @param ipAdresi             İstemci IP adresi
 * @param cihazBilgisi         User-Agent bilgisi
 */
public record SessionRecord(
        String kullaniciId,           // Kullanıcı ID
        String kullaniciAdi,          // Kullanıcı adı
        String email,                 // E-posta
        List<String> roller,          // Roller listesi (değiştirilemez)
        Instant girisZamani,          // Giriş zamanı
        Instant sonAktiviteZamani,    // Son aktivite
        String ipAdresi,              // IP adresi
        String cihazBilgisi           // User-Agent
) {

    /**
     * Compact constructor - roller listesini değiştirilemez yap.
     */
    public SessionRecord {
        // Roller listesini değiştirilemez (immutable) kopyaya çevir
        roller = roller != null ? List.copyOf(roller) : List.of();
    }

    /**
     * Redis'teki session anahtarını döndürür.
     *
     * @return "session:{kullaniciId}" formatında anahtar
     */
    public String redisAnahtari() {
        // Session anahtarı formatı
        return "session:" + kullaniciId;
    }

    /**
     * Kullanıcının belirtilen role sahip olup olmadığını kontrol eder.
     *
     * @param rol Kontrol edilecek rol (örn: "ROLE_ADMIN")
     * @return true ise kullanıcı bu role sahip
     */
    public boolean rolSahipMi(String rol) {
        // Roller listesinde verilen rol var mı kontrol et
        return roller.contains(rol);
    }

    /**
     * Session'ın belirtilen dakika içinde aktif olup olmadığını kontrol eder.
     *
     * @param dakika Kontrol edilecek süre
     * @return true ise son aktivite bu süre içinde
     */
    public boolean aktifMi(long dakika) {
        // Son aktivite zamanından bu yana geçen süreyi hesapla
        var simdi = Instant.now();
        var fark = simdi.getEpochSecond() - sonAktiviteZamani.getEpochSecond();
        // Fark dakika*60 saniyeden az ise aktif kabul et
        return fark < (dakika * 60);
    }

    /**
     * Aktivite zamanını güncelleyerek yeni bir SessionRecord döndürür.
     * Record'lar immutable olduğundan, güncelleme yeni nesne döndürür.
     *
     * @return Güncellenmiş session
     */
    public SessionRecord aktiviteGuncelle() {
        // Tüm alanlar aynı kalır, sadece sonAktiviteZamani şimdiki zaman olur
        return new SessionRecord(
                kullaniciId,
                kullaniciAdi,
                email,
                roller,
                girisZamani,
                Instant.now(),  // Yeni aktivite zamanı
                ipAdresi,
                cihazBilgisi
        );
    }
}
