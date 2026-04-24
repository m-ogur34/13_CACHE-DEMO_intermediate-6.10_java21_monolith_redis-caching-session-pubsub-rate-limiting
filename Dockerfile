# ==================== AŞAMA 1: DERLEME ====================
# Maven ve Java 21 içeren resmi Eclipse Temurin imajı ile derleme
FROM eclipse-temurin:21-jdk-alpine AS build

# Proje meta bilgileri
LABEL maintainer="Redis Cache Demo"
LABEL description="Java 21 + Spring Boot 3.3 + Redis Cache Demo"

# Çalışma dizinini oluştur
WORKDIR /app

# ── BAĞIMLILIK CACHE OPTIMIZASYONU ──────────────────────────────────────
# Önce sadece pom.xml kopyala - bağımlılıklar değişmezse bu katman cache'lenir
COPY pom.xml .

# Maven wrapper varsa kopyala (yoksa bu satırı kaldır)
# COPY .mvnw .
# COPY .mvn .mvn

# Bağımlılıkları indirirken test'leri atla (sadece bağımlılık indirmesi)
# Bu katman pom.xml değişmediği sürece cache'den gelir = hızlı build
RUN mvn dependency:go-offline -B --no-transfer-progress

# ── UYGULAMA KAYNAK KODUNU KOPYALA ──────────────────────────────────────
# Kaynak kodunu konteyner içine kopyala
COPY src ./src

# ── UYGULAMA DERLEME ──────────────────────────────────────────────────────
# Maven ile derleme yap:
# -DskipTests: Test'leri atla (CI/CD'de ayrı adımda çalışır)
# -B: Batch mode (interaktif çıktı olmadan)
# package: JAR oluştur
RUN mvn package -DskipTests -B --no-transfer-progress

# ==================== AŞAMA 2: ÇALIŞMA ====================
# Sadece JRE (JDK değil) ile minimal imaj - güvenli ve küçük
FROM eclipse-temurin:21-jre-alpine AS runtime

# Güvenlik: Root olmayan kullanıcı ile çalıştır
RUN addgroup -S springgroup && adduser -S springuser -G springgroup

# Çalışma dizini
WORKDIR /app

# Uygulama JAR dosyasını derleme aşamasından kopyala
COPY --from=build /app/target/*.jar app.jar

# JAR dosyasının sahibini değiştir
RUN chown springuser:springgroup app.jar

# Root olmayan kullanıcıya geç (güvenlik)
USER springuser

# ── JVM OPTIMIZASYONLARI ────────────────────────────────────────────────
# Java 21 sanal thread'leri için JVM optimizasyon parametreleri
ENV JAVA_OPTS="\
    -Xms256m \
    -Xmx512m \
    --enable-preview \
    -XX:+UseContainerSupport \
    -XX:MaxRAMPercentage=75.0 \
    -Djava.security.egd=file:/dev/./urandom"

# ── PORT ─────────────────────────────────────────────────────────────────
# Spring Boot'un çalışacağı port
EXPOSE 8080

# ── SAĞLIK KONTROLÜ ───────────────────────────────────────────────────────
# Docker her 30 saniyede uygulamanın çalışıp çalışmadığını kontrol eder
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget -q --spider http://localhost:8080/api/actuator/health || exit 1

# ── BAŞLATMA KOMUTU ───────────────────────────────────────────────────────
# Uygulamayı başlat - JAVA_OPTS çevre değişkenini kullan
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
