# Generación de PDF con OpenPDF

## Dependencia Maven

```xml
<dependency>
    <groupId>com.github.librepdf</groupId>
    <artifactId>openpdf</artifactId>
    <version>2.0.3</version>
</dependency>
```

## PdfService

### Estructura del PDF de Presupuesto

Un presupuesto profesional español debe incluir:

1. **Cabecera**: Logo (opcional) + datos del autónomo (nombre/razón social, NIF, dirección, teléfono, email)
2. **Datos del documento**: Número de presupuesto, fecha, validez
3. **Datos del cliente**: Nombre, NIF, dirección
4. **Tabla de conceptos**: Concepto, cantidad, precio unitario, importe
5. **Totales**: Subtotal, IVA (21%), Total
6. **Pie**: Condiciones, forma de pago, notas

### Implementación

```java
public class PdfService {
    private static final Logger log = LoggerFactory.getLogger(PdfService.class);
    
    // Fuentes
    private static final Font TITULO_FONT = new Font(Font.HELVETICA, 18, Font.BOLD, 
        new Color(44, 62, 80));
    private static final Font SUBTITULO_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, 
        new Color(44, 62, 80));
    private static final Font NORMAL_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font SMALL_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, 
        new Color(128, 128, 128));
    private static final Font HEADER_TABLE_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, 
        Color.WHITE);
    private static final Font TOTAL_FONT = new Font(Font.HELVETICA, 12, Font.BOLD);
    
    // Colores corporativos
    private static final Color COLOR_PRIMARIO = new Color(41, 128, 185);  // Azul
    private static final Color COLOR_FONDO_HEADER = new Color(41, 128, 185);
    private static final Color COLOR_FONDO_ALTERNO = new Color(245, 245, 245);
    
    /**
     * Genera un PDF de presupuesto profesional.
     * 
     * @param presupuesto datos completos del presupuesto
     * @param usuario datos fiscales del autónomo
     * @return File con el PDF generado
     */
    public File generarPresupuesto(Presupuesto presupuesto, Usuario usuario) {
        String filename = "presupuesto_" + presupuesto.getNumero() + ".pdf";
        File outputFile = new File(ConfigUtil.get("pdf.output.dir"), filename);
        
        try {
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, new FileOutputStream(outputFile));
            document.open();
            
            // 1. Cabecera con datos del autónomo
            agregarCabecera(document, usuario);
            
            // 2. Título y número
            agregarTituloPresupuesto(document, presupuesto);
            
            // 3. Datos del cliente
            agregarDatosCliente(document, presupuesto);
            
            // 4. Descripción general
            if (presupuesto.getDescripcion() != null) {
                document.add(new Paragraph("Descripción: " + presupuesto.getDescripcion(), 
                    NORMAL_FONT));
                document.add(Chunk.NEWLINE);
            }
            
            // 5. Tabla de conceptos
            agregarTablaConceptos(document, presupuesto.getLineas());
            
            // 6. Totales
            agregarTotales(document, presupuesto);
            
            // 7. Pie
            agregarPie(document, presupuesto);
            
            document.close();
            log.info("PDF generado: {}", outputFile.getAbsolutePath());
            
        } catch (DocumentException | IOException e) {
            throw new ServiceException("Error generando PDF", e);
        }
        
        return outputFile;
    }
    
    private void agregarCabecera(Document document, Usuario usuario) throws DocumentException {
        PdfPTable header = new PdfPTable(2);
        header.setWidthPercentage(100);
        header.setWidths(new float[]{1, 2});
        
        // Logo (si existe)
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(Rectangle.NO_BORDER);
        // TODO: cargar logo si usuario tiene uno configurado
        header.addCell(logoCell);
        
        // Datos fiscales
        StringBuilder datos = new StringBuilder();
        datos.append(usuario.getNombreComercial() != null ? 
            usuario.getNombreComercial() : usuario.getNombre());
        datos.append("\n");
        datos.append("NIF: ").append(usuario.getNif()).append("\n");
        if (usuario.getDireccion() != null) datos.append(usuario.getDireccion()).append("\n");
        if (usuario.getTelefono() != null) datos.append("Tel: ").append(usuario.getTelefono()).append("\n");
        if (usuario.getEmail() != null) datos.append(usuario.getEmail());
        
        PdfPCell datosCell = new PdfPCell(new Paragraph(datos.toString(), NORMAL_FONT));
        datosCell.setBorder(Rectangle.NO_BORDER);
        datosCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        header.addCell(datosCell);
        
        document.add(header);
        
        // Línea separadora
        LineSeparator line = new LineSeparator();
        line.setLineColor(COLOR_PRIMARIO);
        document.add(new Chunk(line));
        document.add(Chunk.NEWLINE);
    }
    
    private void agregarTablaConceptos(Document document, List<LineaDetalle> lineas) 
            throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{4, 1.5f, 1, 1.5f, 1.5f});
        
        // Headers
        String[] headers = {"Concepto", "Tipo", "Cant.", "Precio Unit.", "Importe"};
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, HEADER_TABLE_FONT));
            cell.setBackgroundColor(COLOR_FONDO_HEADER);
            cell.setPadding(8);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        
        // Filas
        boolean alterno = false;
        for (LineaDetalle linea : lineas) {
            Color bgColor = alterno ? COLOR_FONDO_ALTERNO : Color.WHITE;
            
            addCell(table, linea.getConcepto(), NORMAL_FONT, bgColor, Element.ALIGN_LEFT);
            addCell(table, traducirTipo(linea.getTipo()), SMALL_FONT, bgColor, Element.ALIGN_CENTER);
            addCell(table, formatDecimal(linea.getCantidad()), NORMAL_FONT, bgColor, Element.ALIGN_CENTER);
            addCell(table, formatMoney(linea.getPrecioUnitario()), NORMAL_FONT, bgColor, Element.ALIGN_RIGHT);
            addCell(table, formatMoney(linea.getImporte()), NORMAL_FONT, bgColor, Element.ALIGN_RIGHT);
            
            alterno = !alterno;
        }
        
        document.add(table);
    }
    
    private void agregarTotales(Document document, Presupuesto p) throws DocumentException {
        PdfPTable totals = new PdfPTable(2);
        totals.setWidthPercentage(40);
        totals.setHorizontalAlignment(Element.ALIGN_RIGHT);
        
        addTotalRow(totals, "Subtotal:", formatMoney(p.getSubtotal()), NORMAL_FONT);
        addTotalRow(totals, "IVA (" + p.getIvaPorcentaje() + "%):", 
            formatMoney(p.getIvaImporte()), NORMAL_FONT);
        addTotalRow(totals, "TOTAL:", formatMoney(p.getTotal()), TOTAL_FONT);
        
        document.add(Chunk.NEWLINE);
        document.add(totals);
    }
    
    private String traducirTipo(String tipo) {
        return switch (tipo) {
            case "material" -> "Material";
            case "mano_obra" -> "Mano de obra";
            case "servicio" -> "Servicio";
            default -> tipo;
        };
    }
    
    private String formatMoney(BigDecimal amount) {
        return String.format("%,.2f €", amount);
    }
}
```

### PDF de Factura

La factura es similar al presupuesto pero incluye:
- Número de factura (serie diferente: F-2025-xxxx)
- Retención de IRPF (normalmente 15% para autónomos)
- Línea adicional en totales: Subtotal, IVA, -IRPF, Total

```java
public File generarFactura(Factura factura, Usuario usuario) {
    // Similar a presupuesto, con estas diferencias:
    // - Título: "FACTURA" en vez de "PRESUPUESTO"
    // - Añadir IRPF en totales
    // - Añadir forma de pago (transferencia, efectivo)
    // - Texto legal de conservación fiscal
}
```

### Formato de números de documento

```java
public class NumeracionService {
    /**
     * Genera el siguiente número de presupuesto: P-2025-0001
     */
    public String siguienteNumeroPresupuesto(long usuarioId) {
        int year = Year.now().getValue();
        int siguiente = presupuestoDao.contarPorUsuarioYAnio(usuarioId, year) + 1;
        return String.format("P-%d-%04d", year, siguiente);
    }
    
    /**
     * Genera el siguiente número de factura: F-2025-0001
     * Las facturas deben ser correlativas sin saltos (requisito fiscal español)
     */
    public String siguienteNumeroFactura(long usuarioId) {
        int year = Year.now().getValue();
        int siguiente = facturaDao.contarPorUsuarioYAnio(usuarioId, year) + 1;
        return String.format("F-%d-%04d", year, siguiente);
    }
}
```

## Consideraciones fiscales españolas

- IVA general: 21% (configurable por usuario, algunos aplican 10% o 4%)
- IRPF: 15% retención estándar (7% los primeros 3 años de actividad)
- Las facturas deben llevar numeración correlativa sin saltos
- Datos obligatorios en factura: NIF emisor/receptor, fecha, descripción, base imponible, tipo IVA, cuota IVA
- Los presupuestos no tienen requisitos fiscales estrictos pero conviene que sean profesionales
