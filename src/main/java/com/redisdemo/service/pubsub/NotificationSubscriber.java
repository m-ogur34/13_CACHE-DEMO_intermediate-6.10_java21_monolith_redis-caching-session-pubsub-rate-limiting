package com.redisdemo.service.pubsub;

// Spring bağımlılık enjeksiyonu
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

// Redis dinleyici bileşenleri
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

// JSON dönüşümü için
import com.fasterxml.jackson.databind.ObjectMapper;

// Loglama
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Pub/Sub Dinleyici (Subscriber)
 *
 * Yayıncı (NotificationPublisher) tarafından kanala gönderilen mesajları
 * gerçek zamanlı olarak alır ve işler.
 *
 * Nasıl Çalışır?
 * 1. RedisMessageListenerContainer - arka planda dinleme yapan konteyner
 * 2. MessageListenerAdapter - Spring'in MessageListener soyutlaması
 * 3. ChannelTopic - belirli bir kanala abone ol
 * 4. PatternTopic - pattern eşleşen kanallara abone ol (örn: "order.*")
 *
 * Mesaj Akışı:
 *   Publisher → Redis Channel → Subscriber.mesajAl() → İşlem
 *
 * Bu sınıf üretim için nasıl geliştirilir?
 * - WebSocket entegrasyonu ile gerçek zamanlı istemci bildirimleri
 * - Email/SMS servisiyle dış bildirim
 * - Veritabanına bildirim kaydetme
 */
@Component // Spring bileşeni
public class NotificationSubscriber implements MessageListener {

    // Logger - gelen mesajları izle
    private static final Logger log = LoggerFactory.getLogger(NotificationSubscriber.class);

    // JSON dönüşümü için
    private final ObjectMapper objectMapper;

    // Bildirim kanalı adı
    @Value("${app.redis.notification-channel:notifications}")
    private String bildirimKanali;

    // Sipariş kanalı adı
    @Value("${app.redis.order-channel:orders}")
    private String siparisKanali;

    // Redis mesaj dinleyici konteyneri
    private final RedisMessageListenerContainer container;

    @Autowired
    public NotificationSubscriber(ObjectMapper objectMapper,
                                   RedisMessageListenerContainer container) {
        // ObjectMapper bağımlılığını ata
        this.objectMapper = objectMapper;
        // Container bağımlılığını ata (RedisConfig'de tanımlandı)
        this.container = container;
    }

    /**
     * Dinleyiciyi başlatır ve kanallara abone olur.
     * @PostConstruct yerine @Bean metodu olarak da yapılandırılabilir.
     * Bu metod uygulama başlarken çağrılır.
     */
    @jakarta.annotation.PostConstruct
    public void abonelik() {
        // MessageListenerAdapter: bu sınıfın onMessage() metodunu dinleyici olarak kullan
        var adapter = new MessageListenerAdapter(this, "onMessage");

        // Bildirim kanalına abone ol
        container.addMessageListener(adapter, new ChannelTopic(bildirimKanali));

        // Sipariş kanalına abone ol
        container.addMessageListener(adapter, new ChannelTopic(siparisKanali));

        // Pattern ile tüm "product.*" kanallarına abone ol
        container.addMessageListener(adapter, new PatternTopic("product.*"));

        // Abonelik bilgisini kaydet
        log.info("Redis Pub/Sub kanallarına abone olundu: [{}], [{}], [product.*]",
                bildirimKanali, siparisKanali);
    }

    /**
     * Redis kanalından mesaj geldiğinde otomatik çağrılır.
     * Bu metod tüm abone olunan kanallar için çalışır.
     *
     * @param message Redis'ten gelen ham mesaj (byte[] formatında)
     * @param pattern Eşleşen pattern (null ise ChannelTopic)
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            // Ham byte[] mesajı String'e çevir
            var mesajBody = new String(message.getBody());

            // Hangi kanaldan geldi
            var kanal = new String(message.getChannel());

            // Pattern bilgisi (PatternTopic ise dolu, ChannelTopic ise null)
            var patternBilgisi = pattern != null ? new String(pattern) : "direkt kanal";

            // Mesaj bilgisini log'la
            log.info("Pub/Sub mesajı alındı: kanal={}, pattern={}", kanal, patternBilgisi);
            log.debug("Mesaj içeriği: {}", mesajBody);

            // Kanal tipine göre mesajı işle
            if (kanal.equals(bildirimKanali)) {
                // Bildirim mesajını işle
                bildirimIsle(mesajBody, kanal);
            } else if (kanal.equals(siparisKanali)) {
                // Sipariş mesajını işle
                siparisIsle(mesajBody);
            } else {
                // Diğer kanallar (pattern eşleşme)
                genelMesajIsle(mesajBody, kanal);
            }

        } catch (Exception e) {
            // Mesaj işleme hatası - uygulamayı durdurma, sadece log'la
            log.error("Pub/Sub mesajı işlenirken hata: {}", e.getMessage());
        }
    }

    /**
     * Bildirim mesajını işler.
     *
     * @param jsonMesaj JSON formatında mesaj
     * @param kanal     Mesajın geldiği kanal
     */
    private void bildirimIsle(String jsonMesaj, String kanal) {
        try {
            // JSON'ı BildirimMesaji'na parse et
            var mesaj = objectMapper.readValue(jsonMesaj,
                    NotificationPublisher.BildirimMesaji.class);

            // Java 21 Pattern Matching for switch - mesaj tipine göre işle
            switch (mesaj.tip()) {
                case "STOK_GUNCELLEME" -> {
                    // Stok güncelleme işlemleri
                    log.info("Stok güncelleme bildirimi: {}", mesaj.icerik());
                    // GERÇEK UYGULAMADA: WebSocket ile istemcilere gönder
                    // GERÇEK UYGULAMADA: İlgili kişilere e-posta/SMS gönder
                }
                case "FIYAT_DEGISIKLIGI" -> {
                    // Fiyat değişikliği işlemleri
                    log.info("Fiyat değişikliği bildirimi: {}", mesaj.icerik());
                    // GERÇEK UYGULAMADA: Watchlist kullanıcılarına bildir
                }
                default -> {
                    // Bilinmeyen bildirim tipi - genel işleme
                    log.info("Bilinmeyen bildirim tipi: {}, içerik: {}", mesaj.tip(), mesaj.icerik());
                }
            }
        } catch (Exception e) {
            log.error("Bildirim mesajı parse hatası: {}", e.getMessage());
        }
    }

    /**
     * Sipariş mesajını işler.
     *
     * @param jsonMesaj JSON formatında sipariş mesajı
     */
    private void siparisIsle(String jsonMesaj) {
        try {
            // JSON'ı SiparisMesaji'na parse et
            var mesaj = objectMapper.readValue(jsonMesaj,
                    NotificationPublisher.SiparisMesaji.class);

            // Sipariş durumuna göre işlem yap
            log.info("Sipariş güncellemesi: siparisId={}, durum={}, kullanici={}",
                    mesaj.siparisId(), mesaj.durum(), mesaj.userId());

            // GERÇEK UYGULAMADA:
            // - Müşteriye push notification gönder
            // - E-posta/SMS ile bildir
            // - Sipariş durumu sayfasını güncelle (WebSocket)

        } catch (Exception e) {
            log.error("Sipariş mesajı parse hatası: {}", e.getMessage());
        }
    }

    /**
     * Genel mesaj işleyici (pattern ile eşleşen kanallar için).
     *
     * @param mesaj  Mesaj içeriği
     * @param kanal  Kaynak kanal
     */
    private void genelMesajIsle(String mesaj, String kanal) {
        // Pattern eşleşen kanal mesajını kaydet
        log.info("Genel mesaj: kanal={}, içerik={}", kanal, mesaj);
    }
}
