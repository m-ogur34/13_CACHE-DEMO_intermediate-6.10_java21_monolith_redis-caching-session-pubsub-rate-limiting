package com.redisdemo.dto;

// Jakarta Bean Validation anotasyonları
import jakarta.validation.constraints.NotBlank;

/**
 * Giriş İsteği DTO'su - Java 21 Record
 *
 * Kullanıcı adı ve şifre ile giriş yapmak için kullanılır.
 * POST /auth/login endpoint'ine gönderilir.
 *
 * @param kullaniciAdi  Kullanıcı adı (boş olamaz)
 * @param sifre         Kullanıcı şifresi (boş olamaz)
 */
public record LoginRequest(
        @NotBlank(message = "Kullanıcı adı boş olamaz")
        String kullaniciAdi,    // Sisteme giriş için kullanıcı adı

        @NotBlank(message = "Şifre boş olamaz")
        String sifre            // Düz metin şifre (bcrypt ile karşılaştırılır)
) {}
