package com.redisdemo;

// Spring Boot otomatik yapılandırma için temel import
import org.springframework.boot.SpringApplication;
// @SpringBootApplication anotasyonu için import
import org.springframework.boot.autoconfigure.SpringBootApplication;
// Cache özelliğini etkinleştiren anotasyon
import org.springframework.cache.annotation.EnableCaching;
// Scheduling özelliği için (periyodik cache temizleme vb.)
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Redis Cache Demo - Ana Uygulama Sınıfı
 *
 * Bu proje şu konuları kapsamlı biçimde gösterir:
 * - Spring Cache soyutlaması (@Cacheable, @CachePut, @CacheEvict, @Caching)
 * - Redis ile manuel işlemler (RedisTemplate)
 * - JWT token blacklist ve refresh token yönetimi
 * - Alışveriş sepeti (Redis Hash)
 * - Rate Limiting (Redis Atomic Operations)
 * - OTP (One Time Password) yönetimi
 * - Liderlik tablosu (Redis Sorted Set)
 * - Pub/Sub ile gerçek zamanlı bildirimler
 * - Java 21 özellikleri: Records, Sealed Classes, Pattern Matching, Virtual Threads
 */
// @SpringBootApplication = @Configuration + @EnableAutoConfiguration + @ComponentScan
@SpringBootApplication
// Redis tabanlı önbelleklemeyi etkinleştirir - bu olmadan @Cacheable çalışmaz
@EnableCaching
// Zamanlama özelliklerini etkinleştirir
@EnableScheduling
public class RedisDemoApplication {

    /**
     * Uygulamanın başlangıç noktası (entry point).
     * Java 21'de Virtual Thread'ler application.yml ile etkinleştirildiğinden
     * burada ek yapılandırma gerekmez.
     *
     * @param args komut satırı argümanları
     */
    public static void main(String[] args) {
        // Spring Boot uygulamasını başlat - tüm bean'ler yüklenir ve sunucu ayağa kalkar
        SpringApplication.run(RedisDemoApplication.class, args);

        // Başarılı başlatma mesajı
        System.out.println("""

                ╔═══════════════════════════════════════════════════════╗
                ║         Redis Cache Demo Başarıyla Başlatıldı!        ║
                ╠═══════════════════════════════════════════════════════╣
                ║  Uygulama  : http://localhost:8080/api               ║
                ║  Actuator  : http://localhost:8080/api/actuator      ║
                ║  Redis UI  : http://localhost:8081                   ║
                ║  pgAdmin   : http://localhost:5050                   ║
                ╚═══════════════════════════════════════════════════════╝
                """);
    }
}
