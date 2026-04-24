package com.redisdemo.config;

// Java zamanı için Duration sınıfı
import java.time.Duration;
// HashMap - cache konfigürasyon haritası için
import java.util.HashMap;
// Map arayüzü
import java.util.Map;

// Spring Bean tanımlaması
import org.springframework.beans.factory.annotation.Value;
// Konfigürasyon sınıfı
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
// Spring Cache yöneticisi arayüzü
import org.springframework.cache.CacheManager;
// Redis cache konfigürasyon sınıfı
import org.springframework.data.redis.cache.RedisCacheConfiguration;
// Redis cache yöneticisi
import org.springframework.data.redis.cache.RedisCacheManager;
// Redis bağlantı fabrikası
import org.springframework.data.redis.connection.RedisConnectionFactory;
// Redis serileştirme bağlamı
import org.springframework.data.redis.serializer.RedisSerializationContext;
// String serileştirici
import org.springframework.data.redis.serializer.StringRedisSerializer;

// Redis serileştirici (RedisConfig'den inject edilecek)
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Spring Cache Yöneticisi Yapılandırması
 *
 * Bu sınıf, @Cacheable, @CachePut, @CacheEvict gibi Spring Cache anotasyonları için
 * kullanılacak CacheManager'ı yapılandırır.
 *
 * Farklı cache'ler için farklı TTL (Time To Live) değerleri tanımlanır:
 * ┌──────────────────┬──────────────────────────────────────────┐
 * │ Cache Adı        │ TTL           │ Açıklama                 │
 * ├──────────────────┼───────────────┼──────────────────────────┤
 * │ products         │ 5 dakika      │ Ürün listesi             │
 * │ product          │ 10 dakika     │ Tek ürün detayı          │
 * │ userSession      │ 30 dakika     │ Kullanıcı session bilgisi│
 * │ categories       │ 1 saat        │ Kategori listesi         │
 * │ searchResults    │ 2 dakika      │ Arama sonuçları          │
 * └──────────────────┴───────────────┴──────────────────────────┘
 */
@Configuration
public class CacheConfig {

    // application.yml'den ürün listesi TTL değerini oku
    @Value("${app.cache.product-list-ttl:300}")
    private long productListTtl;

    // application.yml'den ürün detayı TTL değerini oku
    @Value("${app.cache.product-detail-ttl:600}")
    private long productDetailTtl;

    // application.yml'den kullanıcı session TTL değerini oku
    @Value("${app.cache.user-session-ttl:1800}")
    private long userSessionTtl;

    /**
     * Redis CacheManager bean'i oluşturur.
     *
     * CacheManager, Spring Cache soyutlama katmanının kalbidir.
     * Hangi cache'in nerede saklandığını ve nasıl serileştirileceğini yönetir.
     *
     * @param connectionFactory Redis bağlantı fabrikası
     * @param redisJsonSerializer JSON serileştirici (RedisConfig'den)
     * @return Yapılandırılmış CacheManager
     */
    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            RedisSerializer<Object> redisJsonSerializer) {

        // ── VARSAYILAN CACHE KONFIGÜRASYONU ──────────────────────────────────────
        // Tüm cache'ler bu varsayılan konfigürasyondan miras alır
        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                // Varsayılan TTL: 10 dakika - konfigürasyonda override edilebilir
                .entryTtl(Duration.ofSeconds(600))
                // Cache anahtarlarını String olarak serialize et (Redis'te okunabilir)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer())
                )
                // Cache değerlerini JSON olarak serialize et (tip bilgisiyle)
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(redisJsonSerializer)
                )
                // Null değerleri cache'leme - bellek tasarrufu sağlar
                .disableCachingNullValues()
                // Cache anahtar öneki - farklı uygulamalar aynı Redis'i paylaşabilir
                .prefixCacheNameWith("redis-demo:");

        // ── CACHE'E ÖZGÜ KONFIGÜRASYONLAR ────────────────────────────────────────
        // Her cache için farklı TTL ve ayar belirlenebilir
        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        // Ürün listesi cache'i - sık değişmediği için 5 dakika yeterli
        cacheConfigurations.put("products",
                defaultConfig.entryTtl(Duration.ofSeconds(productListTtl)));

        // Tek ürün detayı cache'i - 10 dakika
        cacheConfigurations.put("product",
                defaultConfig.entryTtl(Duration.ofSeconds(productDetailTtl)));

        // Kullanıcı session bilgisi - 30 dakika aktif oturum süresi
        cacheConfigurations.put("userSession",
                defaultConfig.entryTtl(Duration.ofSeconds(userSessionTtl)));

        // Kategori listesi - çok nadir değişir, 1 saat geçerli
        cacheConfigurations.put("categories",
                defaultConfig.entryTtl(Duration.ofHours(1)));

        // Arama sonuçları - çabuk eskiyebilir, 2 dakika
        cacheConfigurations.put("searchResults",
                defaultConfig.entryTtl(Duration.ofMinutes(2)));

        // Popüler ürünler - her 15 dakikada güncellenir
        cacheConfigurations.put("popularProducts",
                defaultConfig.entryTtl(Duration.ofMinutes(15)));

        // Kullanıcı profili - 20 dakika
        cacheConfigurations.put("userProfile",
                defaultConfig.entryTtl(Duration.ofMinutes(20)));

        // ── CACHE MANAGER OLUŞTURMA ───────────────────────────────────────────────
        return RedisCacheManager.builder(connectionFactory)
                // Varsayılan cache konfigürasyonunu uygula
                .cacheDefaults(defaultConfig)
                // Cache'e özgü konfigürasyonları ekle
                .withInitialCacheConfigurations(cacheConfigurations)
                // Cache'i tembel oluştur (ilk kullanımda oluşturulur)
                .transactionAware()
                // CacheManager'ı oluştur ve döndür
                .build();
    }
}
