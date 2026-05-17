# Redis Cache ve Session Yönetimi

Java 21 LTS + Spring Boot 3.3 + Redis 7 ile kapsamlı cache ve session yönetimi projesi.

---

## İçindekiler

1. [Redis Nedir?](#1-redis-nedir)
2. [Redis Veri Tipleri](#2-redis-veri-tipleri)
3. [TTL (Time To Live)](#3-ttl-time-to-live)
4. [Redis Persistence: RDB vs AOF](#4-redis-persistence-rdb-vs-aof)
5. [Redis Pub/Sub](#5-redis-pubsub)
6. [Spring Cache Anotasyonları](#6-spring-cache-anotasyonları)
7. [@Cacheable vs RedisTemplate](#7-cacheable-vs-redistemplate)
8. [Cache Stratejileri](#8-cache-stratejileri)
9. [Kullanım Senaryoları](#9-kullanım-senaryoları)
10. [Cache Invalidation Problemi](#10-cache-invalidation-problemi)
11. [TTL Belirleme Stratejisi](#11-ttl-belirleme-stratejisi)
12. [Performans Karşılaştırması](#12-performans-karşılaştırması)
13. [Docker ile Çalıştırma](#13-docker-ile-çalıştırma)
14. [API Dokümantasyonu](#14-api-dokümantasyonu)
15. [Proje Yapısı](#15-proje-yapısı)

---

## 1. Redis Nedir?

**Redis** (Remote Dictionary Server), bellekte çalışan (in-memory) açık kaynaklı bir veri deposudur. 2009'dan beri Salvatore Sanfilippo tarafından geliştirilmektedir.

### In-Memory Database Nedir?

| Özellik | Disk Tabanlı DB (PostgreSQL) | In-Memory DB (Redis) |
|---------|------------------------------|----------------------|
| Veri yeri | Disk (SSD/HDD) | RAM |
| Okuma hızı | ~1-10 ms | ~0.1-1 ms |
| Yazma hızı | ~5-20 ms | ~0.1-1 ms |
| Kapasite | Terabayt | Gigabayt (RAM sınırı) |
| Kalıcılık | Tam | Yapılandırılabilir |
| Kullanım amacı | Ana veri deposu | Cache, session, geçici veri |

### Redis'i Ne Zaman Kullanırsınız?

- **Cache**: Sık okunan, nadir değişen verileri (ürün listesi, kategori ağacı)
- **Session**: Kullanıcı oturum bilgileri
- **Rate Limiting**: API istek hızı kontrolü
- **Pub/Sub**: Gerçek zamanlı mesajlaşma
- **Liderlik Tabloları**: Oyun skor tabloları, en çok satanlar
- **Geçici Veri**: OTP, email doğrulama token'ları
- **Dağıtık Kilit**: Distributed lock (Redisson ile)
- **Sayaç**: Anlık kullanıcı sayısı, sayfa görüntüleme

---

## 2. Redis Veri Tipleri

### Veri Tipi Tablosu

| Veri Tipi | Redis Komutu Örneği | Kullanım Alanı | Bu Projede |
|-----------|--------------------|--------------------|------------|
| **String** | `SET key value` | Cache, sayaç, flag | OTP, token, rate limit |
| **List** | `LPUSH key val` | Kuyruk, log | - |
| **Set** | `SADD key val` | Benzersiz koleksiyon | - |
| **Sorted Set** | `ZADD key score val` | Sıralı liste | Liderlik tablosu |
| **Hash** | `HSET key field val` | Nesne saklama | Alışveriş sepeti |
| **Stream** | `XADD key * field val` | Event log | - |
| **Bitmap** | `SETBIT key offset val` | Kullanıcı aktivitesi | - |
| **HyperLogLog** | `PFADD key val` | Yaklaşık sayım | - |

### String Tipi

```bash
# Değer yaz
SET kullanici:1:ad "Ahmet Yılmaz"
SET sepet:aktif:user123 "true" EX 86400   # 24 saat TTL

# Değer oku
GET kullanici:1:ad

# Sayaç (atomic!)
INCR sayfa:goruntulenme:home   # Otomatik 1 artır
INCRBY sayfa:goruntulenme:home 5   # 5 artır
```

### Hash Tipi (Alışveriş Sepeti)

```bash
# Sepete ürün ekle
HSET cart:user123 product:1 '{"ad":"Laptop","fiyat":15000,"miktar":1}'
HSET cart:user123 product:5 '{"ad":"Mouse","fiyat":250,"miktar":2}'

# Sepetteki tek ürünü oku
HGET cart:user123 product:1

# Tüm sepeti getir
HGETALL cart:user123

# Sepetten ürün sil
HDEL cart:user123 product:5

# Kaç ürün var?
HLEN cart:user123
```

### Sorted Set (Liderlik Tablosu)

```bash
# Ürün satışı kaydet (skor = satış adedi)
ZADD product:leaderboard 150 "product:1"
ZADD product:leaderboard 89 "product:5"
ZADD product:leaderboard 234 "product:3"

# Skoru artır (atomik)
ZINCRBY product:leaderboard 10 "product:1"  # 150 → 160

# En çok satan 10 ürün (yüksekten düşüğe)
ZREVRANGE product:leaderboard 0 9 WITHSCORES

# Ürünün sıralaması (1. kaçıncı)
ZREVRANK product:leaderboard "product:3"   # → 0 (birinci)

# Belirli skor aralığı
ZRANGEBYSCORE product:leaderboard 100 500
```

---

## 3. TTL (Time To Live)

TTL, bir Redis anahtarının ne kadar süre sonra otomatik olarak silineceğini belirler.

```bash
# String ile birlikte TTL ayarla
SET otp:user@email.com "123456" EX 300   # 300 saniye = 5 dakika

# Sonradan TTL ayarla
EXPIRE session:user123 1800   # 30 dakika

# Kalan TTL'yi göster (saniye)
TTL session:user123   # → 1756 (kalan saniye)

# TTL'yi kaldır (kalıcı hale getir)
PERSIST session:user123   # → TTL kaldırıldı

# TTL bilgisi
TTL mevcut_olmayan_anahtar   # → -2 (anahtar yok)
TTL tll_siz_anahtar          # → -1 (TTL yok, kalıcı)
```

### TTL Neden Önemli?

- **Bellek yönetimi**: Eski veriler otomatik temizlenir
- **Güvenlik**: OTP, token gibi hassas veriler otomatik sona erer
- **Tutarlılık**: Stale (bayat) veri belirli sürede temizlenir
- **Maliyet**: Gereksiz veri birikmez, RAM tasarrufu yapılır

---

## 4. Redis Persistence: RDB vs AOF

Redis varsayılan olarak in-memory çalışır; sunucu yeniden başlarsa veri kaybolur. Persistence ile veri diske yazılır.

### RDB (Redis Database) - Anlık Görüntü

```
Avantaj: Küçük dosya, hızlı yeniden yükleme, tam yedek
Dezavantaj: Son snapshot'tan sonraki veri kaybolur
Kullanım: Yedekleme, cache (veri kaybı tolere edilebilir)

Yapılandırma (redis.conf):
save 900 1      # 900 saniyede 1 değişiklik olursa kaydet
save 300 10     # 300 saniyede 10 değişiklik olursa kaydet
save 60 10000   # 60 saniyede 10.000 değişiklik olursa kaydet
```

### AOF (Append Only File) - İşlem Günlüğü

```
Avantaj: Minimum veri kaybı (her yazma kaydedilir)
Dezavantaj: Büyük dosya, yavaş yeniden yükleme
Kullanım: Kritik veriler (session, token), finansal işlemler

Yapılandırma:
appendonly yes
appendfsync everysec    # Her saniye diske yaz (denge)
# appendfsync always   # Her yazma işleminde (çok güvenli, yavaş)
# appendfsync no       # İşletim sistemine bırak (hızlı, riskli)
```

### Bu Projede

```yaml
# docker-compose.yml Redis yapılandırması:
command: >
  redis-server
  --appendonly yes          # AOF aktif
  --appendfsync everysec    # Her saniye flush
  --maxmemory 256mb         # Maksimum 256MB RAM
  --maxmemory-policy allkeys-lru  # Dolunca en az kullanılanı sil
```

---

## 5. Redis Pub/Sub

Publish/Subscribe: Yayıncı mesaj gönderir, tüm aboneler anında alır.

```bash
# Terminal 1 - Abone ol
SUBSCRIBE notifications
PSUBSCRIBE order.*    # Pattern ile abone ol

# Terminal 2 - Mesaj gönder
PUBLISH notifications '{"tip":"STOK","icerik":"Laptop tükendi"}'
PUBLISH order.created '{"siparisId":"ORD-001","durum":"ALINDI"}'
```

### Bu Projedeki Pub/Sub Akışı

```
NotificationPublisher.bildirimYayinla()
    ↓
redisTemplate.convertAndSend("notifications", jsonMesaj)
    ↓
Redis Pub/Sub Engine
    ↓
NotificationSubscriber.onMessage()   ← Tüm subscribe'lılar alır
    ↓
bildirimIsle() / siparisIsle()
```

**Önemli Not**: Redis Pub/Sub **fire-and-forget**'tir. Mesaj gönderildiğinde aktif abone yoksa mesaj **kaybolur**. Mesaj garantisi için **Redis Streams** kullanın.

---

## 6. Spring Cache Anotasyonları

### @EnableCaching

```java
@SpringBootApplication
@EnableCaching   // ← Bu olmadan aşağıdaki anotasyonlar çalışmaz!
public class Application { }
```

### @Cacheable - Cache'den Oku

```java
// İlk istek: DB'den yükle + cache'e yaz
// Sonraki istekler: cache'den dön (DB'ye gitmez!)
@Cacheable(value = "products", key = "'all'")
public List<Product> tumUrunler() { ... }

// SpEL örnekleri
@Cacheable(value = "product", key = "#id")
@Cacheable(value = "product", key = "#user.id + ':' + #type")
@Cacheable(value = "product", key = "@beanName.metod(#param)")

// Koşullu cache
@Cacheable(
    value = "products",
    key = "#id",
    condition = "#id > 0",           // Bu koşul sağlanırsa cache'le
    unless = "#result == null"       // Sonuç null ise cache'leme
)
```

### @CachePut - Cache'i Güncelle

```java
// Her zaman metodu çalıştırır + sonucu cache'e YAZAR
// Güncelleme işlemlerinde cache senkronizasyonu için
@CachePut(value = "product", key = "#id")
public Product urunGuncelle(Long id, Product urun) { ... }

// Dönüş değerinden key almak için
@CachePut(value = "product", key = "#result.id")
public Product kaydet(Product urun) { ... }
```

### @CacheEvict - Cache'den Sil

```java
// Belirli key'i sil
@CacheEvict(value = "product", key = "#id")
public void sil(Long id) { ... }

// Tüm cache girdilerini sil
@CacheEvict(value = "products", allEntries = true)
public void tumunuTemizle() { ... }

// Metot başlamadan önce sil (default: false = sonra sil)
@CacheEvict(value = "product", key = "#id", beforeInvocation = true)
public void silOnceCachiTemizle(Long id) { ... }
```

### @Caching - Birden Fazla Anotasyon

```java
@Caching(
    evict = {
        @CacheEvict(value = "product", key = "#id"),
        @CacheEvict(value = "products", allEntries = true)
    },
    put = {
        @CachePut(value = "deletedProducts", key = "#id")
    }
)
public void karmasikIslem(Long id) { ... }
```

---

## 7. @Cacheable vs RedisTemplate

| Özellik | @Cacheable | RedisTemplate |
|---------|------------|---------------|
| Kullanım kolaylığı | Çok kolay (anotasyon) | Orta (kod gerekli) |
| Esneklik | Düşük | Çok yüksek |
| TTL kontrolü | CacheConfig'den | Her işlemde |
| Veri tipleri | Sadece method dönüşü | String, List, Set, Hash, ZSet |
| Atomic işlemler | Hayır | Evet (INCR, GETSET vb.) |
| Pub/Sub | Hayır | Evet |
| Özel serileştirme | Sınırlı | Tam kontrol |
| Kullanım amacı | Basit cache | Kompleks Redis işlemleri |

**Ne zaman hangisi?**

```java
// @Cacheable: Basit method sonucu cache'leme için
@Cacheable("products")
public List<Product> getAll() { return repo.findAll(); }

// RedisTemplate: Rate limiting, Pub/Sub, Sorted Set gibi özel işlemler için
public boolean rateLimitKontrol(String ip) {
    Long count = redisTemplate.opsForValue().increment("rate:" + ip);
    if (count == 1) redisTemplate.expire("rate:" + ip, Duration.ofMinutes(1));
    return count <= 100;
}
```

---

## 8. Cache Stratejileri

### Cache-Aside (Lazy Loading) - Bu Projede Kullanılan

```
Uygulama → Cache → (HIT) → Döndür
                ↓ (MISS)
          Veritabanı → Cache'e Yaz → Döndür
```

```java
// @Cacheable bu stratejiyi otomatik uygular
@Cacheable(value = "product", key = "#id")
public Product getProduct(Long id) {
    return repository.findById(id).orElse(null);
}
```

**Avantaj**: Sadece istenen veri cache'lenir, bellek tasarrufu
**Dezavantaj**: İlk istek her zaman yavaş (cold start)

### Write-Through

```
Yazma → Hem Cache'e HEM DB'ye eş zamanlı yaz
```

```java
@CachePut(value = "product", key = "#result.id")
public Product saveProduct(Product p) {
    return repository.save(p); // DB'ye yaz
    // @CachePut sonucu otomatik cache'e yazar
}
```

**Avantaj**: Cache her zaman güncel
**Dezavantaj**: Her yazma iki işlem gerektirir (yavaş yazma)

### Write-Behind (Write-Back)

```
Yazma → Önce Cache'e yaz → Arka planda asenkron DB güncelle
```

**Avantaj**: Çok hızlı yazma
**Dezavantaj**: Cache-DB tutarsızlık riski, kayıp veri riski

### Read-Through

```
Uygulama → Cache →  (HIT) → Döndür
                 → (MISS) → Cache, DB'den yükler → Döndür
```

**Fark**: Cache-Aside'da uygulama DB'ye gider; Read-Through'da cache katmanı gider.

---

## 9. Kullanım Senaryoları

### 1. JWT Token Blacklist

```
Sorun: JWT stateless → sunucu iptal edemez
Çözüm: Logout'ta token'ı Redis'e ekle, her istekte kontrol et

Redis: blacklist:jwt:{jti} → "revoked"  [TTL: kalan token süresi]
```

### 2. Refresh Token Saklama

```
Redis: refresh:{userId} → {tokenString}  [TTL: 7 gün]

Güvenlik: Token rotasyonu - her kullanımda yeni token üret
```

### 3. Email Verification Token

```
Redis: email-verify:{email} → {UUID}  [TTL: 24 saat]

Tek kullanımlık: Doğrulama sonrası Redis'ten sil
```

### 4. Ürün Listesi Cache (5 dk TTL)

```
İlk istek:  DB sorgusu → Cache'e yaz → Döndür
Sonraki:    Cache'den döndür (DB sorgusu yok!)
Güncelleme: @CacheEvict → Cache temizle → Sonraki istek DB'den yükler
```

### 5. Shopping Cart (Redis Hash)

```
HSET cart:user123 product:1 '{"ad":"Laptop","miktar":1,"fiyat":15000}'
HSET cart:user123 product:5 '{"ad":"Mouse","miktar":2,"fiyat":250}'
HGETALL cart:user123  → Tüm sepet
TTL: 24 saat (her işlemde yenilenir)
```

### 6. Rate Limiting

```
INCR rate:limit:192.168.1.1    → 1 (ilk istek)
EXPIRE rate:limit:192.168.1.1 60  → 60 saniyelik pencere
INCR → 2, 3, ... , 100
INCR → 101 → LIMIT AŞILDI! → 429 Too Many Requests
(60 saniye sonra anahtar silinir, sayaç sıfırlanır)
```

### 7. OTP (One Time Password)

```
SET otp:user@email.com "847291" EX 300   → 5 dakika TTL

Doğrulama:
  1. GET otp:user@email.com → "847291"
  2. Kullanıcının girdiği ile karşılaştır
  3. Eşleşirse: DEL otp:user@email.com  → Tek kullanımlık!
```

### 8. Leaderboard (Sorted Set)

```
ZADD product:leaderboard 150 "product:1"   → 150 satış
ZINCRBY product:leaderboard 10 "product:1" → 160 satış (atomik!)
ZREVRANGE product:leaderboard 0 9 WITHSCORES → Top 10
ZREVRANK product:leaderboard "product:1"   → Sıralama
```

### 9. Pub/Sub Bildirimleri

```
Publisher:  PUBLISH notifications '{"tip":"STOK","icerik":"..."}'
Subscriber: Tüm bağlı istemciler anında alır
```

---

## 10. Cache Invalidation Problemi

> "Cache invalidation, bilgisayar biliminin en zor iki probleminden biridir." - Phil Karlton

### Problem Tipleri

**1. Stale Data (Bayat Veri)**
```
DB güncellendi → Cache eski veriyi döndürmeye devam eder
Çözüm: @CacheEvict ile her güncellemede cache temizle
```

**2. Cache Stampede (Sürü Etkisi)**
```
TTL doldu → Aynı anda 1000 istek → Hepsi DB'ye gider → DB çöküyor
Çözüm: Mutex/Lock, Probabilistic Early Expiration, Background Refresh
```

**3. Thundering Herd**
```
Redis yeniden başladı → Tüm cache boş → Tüm istekler DB'ye
Çözüm: Warm-up stratejisi, kademeli yükleme
```

### Bu Projede Önlemler

```java
// @CacheEvict ile tutarlılık
@CacheEvict(value = {"product", "products"}, allEntries = true)
public void urunGuncelle(Product urun) { ... }

// TTL ile stale data önleme
// products cache: 5 dakika
// product detay: 10 dakika  
// session: 30 dakika
```

---

## 11. TTL Belirleme Stratejisi

| Veri Türü | Önerilen TTL | Neden |
|-----------|-------------|-------|
| Ürün listesi | 5 dakika | Sık güncellenmez, stale tolere edilir |
| Ürün detayı | 10 dakika | Fiyat/stok değişebilir |
| Kategori listesi | 1 saat | Nadiren değişir |
| Kullanıcı profili | 20 dakika | Oturum süresi içinde güncel |
| Session | 30 dakika | Aktif kullanım penceresi |
| OTP | 5 dakika | Güvenlik gerekliliği |
| Email token | 24 saat | Kullanıcı zamanına bırak |
| Refresh token | 7 gün | Uzun süreli oturum |
| Rate limit penceresi | 1 dakika | Sliding window |
| Shopping cart | 24 saat | Alışveriş süreci |

**Kural**: TTL ne kadar kısa → veri ne kadar güncel, ama DB yükü artar

---

## 12. Performans Karşılaştırması

### Test Senaryosu: 1000 eşzamanlı istek, ürün listesi (100 ürün)

| Senaryo | Ortalama Yanıt | P99 Yanıt | DB Sorgu Sayısı |
|---------|---------------|-----------|-----------------|
| Cache yok | ~45 ms | ~120 ms | 1000 sorgu |
| Cache var (hit) | ~2 ms | ~5 ms | 0 sorgu |
| Cache var (miss) | ~48 ms | ~125 ms | 1 sorgu |

**Sonuç**: Cache ile %96 daha hızlı, DB yükü %99.9 azaldı

### Neden Bu Kadar Hızlı?

```
PostgreSQL: Disk I/O + Network + SQL Parse + Query Plan + Result Set
     ≈ 1-50 ms

Redis: RAM lookup + Network
     ≈ 0.1-1 ms

Fark: Redis 10-50x daha hızlı
```

---

## 13. Docker ile Çalıştırma

### Ön Gereksinimler

```bash
# Docker ve Docker Compose kurulu mu kontrol et
docker --version       # Docker 20.x veya üstü
docker compose version # Docker Compose 2.x veya üstü
```

### Hızlı Başlangıç

```bash
# 1. Projeyi klonla
git clone https://github.com/kullanici/redis-cache-demo.git
cd redis-cache-demo

# 2. Ortam değişkenlerini ayarla
cp .env.example .env
# .env dosyasını düzenle (opsiyonel)

# 3. Tüm servisleri başlat
docker compose up --build

# Arka planda çalıştırmak için
docker compose up --build -d
```

### Servis URL'leri

| Servis | URL | Kimlik Bilgisi |
|--------|-----|----------------|
| Spring Boot API | http://localhost:8080/api | - |
| Actuator | http://localhost:8080/api/actuator/health | - |
| Redis Commander | http://localhost:8081 | - |
| pgAdmin | http://localhost:5050 | admin@admin.com / admin123 |

### Redis Commander Kullanımı

1. http://localhost:8081 adresine gidin
2. Sol menüden "local" bağlantısını seçin
3. Anahtarları görüntüleyin:
   - `redis-demo:product:*` → Cache'lenmiş ürünler
   - `cart:*` → Alışveriş sepetleri
   - `otp:*` → OTP'ler
   - `rate:*` → Rate limit sayaçları
   - `product:leaderboard` → Sorted Set liderlik tablosu

### Yararlı Docker Komutları

```bash
# Logları izle
docker compose logs -f app
docker compose logs -f redis

# Servisleri yeniden başlat
docker compose restart app

# Redis CLI'ya bağlan
docker exec -it redis-demo-redis redis-cli

# PostgreSQL'e bağlan
docker exec -it redis-demo-postgres psql -U postgres -d redisdb

# Tüm servisleri durdur ve volume'leri temizle
docker compose down -v
```

### Redis CLI ile Test

```bash
# Redis konteynerine bağlan
docker exec -it redis-demo-redis redis-cli

# Tüm anahtarları listele
KEYS *

# Cache anahtarlarını listele
KEYS redis-demo:*

# Ürün cache'ini kontrol et
GET "redis-demo:product::1"
TTL "redis-demo:product::1"

# Liderlik tablosunu görüntüle
ZREVRANGE product:leaderboard 0 9 WITHSCORES

# Sepet görüntüle
HGETALL cart:user123

# Rate limit sayacı
GET rate:limit:192.168.1.1

# Redis istatistikleri
INFO memory
INFO stats
INFO keyspace
```

---

## 14. API Dokümantasyonu

### Ürün Endpoint'leri

```bash
# Tüm ürünleri getir (cache'li)
GET /api/products

# Tek ürün (cache'li)
GET /api/products/1

# Kategoriye göre
GET /api/products/kategori/Elektronik

# Yeni ürün ekle (@CachePut)
POST /api/products
Content-Type: application/json
{
  "ad": "Laptop",
  "fiyat": 15000,
  "stokMiktari": 50,
  "kategori": "Elektronik"
}

# Ürün güncelle (@CachePut)
PUT /api/products/1

# Ürün sil (@CacheEvict)
DELETE /api/products/1

# Tüm cache temizle
DELETE /api/products/cache/all

# Cache bilgisi
GET /api/products/1/cache-info
```

### Sepet Endpoint'leri

```bash
# Sepeti getir
GET /api/cart/user123

# Sepete ürün ekle
POST /api/cart/user123/items
{"urunId":1,"urunAdi":"Laptop","miktar":1,"birimFiyat":15000,"kategori":"Elektronik"}

# Miktar güncelle
PUT /api/cart/user123/items/1?miktar=3

# Ürün kaldır
DELETE /api/cart/user123/items/1

# Sepeti temizle
DELETE /api/cart/user123

# Sepet özeti
GET /api/cart/user123/summary

# Ödeme (checkout)
POST /api/cart/user123/checkout
```

### Auth Endpoint'leri

```bash
# Giriş (demo: admin/password123)
POST /api/auth/login
{"kullaniciAdi":"admin","sifre":"password123"}

# Çıkış
POST /api/auth/logout
Authorization: Bearer {token}

# Token yenile
POST /api/auth/refresh?userId=user-001&refreshToken={token}

# OTP oluştur
POST /api/auth/otp/generate?email=user@example.com

# OTP doğrula
POST /api/auth/otp/verify?email=user@example.com&otp=123456

# Session bilgisi
GET /api/auth/session/user-001

# Rate limit durumu
GET /api/auth/rate-limit/192.168.1.1
```

### Liderlik Tablosu

```bash
# Top 10
GET /api/leaderboard/top/10

# Ürün sıralaması
GET /api/leaderboard/rank/1

# Satış kaydet
POST /api/leaderboard/sale?urunId=1&adet=5

# İstatistikler
GET /api/leaderboard/stats

# Tabloyu sıfırla
DELETE /api/leaderboard/reset
```

---

## 15. Proje Yapısı

```
redis-cache-demo/
├── src/main/java/com/redisdemo/
│   ├── RedisDemoApplication.java          # Ana sınıf
│   ├── config/
│   │   ├── RedisConfig.java               # Lettuce + RedisTemplate yapılandırması
│   │   ├── CacheConfig.java               # CacheManager + TTL yapılandırması
│   │   └── SecurityConfig.java            # Spring Security (demo)
│   ├── controller/
│   │   ├── ProductController.java         # Ürün endpoint'leri
│   │   ├── CartController.java            # Sepet endpoint'leri
│   │   ├── AuthController.java            # Auth endpoint'leri
│   │   └── LeaderboardController.java     # Liderlik tablosu endpoint'leri
│   ├── entity/
│   │   ├── Product.java                   # Ürün JPA entity
│   │   └── User.java                      # Kullanıcı JPA entity
│   ├── dto/
│   │   ├── records/
│   │   │   ├── CacheResult.java           # Sealed interface (Java 21)
│   │   │   ├── ProductRecord.java         # Ürün record (Java 21)
│   │   │   ├── CartItemRecord.java        # Sepet kalemi record (Java 21)
│   │   │   └── SessionRecord.java         # Session record (Java 21)
│   │   ├── ApiResponse.java               # Genel API yanıt record
│   │   ├── LoginRequest.java              # Giriş isteği record
│   │   └── LoginResponse.java             # Giriş yanıtı record
│   ├── repository/
│   │   ├── ProductRepository.java         # Ürün JPA repository
│   │   └── UserRepository.java            # Kullanıcı JPA repository
│   └── service/
│       ├── cache/
│       │   ├── ProductCacheService.java   # @Cacheable/@CachePut/@CacheEvict
│       │   ├── TokenCacheService.java     # JWT blacklist + refresh token
│       │   ├── CartCacheService.java      # Redis Hash (sepet)
│       │   ├── RateLimiterService.java    # Rate limiting (INCR/EXPIRE)
│       │   ├── OtpService.java            # OTP (TTL + tek kullanımlık)
│       │   ├── LeaderboardService.java    # Sorted Set (liderlik)
│       │   └── SessionCacheService.java   # Kullanıcı session
│       └── pubsub/
│           ├── NotificationPublisher.java # Redis Pub/Sub yayıncı
│           └── NotificationSubscriber.java # Redis Pub/Sub dinleyici
├── src/main/resources/
│   └── application.yml                    # Uygulama yapılandırması
├── Dockerfile                             # Multi-stage build (Java 21)
├── docker-compose.yml                     # App + DB + Redis + UI
├── .env.example                           # Ortam değişkenleri örneği
├── .gitignore
└── pom.xml                                # Maven bağımlılıkları

```

### Java 21 Özellikleri Kullanımı

| Özellik | Nerede Kullanıldı |
|---------|-------------------|
| **Record** | ProductRecord, CartItemRecord, SessionRecord, ApiResponse, LoginRequest/Response |
| **Sealed Interface** | CacheResult (Hit, Miss, Expired) |
| **Pattern Matching switch** | ProductController.cacheKontrol(), OtpService sonuç işleme |
| **Virtual Threads** | application.yml: `spring.threads.virtual.enabled: true` |
| **var keyword** | Tüm servis sınıflarında yerel değişkenler |
| **Text Blocks** | RedisDemoApplication başlangıç mesajı |

---

## Teknoloji Yığını

| Teknoloji | Sürüm | Rol |
|-----------|-------|-----|
| Java | 21 LTS | Programlama dili |
| Spring Boot | 3.3.4 | Framework |
| Spring Data Redis | 3.3.x | Redis entegrasyonu |
| Lettuce | 6.x | Redis istemcisi (async, thread-safe) |
| Redis | 7 | In-memory veri deposu |
| PostgreSQL | 16 | İlişkisel veritabanı |
| Maven | 3.9+ | Bağımlılık yönetimi |
| Lombok | Latest | Boilerplate kod azaltma |
| Jackson | 2.x | JSON serileştirme |
| Docker | 24+ | Konteynerleştirme |
| Docker Compose | 2.x | Çoklu konteyner yönetimi |

---

## Mülakat Soruları

**Q: Cache-Aside (Lazy Loading) vs Write-Through vs Write-Behind farkları nelerdir?**
A: Cache-Aside (bu projede): Uygulama önce cache'i kontrol eder, miss → DB'den oku → cache'e yaz. Cache ve DB'yi uygulama yönetir. En yaygın pattern. Write-Through: Her yazma işleminde hem cache hem DB güncellenir (iki aşamalı). Güvenli ama yavaş. Write-Behind (Write-Back): Önce cache'e yaz, sonra async DB'ye yaz. Hızlı ama cache çöküşünde veri kaybı riski. Read-Through: Cache miss'te otomatik olarak DB'den yükler (Redis + uygulama katmanı yoktur, cache kendisi sağlar).

**Q: Cache invalidation (geçersiz kılma) stratejileri nelerdir?**
A: TTL (Time-To-Live): En basit — belirli süre sonra otomatik expire. Güncelleme anında bile eski veri döner (stale). Event-Based Invalidation: Entity güncellenince `@CacheEvict` ile cache silinir. Anlık tutarlılık. Versioned Key: `product:v3:123` — her güncelleme yeni versiyon key. Eski versiyon otomatik expire olur. Cache Tags: İlgili cache'leri etiketle, kategori değişince tüm kategori cache'ini temizle. Bu projede: `@CacheEvict(allEntries=true)` ile ilgili cache namespace'i temizleme.

**Q: Redis INCR ile rate limiting nasıl çalışır?**
A: `INCR rate-limit:{ip}:{minute}` → atomik artış + ilk çağrıda key oluşturur. `EXPIRE rate-limit:{ip}:{minute} 60` → 60 saniye TTL. Her istekte: count = INCR, count > limit → 429 Too Many Requests. Sorun: INCR ve EXPIRE iki ayrı komut — race condition (INCR sonrası crash → key expire olmaz). Çözüm: Lua script ile atomic veya `SET key 1 EX 60 NX` (ilk kez set + expire aynı anda).

**Q: Redis Sorted Set (ZSET) leaderboard için neden idealdir?**
A: ZSET: her üye bir score ile saklanır. `ZADD leaderboard 9500.0 user:1` → score ile ekle/güncelle. `ZREVRANGE leaderboard 0 9 WITHSCORES` → en yüksek 10'u al (O(log N + K)). `ZRANK leaderboard user:5` → kullanıcının sıralaması. Alternatif: Her query'de DB'yi sıralama → O(N log N) + DB yükü. ZSET: O(log N) ekleme/güncelleme, O(log N + K) top-K sorgusu — oyun skor tabloları, "en çok satanlar" için ideal.

**Q: Redis Hash neden sepet (cart) için uygun?**
A: Hash: `HSET cart:{userId} product:1 2 product:5 1` → aynı key altında field-value çiftleri. `HGETALL cart:{userId}` → tüm sepeti tek sorguda al. `HINCRBY cart:{userId} product:1 1` → atomik miktar artış. `HDEL cart:{userId} product:5` → ürünü sepetten çıkar. Alternatif: JSON string → her güncelleme için tüm JSON parse + stringify. Hash: field bazında işlem, kısmi okuma (`HGET`), memory-efficient (ziplist encoding küçük hash'lerde).

**Q: Redis Pub/Sub neden Kafka gibi kalıcı değildir?**
A: Redis Pub/Sub: fire-and-forget — subscriber offline ise mesaj kaybolur, mesajlar disk'e yazılmaz, consumer group yok. Producer çok hızlı subscriber yavaşsa buffer dolmaz (mesaj düşer). Kafka: disk'e yazar, consumer offline olsa sonradan okuyabilir, consumer group ile yük paylaşımı, replayable. Seçim: Gerçek zamanlı bildirimler (chat, live update) → Redis Pub/Sub yeterli. Kritik event'ler, audit log, event sourcing → Kafka.

**Q: @Cacheable methodName vs SpEL key neden önemlidir?**
A: `@Cacheable(value="product", key="#id")` → Redis key: `product::123`. `@Cacheable(value="product")` → tüm parametreler hash olarak key: `product::SimpleKey[123,active]`. SpEL ile özel key: `key="#userId + ':' + #category"` → `product::5:ELECTRONICS`. Neden önemli: Yanlış key stratejisi → cache pollution (farklı kullanıcıların verisi üst üste yazılır), cache miss (her zaman DB sorgusu), bellek israfı. Multi-tenant sistemlerde tenant ID mutlaka key'de olmalı.

**Q: OTP (One-Time Password) için Redis neden idealdir?**
A: OTP kısa ömürlü (5dk) ve tek kullanımlık. Redis TTL: `SET otp:{email} 123456 EX 300` → 5 dakika sonra otomatik siler. Doğrulama: `GET otp:{email}` ile karşılaştır → `DEL otp:{email}` (tek kullanım). Brute force koruması: `INCR attempt:{email}` ile deneme sayısı, 5 denemede `DEL otp:{email}`. Alternatif: DB'de OTP tablosu → scheduled job ile temizleme gerekir, TTL otomasyonu yok.

---

## Lisans

Bu proje eğitim amaçlıdır. MIT Lisansı altında serbestçe kullanılabilir.
