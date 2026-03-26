package org.gestorai.util;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Utilidades de seguridad para el panel web y validación de entradas.
 *
 * <p>Cubre:
 * <ul>
 *   <li>Escape de HTML para prevenir XSS en respuestas de servlets</li>
 *   <li>Sanitización de entradas de usuario (longitud, caracteres)</li>
 *   <li>Tokens CSRF para formularios del panel web</li>
 *   <li>Validación de NIF/DNI/NIE español</li>
 *   <li>Validación de email y teléfono</li>
 * </ul>
 * </p>
 */
public final class SecurityUtil {

    private SecurityUtil() {}

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Letras de control para DNI/NIE (posición = número % 23)
    private static final String LETRAS_DNI = "TRWAGMYFPDXBNJZSQVHLCKE";

    private static final Pattern PATRON_EMAIL =
            Pattern.compile("^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$");

    private static final Pattern PATRON_TELEFONO_ES =
            Pattern.compile("^[+]?[0-9\\s\\-]{7,15}$");

    // -------------------------------------------------------------------------
    // Escape HTML (prevención XSS)
    // -------------------------------------------------------------------------

    /**
     * Escapa caracteres especiales HTML para prevenir XSS.
     *
     * <p>Debe usarse al escribir datos de usuario en respuestas HTML de servlets.</p>
     *
     * @param input texto a escapar; {@code null} devuelve cadena vacía
     * @return texto con entidades HTML escapadas
     */
    public static String escapeHtml(String input) {
        if (input == null) return "";
        StringBuilder sb = new StringBuilder(input.length() + 16);
        for (char c : input.toCharArray()) {
            switch (c) {
                case '&'  -> sb.append("&amp;");
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                case '/'  -> sb.append("&#x2F;");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Sanitización de entradas
    // -------------------------------------------------------------------------

    /**
     * Sanitiza una cadena de texto de usuario: elimina espacios extremos y
     * devuelve {@code null} si el resultado está vacío.
     *
     * @param input  cadena de entrada
     * @param maxLen longitud máxima permitida
     * @return texto saneado o {@code null} si era vacío/nulo
     * @throws IllegalArgumentException si supera {@code maxLen} caracteres
     */
    public static String sanitizar(String input, int maxLen) {
        if (input == null) return null;
        String trimmed = input.strip();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > maxLen) {
            throw new IllegalArgumentException(
                    "El texto supera la longitud máxima de " + maxLen + " caracteres.");
        }
        return trimmed;
    }

    /**
     * Sanitiza y valida un NIF/DNI/NIE español.
     * Devuelve el NIF en mayúsculas sin espacios, o lanza excepción si es inválido.
     *
     * @param nif NIF introducido por el usuario
     * @return NIF normalizado en mayúsculas
     * @throws IllegalArgumentException si el formato o la letra de control son incorrectos
     */
    public static String sanitizarNif(String nif) {
        if (nif == null || nif.isBlank()) {
            throw new IllegalArgumentException("El NIF no puede estar vacío.");
        }
        String normalizado = nif.strip().toUpperCase().replace("-", "").replace(" ", "");
        if (!validarNif(normalizado)) {
            throw new IllegalArgumentException("El NIF/DNI/NIE introducido no es válido.");
        }
        return normalizado;
    }

    // -------------------------------------------------------------------------
    // Validación de NIF/DNI/NIE español
    // -------------------------------------------------------------------------

    /**
     * Valida un NIF/DNI/NIE español.
     *
     * <ul>
     *   <li><b>DNI</b>: 8 dígitos + letra de control</li>
     *   <li><b>NIE</b>: X/Y/Z + 7 dígitos + letra de control</li>
     *   <li><b>NIF empresarial</b>: letra A-H/J/N-W + 7 dígitos + dígito/letra de control</li>
     * </ul>
     *
     * @param nif NIF ya normalizado (en mayúsculas, sin espacios ni guiones)
     * @return {@code true} si el formato y la letra de control son correctos
     */
    public static boolean validarNif(String nif) {
        if (nif == null || nif.length() < 2) return false;

        char primero = nif.charAt(0);

        if (Character.isDigit(primero)) {
            return validarDni(nif);
        } else if (primero == 'X' || primero == 'Y' || primero == 'Z') {
            return validarNie(nif);
        } else {
            return validarNifEmpresarial(nif);
        }
    }

    private static boolean validarDni(String dni) {
        if (!dni.matches("\\d{8}[A-Z]")) return false;
        int numero = Integer.parseInt(dni.substring(0, 8));
        char letraEsperada = LETRAS_DNI.charAt(numero % 23);
        return dni.charAt(8) == letraEsperada;
    }

    private static boolean validarNie(String nie) {
        if (!nie.matches("[XYZ]\\d{7}[A-Z]")) return false;
        char inicial = nie.charAt(0);
        int prefijo = switch (inicial) {
            case 'X' -> 0;
            case 'Y' -> 1;
            case 'Z' -> 2;
            default  -> -1;
        };
        int numero = Integer.parseInt(prefijo + nie.substring(1, 8));
        char letraEsperada = LETRAS_DNI.charAt(numero % 23);
        return nie.charAt(8) == letraEsperada;
    }

    private static boolean validarNifEmpresarial(String cif) {
        // Formato: letra + 7 dígitos + carácter de control
        if (!cif.matches("[ABCDEFGHJNPQRSUVW]\\d{7}[0-9A-J]")) return false;

        // Cálculo del dígito de control
        int suma = 0;
        for (int i = 1; i <= 7; i++) {
            int digito = cif.charAt(i) - '0';
            if (i % 2 == 0) {
                suma += digito;
            } else {
                int doble = digito * 2;
                suma += doble > 9 ? doble - 9 : doble;
            }
        }
        int control = (10 - (suma % 10)) % 10;
        char controlLetra = "JABCDEFGHI".charAt(control);

        char ultimo = cif.charAt(8);
        // Algunos tipos de entidad usan letra, otros dígito
        char tipo = cif.charAt(0);
        if ("PQRSNW".indexOf(tipo) >= 0) {
            return ultimo == controlLetra;
        } else if ("ABEH".indexOf(tipo) >= 0) {
            return ultimo == (char) ('0' + control);
        } else {
            return ultimo == (char) ('0' + control) || ultimo == controlLetra;
        }
    }

    // -------------------------------------------------------------------------
    // Validación de email y teléfono
    // -------------------------------------------------------------------------

    /**
     * Comprueba si el email tiene un formato válido.
     *
     * @param email dirección de email a validar
     * @return {@code true} si el formato es válido
     */
    public static boolean validarEmail(String email) {
        if (email == null || email.isBlank()) return false;
        return PATRON_EMAIL.matcher(email.strip()).matches();
    }

    /**
     * Comprueba si el teléfono tiene un formato válido (7-15 dígitos, admite +, espacios y guiones).
     *
     * @param telefono número de teléfono a validar
     * @return {@code true} si el formato es válido
     */
    public static boolean validarTelefono(String telefono) {
        if (telefono == null || telefono.isBlank()) return false;
        return PATRON_TELEFONO_ES.matcher(telefono.strip()).matches();
    }

    // -------------------------------------------------------------------------
    // Tokens CSRF
    // -------------------------------------------------------------------------

    /**
     * Genera un token CSRF criptográficamente seguro de 32 bytes en Base64 URL-safe.
     *
     * <p>Uso típico en servlet:
     * <pre>
     *   String token = SecurityUtil.generarCsrfToken();
     *   req.getSession().setAttribute("csrf_token", token);
     *   // Incluir el token en el formulario HTML como campo oculto
     * </pre>
     * </p>
     *
     * @return token CSRF en Base64 URL-safe (sin padding)
     */
    public static String generarCsrfToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Compara dos tokens CSRF en tiempo constante para prevenir ataques de timing.
     *
     * @param tokenSesion token almacenado en la sesión HTTP
     * @param tokenRequest token enviado en el formulario
     * @return {@code true} si ambos tokens son iguales y no nulos
     */
    public static boolean validarCsrfToken(String tokenSesion, String tokenRequest) {
        if (tokenSesion == null || tokenRequest == null) return false;
        // Comparación en tiempo constante para evitar timing attacks
        byte[] a = tokenSesion.getBytes();
        byte[] b = tokenRequest.getBytes();
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }
}
