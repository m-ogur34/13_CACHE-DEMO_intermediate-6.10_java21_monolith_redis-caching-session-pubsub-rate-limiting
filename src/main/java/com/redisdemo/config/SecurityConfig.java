package com.redisdemo.config;

// Spring Bean tanımlaması için
import org.springframework.context.annotation.Bean;
// Konfigürasyon sınıfı
import org.springframework.context.annotation.Configuration;
// Web güvenlik ayarlarını etkinleştirir
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// Lambda DSL yapılandırması için
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
// HTTP oturum yönetimi stratejisi
import org.springframework.security.config.http.SessionCreationPolicy;
// Güvenlik filtre zinciri
import org.springframework.security.web.SecurityFilterChain;
// Şifre kodlama arayüzü
import org.springframework.security.crypto.password.PasswordEncoder;
// BCrypt şifreleme implementasyonu
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Spring Security Yapılandırması
 *
 * Bu demo projesi Redis özelliklerine odaklandığından,
 * güvenlik yapılandırması kasıtlı olarak basit tutulmuştur.
 *
 * ÜRETİM ORTAMI İÇİN: JWT filtresi, rol tabanlı erişim kontrolü,
 * CORS ve CSRF yapılandırmalarını mutlaka ekleyin!
 */
@Configuration
// Web güvenliği etkinleştir
@EnableWebSecurity
public class SecurityConfig {

    /**
     * HTTP güvenlik filtre zincirini yapılandırır.
     * Demo projesi olduğundan tüm endpoint'lere erişime izin verilmiştir.
     *
     * @param http HTTP güvenlik nesnesi
     * @return Yapılandırılmış filtre zinciri
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF koruması - REST API olduğundan devre dışı (stateless)
                .csrf(AbstractHttpConfigurer::disable)

                // CORS yapılandırması - demo için varsayılan
                .cors(AbstractHttpConfigurer::disable)

                // Yetkilendirme kuralları
                .authorizeHttpRequests(auth -> auth
                        // Actuator endpoint'lerine izin ver
                        .requestMatchers("/actuator/**").permitAll()
                        // Kimlik doğrulama endpoint'lerine izin ver
                        .requestMatchers("/auth/**").permitAll()
                        // Demo projesi: tüm diğer isteklere izin ver
                        // ÜRETİM: .anyRequest().authenticated() kullanın!
                        .anyRequest().permitAll()
                )

                // Session yönetimi - STATELESS: her istek JWT token ile doğrulanır
                // Bu şekilde sunucu tarafında session saklanmaz (Redis session yerine JWT)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        // Filtre zincirini oluştur ve döndür
        return http.build();
    }

    /**
     * BCrypt şifre kodlayıcı bean'i.
     * Kullanıcı şifrelerini güvenli biçimde hash'ler.
     * BCrypt otomatik olarak salt ekler ve tekrar denemelerine karşı yavaş çalışır.
     *
     * @return BCryptPasswordEncoder örneği (strength=10)
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // Strength 10 = 2^10 = 1024 round - güvenli ama çok yavaş değil
        return new BCryptPasswordEncoder(10);
    }
}
