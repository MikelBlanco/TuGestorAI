package org.gestorai.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Centraliza la configuración de la aplicación.
 * Carga {@code config.properties} del classpath; las variables de entorno
 * sobreescriben el fichero (útil en producción).
 */
public class ConfigUtil {

    private static final Properties props = new Properties();

    static {
        try (InputStream is = ConfigUtil.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (IOException e) {
            throw new RuntimeException("No se pudo cargar config.properties", e);
        }
        // Variables de entorno sobreescriben el fichero
        props.stringPropertyNames().forEach(key -> {
            String envKey = key.toUpperCase().replace('.', '_');
            String envVal = System.getenv(envKey);
            if (envVal != null) {
                props.setProperty(key, envVal);
            }
        });
    }

    private ConfigUtil() {}

    public static String get(String key) {
        return props.getProperty(key);
    }

    public static String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
}