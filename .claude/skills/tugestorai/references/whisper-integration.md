# Integración con Whisper API (OpenAI)

## Dependencia

No hay SDK oficial de OpenAI para Java bien mantenido sin Spring. Usar llamadas HTTP directas
con `java.net.http.HttpClient` (incluido en Java 17).

## WhisperService

```java
public class WhisperService {
    private static final Logger log = LoggerFactory.getLogger(WhisperService.class);
    private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey = ConfigUtil.get("openai.api.key");
    
    /**
     * Transcribe un fichero de audio a texto usando Whisper API.
     * Soporta formatos: flac, mp3, mp4, mpeg, mpga, m4a, ogg, wav, webm
     * 
     * @param audioFile fichero de audio (OGG desde Telegram)
     * @return texto transcrito en español
     * @throws ServiceException si hay error en la transcripción
     */
    public String transcribe(File audioFile) {
        try {
            String boundary = "----FormBoundary" + System.currentTimeMillis();
            
            byte[] body = buildMultipartBody(boundary, audioFile);
            
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(WHISPER_URL))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .timeout(Duration.ofSeconds(30))
                .build();
            
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Whisper API error {}: {}", response.statusCode(), response.body());
                throw new ServiceException("Error en transcripción: " + response.statusCode());
            }
            
            // Parsear respuesta JSON
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String text = json.get("text").getAsString();
            
            log.info("Audio transcrito ({} bytes): {} chars", audioFile.length(), text.length());
            return text;
            
        } catch (IOException | InterruptedException e) {
            throw new ServiceException("Error comunicando con Whisper API", e);
        }
    }
    
    private byte[] buildMultipartBody(String boundary, File audioFile) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // Campo: file
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" 
            + audioFile.getName() + "\"\r\n").getBytes());
        baos.write("Content-Type: audio/ogg\r\n\r\n".getBytes());
        baos.write(Files.readAllBytes(audioFile.toPath()));
        baos.write("\r\n".getBytes());
        
        // Campo: model
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes());
        baos.write("whisper-1\r\n".getBytes());
        
        // Campo: language (forzar español)
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"language\"\r\n\r\n".getBytes());
        baos.write("es\r\n".getBytes());
        
        // Campo: prompt (mejorar precisión con contexto)
        baos.write(("--" + boundary + "\r\n").getBytes());
        baos.write("Content-Disposition: form-data; name=\"prompt\"\r\n\r\n".getBytes());
        baos.write("Presupuesto, factura, material, mano de obra, IVA, cliente, instalación, reparación\r\n".getBytes());
        
        // Cierre
        baos.write(("--" + boundary + "--\r\n").getBytes());
        
        return baos.toByteArray();
    }
}
```

## Detalles importantes

### Coste
- Modelo: `whisper-1`
- Precio: $0.006 por minuto de audio
- Audio típico de un autónomo: 15-60 segundos → $0.001 - $0.006 por transcripción
- A 100 presupuestos/mes por usuario: ~$0.30-0.60/mes/usuario

### Optimización de calidad

El parámetro `prompt` mejora significativamente la precisión para vocabulario técnico español.
Incluir palabras clave del dominio: "presupuesto", "factura", "IVA", "material", "mano de obra",
nombres de oficios y herramientas comunes.

### Formato de audio

Telegram envía audios de voz en formato OGG/Opus. Whisper acepta OGG directamente, por lo que
no es necesaria conversión de formato.

### Manejo de errores

- Timeout: establecer 30s, audios largos pueden tardar
- Rate limits: OpenAI limita a ~50 peticiones/minuto en tier gratuito
- Ficheros vacíos o corruptos: validar tamaño mínimo antes de enviar
- Respuesta vacía: a veces Whisper devuelve texto vacío si el audio es ruido; tratar como error
