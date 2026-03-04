# Integración con Claude API (Haiku)

## Dependencia Maven

```xml
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>1.1.0</version>
</dependency>
```

Alternativamente, usar `java.net.http.HttpClient` directamente para evitar dependencias pesadas
(coherente con el enfoque sin Spring del proyecto).

## ClaudeService

### Con HTTP directo (recomendado para este proyecto)

```java
public class ClaudeService {
    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final String apiKey = ConfigUtil.get("anthropic.api.key");
    private final Gson gson = new Gson();
    
    /**
     * Extrae datos estructurados de un presupuesto a partir de texto libre.
     * Usa Claude Haiku por su bajo coste y rapidez suficiente para esta tarea.
     */
    public DatosPresupuesto parsePresupuesto(String transcripcion) {
        String systemPrompt = """
            Eres un asistente que extrae datos de presupuestos a partir de texto dictado 
            por autónomos españoles. El texto viene de transcripción de voz, así que puede 
            contener errores o estar poco estructurado.
            
            Extrae los datos y responde SOLO con un JSON válido con esta estructura:
            {
              "cliente_nombre": "string",
              "cliente_telefono": "string o null",
              "cliente_email": "string o null",
              "descripcion_general": "string - descripción breve del trabajo",
              "lineas": [
                {
                  "concepto": "string",
                  "tipo": "material | mano_obra | servicio",
                  "cantidad": number,
                  "precio_unitario": number
                }
              ],
              "notas": "string o null - cualquier observación adicional"
            }
            
            Reglas:
            - Si no se especifica cantidad, asumir 1
            - Separar siempre material de mano de obra si se mencionan ambos
            - Los precios son sin IVA salvo que se diga explícitamente "con IVA"
            - Si falta algún dato, poner null
            - NO añadir campos extra, solo los indicados
            """;
        
        String requestBody = gson.toJson(Map.of(
            "model", "claude-haiku-4-5-20251001",
            "max_tokens", 1024,
            "system", systemPrompt,
            "messages", List.of(
                Map.of("role", "user", "content", 
                    "Transcripción del audio del autónomo:\n\n" + transcripcion)
            )
        ));
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .timeout(Duration.ofSeconds(15))
            .build();
        
        try {
            HttpResponse<String> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.error("Claude API error {}: {}", response.statusCode(), response.body());
                throw new ServiceException("Error procesando presupuesto con IA");
            }
            
            // Extraer el texto de la respuesta
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = json.getAsJsonArray("content")
                .get(0).getAsJsonObject()
                .get("text").getAsString();
            
            // Parsear el JSON del presupuesto
            return gson.fromJson(content, DatosPresupuesto.class);
            
        } catch (IOException | InterruptedException e) {
            throw new ServiceException("Error comunicando con Claude API", e);
        }
    }
}
```

### Modelo de datos para el parseo

```java
public class DatosPresupuesto {
    private String clienteNombre;
    private String clienteTelefono;
    private String clienteEmail;
    private String descripcionGeneral;
    private List<LineaDato> lineas;
    private String notas;
    
    public static class LineaDato {
        private String concepto;
        private String tipo;  // material, mano_obra, servicio
        private double cantidad;
        private double precioUnitario;
    }
}
```

## Coste estimado

- Modelo: `claude-haiku-4-5-20251001` (el más económico)
- Input: ~300-500 tokens (prompt + transcripción)
- Output: ~200-400 tokens (JSON respuesta)
- Coste por presupuesto: ~$0.0003-0.0005
- A 100 presupuestos/mes por usuario: ~$0.03-0.05/mes/usuario

## Prompt Engineering

### Ejemplos de entrada/salida para testing

**Entrada** (transcripción de Whisper):
```
Presupuesto para María García, le cambio el termo eléctrico de 80 litros.
El material son 280 euros, el termo y las conexiones. La mano de obra son 120 euros, 
son unas dos horas de trabajo más o menos. Ah y el desplazamiento 30 euros.
```

**Salida esperada** (JSON de Claude):
```json
{
  "cliente_nombre": "María García",
  "cliente_telefono": null,
  "cliente_email": null,
  "descripcion_general": "Cambio de termo eléctrico de 80 litros",
  "lineas": [
    {
      "concepto": "Termo eléctrico 80L y conexiones",
      "tipo": "material",
      "cantidad": 1,
      "precio_unitario": 280.00
    },
    {
      "concepto": "Mano de obra instalación (2 horas aprox.)",
      "tipo": "mano_obra",
      "cantidad": 1,
      "precio_unitario": 120.00
    },
    {
      "concepto": "Desplazamiento",
      "tipo": "servicio",
      "cantidad": 1,
      "precio_unitario": 30.00
    }
  ],
  "notas": null
}
```

### Manejo de errores de parseo

Claude puede devolver JSON con backticks markdown (```json ... ```). Limpiar antes de parsear:

```java
private String cleanJsonResponse(String response) {
    String cleaned = response.trim();
    if (cleaned.startsWith("```json")) {
        cleaned = cleaned.substring(7);
    } else if (cleaned.startsWith("```")) {
        cleaned = cleaned.substring(3);
    }
    if (cleaned.endsWith("```")) {
        cleaned = cleaned.substring(0, cleaned.length() - 3);
    }
    return cleaned.trim();
}
```

### Reintentos

Si Claude devuelve un JSON inválido (raro con Haiku, pero posible), reintentar una vez
con el mismo prompt. Si falla dos veces, pedir al usuario que repita el audio.
