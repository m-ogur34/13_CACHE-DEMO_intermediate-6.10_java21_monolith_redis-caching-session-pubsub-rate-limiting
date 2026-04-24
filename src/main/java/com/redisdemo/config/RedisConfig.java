package com.redisdemo.config;

// Spring bean tanımlaması için
import com.fasterxml.jackson.annotation.JsonTypeInfo;
// Jackson ObjectMapper - JSON dönüşümleri için
import com.fasterxml.jackson.databind.ObjectMapper;
// Jackson Java 8 zaman modülü
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
// Spring context için Bean anotasyonu
import org.springframework.context.annotation.Bean;
// Konfigürasyon sınıfı işaretleyici
import org.springframework.context.annotation.Configuration;
// Redis mesaj dinleyici konteyner
import org.springframework.data.redis.connection.RedisConnectionFactory;
// Redis pub/sub mesaj dinleme için
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
// Redis şablon sınıfı - ana işlem aracı
import org.springframework.data.redis.core.RedisTemplate;
// String tabanlı Redis şablonu - basit key-value işlemler için
import org.springframework.data.redis.core.StringRedisTemplate;
// Redis anahtarlarını String olarak serialize eden sınıf
import org.springframework.data.redis.serializer.StringRedisSerializer;
// Redis değerlerini JSON olarak serialize eden sınıf
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
// Redis serileştirici arayüzü
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * Redis Yapılandırma Sınıfı
 *
 * Bu sınıf şunları yapılandırır:
 * 1. RedisTemplate<String, Object> - Genel amaçlı Redis işlemleri için
 * 2. StringRedisTemplate - Sadece String değerler için (rate limiting vb.)
 * 3. RedisMessageListenerContainer - Pub/Sub mesaj alımı için
 * 4. Özel Jackson ObjectMapper - Java records ve Java 8 tarih tiplerini destekler
 */
@Configuration
public class RedisConfig {

    /**
     * Genel amaçlı RedisTemplate bean'i oluşturur.
     *
     * RedisTemplate, Redis'e yapılan tüm işlemler için ana araçtır.
     * Şu işlem tiplerini destekler:
     * - ValueOperations  → String, sayılar
     * - ListOperations   → Redis List
     * - SetOperations    → Redis Set
     * - HashOperations   → Redis Hash (HashMap benzeri)
     * - ZSetOperations   → Redis Sorted Set (liderlik tablosu vb.)
     *
     * @param connectionFactory Lettuce bağlantı fabrikası (Spring Boot tarafından otomatik sağlanır)
     * @return Yapılandırılmış RedisTemplate örneği
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // Yeni bir RedisTemplate örneği oluştur
        var template = new RedisTemplate<String, Object>();

        // Redis bağlantı fabrikasını bağla (Lettuce istemcisi kullanılır)
        template.setConnectionFactory(connectionFactory);

        // Anahtarlar için String serileştirici kullan - Redis'te okunabilir key'ler
        template.setKeySerializer(new StringRedisSerializer());

        // Hash anahtarları için de String serileştirici
        template.setHashKeySerializer(new StringRedisSerializer());

        // Değerler için JSON serileştirici kullan - tip bilgisi dahil JSON formatı
        var jsonSerializer = redisJsonSerializer();
        template.setValueSerializer(jsonSerializer);

        // Hash değerleri için de JSON serileştirici
        template.setHashValueSerializer(jsonSerializer);

        // Template'i tüm serileştiricilerle başlat
        template.afterPropertiesSet();

        // Yapılandırılmış template'i döndür
        return template;
    }

    /**
     * String değerler için özelleştirilmiş RedisTemplate.
     * Rate limiting, OTP sayaçları gibi düz String işlemler için kullanılır.
     * JSON serileştirme gerekmediğinden performans açısından daha verimlidir.
     *
     * @param connectionFactory Redis bağlantı fabrikası
     * @return StringRedisTemplate örneği
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        // StringRedisTemplate zaten hem key hem value için StringRedisSerializer kullanır
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Redis Pub/Sub mesaj dinleyici konteyneri.
     * Bu konteyner, belirli kanallara abone olan listener'ları yönetir.
     * NotificationSubscriber bu konteyner aracılığıyla mesajları alır.
     *
     * @param connectionFactory Redis bağlantı fabrikası
     * @return Yapılandırılmış dinleyici konteyneri
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        // Yeni konteyner örneği oluştur
        var container = new RedisMessageListenerContainer();

        // Redis bağlantısını konteynere bağla
        container.setConnectionFactory(connectionFactory);

        // Yapılandırılmış konteyneri döndür (listener'lar NotificationConfig'de eklenir)
        return container;
    }

    /**
     * Özel JSON serileştirici oluşturur.
     *
     * Bu serileştirici şunları destekler:
     * - Java records (Record sınıfları)
     * - Java 8 tarih/saat tipleri (LocalDateTime, Instant vb.)
     * - Polimorfik tip bilgisi (Redis'ten okurken doğru tipe dönüşüm)
     *
     * @return Yapılandırılmış JSON serileştirici
     */
    @Bean
    public RedisSerializer<Object> redisJsonSerializer() {
        // Yeni ObjectMapper oluştur - Jackson'ın JSON işlem motoru
        var mapper = new ObjectMapper();

        // Java 8 tarih/saat tiplerini destekle (LocalDateTime, ZonedDateTime vb.)
        mapper.registerModule(new JavaTimeModule());

        // Tarihleri timestamp olarak değil ISO-8601 string olarak serialize et
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Polimorfik tip bilgisini JSON'a dahil et
        // Bu sayede Redis'ten okurken hangi sınıf olduğunu biliriz
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),  // tip doğrulayıcı
                ObjectMapper.DefaultTyping.NON_FINAL,   // final olmayan sınıflar için
                JsonTypeInfo.As.PROPERTY                // tip bilgisini JSON property olarak ekle
        );

        // JSON serileştiriciyi bu mapper ile oluştur ve döndür
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
