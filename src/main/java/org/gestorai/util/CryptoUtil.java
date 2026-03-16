package org.gestorai.util;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Cifrado simétrico AES-256-GCM para proteger datos personales en la base de datos.
 *
 * <p>La clave se carga desde la variable de entorno {@code CRYPTO_SECRET_KEY}
 * (o {@code crypto.secret.key} en {@code config.properties}).
 * Debe ser una clave Base64 de exactamente 32 bytes (256 bits).</p>
 *
 * <p>Formato de almacenamiento en BD: {@code Base64(IV_12bytes || ciphertext_con_tag_16bytes)}.
 * El IV es aleatorio y único por cada llamada a {@link #encrypt}.</p>
 *
 * <p>Uso en DAOs:</p>
 * <pre>
 *   // Al guardar
 *   ps.setString(3, CryptoUtil.encrypt(usuario.getNif()));
 *
 *   // Al leer
 *   usuario.setNif(CryptoUtil.decrypt(rs.getString("nif")));
 * </pre>
 */
public final class CryptoUtil {

    private static final String ALGORITMO     = "AES/GCM/NoPadding";
    private static final int    IV_BYTES       = 12;   // 96 bits — recomendado para GCM
    private static final int    TAG_BITS       = 128;  // longitud del tag de autenticación
    private static final int    CLAVE_BYTES    = 32;   // 256 bits

    private static final SecretKey CLAVE;
    private static final SecureRandom RNG = new SecureRandom();

    static {
        String raw = ConfigUtil.get("crypto.secret.key");
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "La variable de cifrado 'crypto.secret.key' / 'CRYPTO_SECRET_KEY' no está configurada. " +
                    "Genera una clave con: openssl rand -base64 32");
        }
        byte[] claveBytes;
        try {
            claveBytes = Base64.getDecoder().decode(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "El valor de 'crypto.secret.key' no es Base64 válido.", e);
        }
        if (claveBytes.length != CLAVE_BYTES) {
            throw new IllegalStateException(String.format(
                    "La clave de cifrado debe tener 32 bytes (256 bits) al decodificar Base64, " +
                    "pero tiene %d bytes. Genera una con: openssl rand -base64 32",
                    claveBytes.length));
        }
        CLAVE = new SecretKeySpec(claveBytes, "AES");
    }

    private CryptoUtil() {}

    /**
     * Cifra un texto plano con AES-256-GCM.
     *
     * @param texto texto plano a cifrar; si es {@code null} devuelve {@code null}
     * @return cadena Base64 con el IV + criptograma + tag, lista para guardar en BD
     */
    public static String encrypt(String texto) {
        if (texto == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            RNG.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.ENCRYPT_MODE, CLAVE, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cifrado = cipher.doFinal(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Concatenar IV || criptograma_con_tag
            byte[] resultado = new byte[IV_BYTES + cifrado.length];
            System.arraycopy(iv, 0, resultado, 0, IV_BYTES);
            System.arraycopy(cifrado, 0, resultado, IV_BYTES, cifrado.length);

            return Base64.getEncoder().encodeToString(resultado);

        } catch (Exception e) {
            throw new RuntimeException("Error cifrando dato", e);
        }
    }

    /**
     * Descifra un valor cifrado con {@link #encrypt}.
     *
     * @param cifrado cadena Base64 tal como se almacenó en BD; si es {@code null} devuelve {@code null}
     * @return texto plano original
     */
    public static String decrypt(String cifrado) {
        if (cifrado == null) return null;
        try {
            byte[] datos = Base64.getDecoder().decode(cifrado);
            if (datos.length < IV_BYTES + 1) {
                throw new IllegalArgumentException("Dato cifrado demasiado corto");
            }

            byte[] iv         = new byte[IV_BYTES];
            byte[] criptograma = new byte[datos.length - IV_BYTES];
            System.arraycopy(datos, 0, iv, 0, IV_BYTES);
            System.arraycopy(datos, IV_BYTES, criptograma, 0, criptograma.length);

            Cipher cipher = Cipher.getInstance(ALGORITMO);
            cipher.init(Cipher.DECRYPT_MODE, CLAVE, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plano = cipher.doFinal(criptograma);

            return new String(plano, java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            throw new RuntimeException("Error descifrando dato", e);
        }
    }
}
