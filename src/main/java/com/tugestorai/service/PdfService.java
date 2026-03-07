package com.tugestorai.service;

import org.openpdf.text.*;
import org.openpdf.text.pdf.*;
import org.openpdf.text.pdf.draw.LineSeparator;
import com.tugestorai.exception.ServiceException;
import com.tugestorai.model.Factura;
import com.tugestorai.model.LineaDetalle;
import com.tugestorai.model.Presupuesto;
import com.tugestorai.model.Usuario;
import com.tugestorai.util.ConfigUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Genera documentos PDF profesionales de presupuestos y facturas con OpenPDF 3.x.
 */
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    // Fuentes
    private static final Font FONT_TITULO     = new Font(Font.HELVETICA, 18, Font.BOLD,  new Color(44, 62, 80));
    private static final Font FONT_NORMAL     = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font FONT_SMALL      = new Font(Font.HELVETICA,  8, Font.NORMAL, new Color(100, 100, 100));
    private static final Font FONT_CABECERA   = new Font(Font.HELVETICA, 10, Font.BOLD,  Color.WHITE);
    private static final Font FONT_TOTAL_BOLD = new Font(Font.HELVETICA, 12, Font.BOLD,  new Color(41, 128, 185));

    // Colores
    private static final Color COLOR_PRIMARIO = new Color(41, 128, 185);
    private static final Color COLOR_ALTERNO  = new Color(245, 245, 245);
    private static final Color COLOR_SEPARADOR = new Color(200, 200, 200);

    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    /**
     * Genera un PDF de presupuesto profesional.
     *
     * @param presupuesto datos completos con líneas de detalle
     * @param usuario     datos fiscales del autónomo emisor
     * @return fichero PDF generado
     */
    public File generarPresupuesto(Presupuesto presupuesto, Usuario usuario) {
        String nombre = "presupuesto_" + presupuesto.getNumero().replace("/", "-") + ".pdf";
        File fichero = new File(obtenerDirectorio(), nombre);

        try (Document doc = new Document(PageSize.A4, 50, 50, 50, 50)) {
            PdfWriter.getInstance(doc, new FileOutputStream(fichero));
            doc.open();

            agregarCabecera(doc, usuario);
            agregarTitulo(doc, "PRESUPUESTO", presupuesto.getNumero(), presupuesto.getCreatedAt());
            agregarBloquecliente(doc, presupuesto.getClienteNombre(), presupuesto.getDescripcion());
            agregarTablaConceptos(doc, presupuesto.getLineas());
            agregarTotalesPresupuesto(doc, presupuesto);
            agregarPiePresupuesto(doc, presupuesto.getIvaPorcentaje());

        } catch (DocumentException | IOException e) {
            throw new ServiceException("Error generando PDF presupuesto " + presupuesto.getNumero(), e);
        }

        log.info("PDF presupuesto generado: {}", fichero.getAbsolutePath());
        return fichero;
    }

    /**
     * Genera un PDF de factura con todos los datos fiscales obligatorios.
     *
     * <p>Incluye IRPF en el bloque de totales y el texto legal de conservación
     * de documentos fiscales (obligatorio en España).</p>
     *
     * @param factura datos completos con líneas de detalle
     * @param usuario datos fiscales del autónomo emisor
     * @return fichero PDF generado
     */
    public File generarFactura(Factura factura, Usuario usuario) {
        String nombre = "factura_" + factura.getNumero().replace("/", "-") + ".pdf";
        File fichero = new File(obtenerDirectorio(), nombre);

        try (Document doc = new Document(PageSize.A4, 50, 50, 50, 50)) {
            PdfWriter.getInstance(doc, new FileOutputStream(fichero));
            doc.open();

            agregarCabecera(doc, usuario);
            agregarTitulo(doc, "FACTURA", factura.getNumero(), factura.getCreatedAt());
            agregarBloquecliente(doc, factura.getClienteNombre(), factura.getDescripcion());
            agregarTablaConceptos(doc, factura.getLineas());
            agregarTotalesFactura(doc, factura);
            agregarPieFactura(doc, factura, usuario);

        } catch (DocumentException | IOException e) {
            throw new ServiceException("Error generando PDF factura " + factura.getNumero(), e);
        }

        log.info("PDF factura generado: {}", fichero.getAbsolutePath());
        return fichero;
    }

    // -------------------------------------------------------------------------
    // Secciones comunes
    // -------------------------------------------------------------------------

    private void agregarCabecera(Document doc, Usuario usuario) throws DocumentException {
        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{1, 2});
        tabla.setSpacingAfter(5);

        // Celda izquierda — reservada para logo
        PdfPCell celdaLogo = new PdfPCell();
        celdaLogo.setBorder(Rectangle.NO_BORDER);
        tabla.addCell(celdaLogo);

        // Celda derecha — datos fiscales del autónomo
        StringBuilder sb = new StringBuilder();
        sb.append(usuario.getNombreComercial() != null
                ? usuario.getNombreComercial() : usuario.getNombre());
        if (usuario.getNif() != null)      sb.append("\nNIF: ").append(usuario.getNif());
        if (usuario.getDireccion() != null) sb.append("\n").append(usuario.getDireccion());
        if (usuario.getTelefono() != null)  sb.append("\nTel: ").append(usuario.getTelefono());
        if (usuario.getEmail() != null)     sb.append("\n").append(usuario.getEmail());

        PdfPCell celdaDatos = new PdfPCell(new Phrase(sb.toString(), FONT_NORMAL));
        celdaDatos.setBorder(Rectangle.NO_BORDER);
        celdaDatos.setHorizontalAlignment(Element.ALIGN_RIGHT);
        celdaDatos.setPaddingBottom(8);
        tabla.addCell(celdaDatos);

        doc.add(tabla);

        LineSeparator linea = new LineSeparator();
        linea.setLineColor(COLOR_PRIMARIO);
        linea.setLineWidth(2f);
        doc.add(new Chunk(linea));
        doc.add(Chunk.NEWLINE);
    }

    private void agregarTitulo(Document doc, String tipo, String numero,
                               LocalDateTime fecha) throws DocumentException {
        Paragraph titulo = new Paragraph(tipo, FONT_TITULO);
        titulo.setAlignment(Element.ALIGN_LEFT);
        titulo.setSpacingBefore(10);
        doc.add(titulo);

        String fechaStr = fecha != null ? fecha.format(FECHA_FMT) : "";
        Paragraph num = new Paragraph(
                "Nº " + numero + "   |   Fecha: " + fechaStr, FONT_NORMAL);
        num.setSpacingAfter(12);
        doc.add(num);
    }

    private void agregarBloquecliente(Document doc, String clienteNombre,
                                      String descripcion) throws DocumentException {
        PdfPTable tabla = new PdfPTable(1);
        tabla.setWidthPercentage(50);
        tabla.setHorizontalAlignment(Element.ALIGN_LEFT);
        tabla.setSpacingAfter(descripcion != null && !descripcion.isBlank() ? 6 : 10);

        PdfPCell cabecera = new PdfPCell(new Phrase("DATOS DEL CLIENTE", FONT_CABECERA));
        cabecera.setBackgroundColor(COLOR_PRIMARIO);
        cabecera.setPadding(6);
        tabla.addCell(cabecera);

        PdfPCell datos = new PdfPCell(
                new Phrase(clienteNombre != null ? clienteNombre : "—", FONT_NORMAL));
        datos.setPadding(6);
        tabla.addCell(datos);

        doc.add(tabla);

        if (descripcion != null && !descripcion.isBlank()) {
            Paragraph p = new Paragraph("Descripción: " + descripcion, FONT_NORMAL);
            p.setSpacingAfter(10);
            doc.add(p);
        }
    }

    private void agregarTablaConceptos(Document doc, List<LineaDetalle> lineas)
            throws DocumentException {
        PdfPTable tabla = new PdfPTable(5);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{4f, 1.2f, 1f, 1.5f, 1.5f});
        tabla.setSpacingBefore(8);
        tabla.setSpacingAfter(8);

        for (String h : new String[]{"Concepto", "Tipo", "Cant.", "Precio unit.", "Importe"}) {
            PdfPCell cell = new PdfPCell(new Phrase(h, FONT_CABECERA));
            cell.setBackgroundColor(COLOR_PRIMARIO);
            cell.setPadding(7);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBorderColor(COLOR_PRIMARIO);
            tabla.addCell(cell);
        }

        boolean alterno = false;
        for (LineaDetalle l : lineas) {
            Color fondo = alterno ? COLOR_ALTERNO : Color.WHITE;
            addCelda(tabla, l.getConcepto(), FONT_NORMAL, fondo, Element.ALIGN_LEFT);
            addCelda(tabla, traducirTipo(l.getTipo()), FONT_SMALL, fondo, Element.ALIGN_CENTER);
            addCelda(tabla, formatDecimal(l.getCantidad()), FONT_NORMAL, fondo, Element.ALIGN_CENTER);
            addCelda(tabla, formatDinero(l.getPrecioUnitario()), FONT_NORMAL, fondo, Element.ALIGN_RIGHT);
            addCelda(tabla, formatDinero(l.getImporte()), FONT_NORMAL, fondo, Element.ALIGN_RIGHT);
            alterno = !alterno;
        }

        doc.add(tabla);
    }

    // -------------------------------------------------------------------------
    // Bloques de totales (diferente para presupuesto y factura)
    // -------------------------------------------------------------------------

    private void agregarTotalesPresupuesto(Document doc, Presupuesto p) throws DocumentException {
        PdfPTable tabla = tablaTotales();
        addFilaTotal(tabla, "Subtotal:", formatDinero(p.getSubtotal()), FONT_NORMAL, false);
        addFilaTotal(tabla, labelIva(p.getIvaPorcentaje()), formatDinero(p.getIvaImporte()), FONT_NORMAL, false);
        addFilaTotal(tabla, "TOTAL:", formatDinero(p.getTotal()), FONT_TOTAL_BOLD, true);
        doc.add(tabla);
    }

    private void agregarTotalesFactura(Document doc, Factura f) throws DocumentException {
        PdfPTable tabla = tablaTotales();
        addFilaTotal(tabla, "Base imponible:", formatDinero(f.getSubtotal()), FONT_NORMAL, false);
        addFilaTotal(tabla, labelIva(f.getIvaPorcentaje()), formatDinero(f.getIvaImporte()), FONT_NORMAL, false);
        addFilaTotal(tabla, labelIrpf(f.getIrpfPorcentaje()), "- " + formatDinero(f.getIrpfImporte()), FONT_NORMAL, false);
        addFilaTotal(tabla, "TOTAL:", formatDinero(f.getTotal()), FONT_TOTAL_BOLD, true);
        doc.add(tabla);
    }

    private PdfPTable tablaTotales() throws DocumentException {
        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(40);
        tabla.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tabla.setSpacingBefore(4);
        tabla.setSpacingAfter(16);
        return tabla;
    }

    // -------------------------------------------------------------------------
    // Pies de página
    // -------------------------------------------------------------------------

    private void agregarPiePresupuesto(Document doc, BigDecimal ivaPorcentaje)
            throws DocumentException {
        doc.add(separador());
        Paragraph pie = new Paragraph(
                "Este presupuesto tiene una validez de 30 días desde su emisión.\n" +
                "Precios sin IVA incluido. IVA al " +
                ivaPorcentaje.stripTrailingZeros().toPlainString() + "%.",
                FONT_SMALL);
        pie.setSpacingBefore(6);
        doc.add(pie);
    }

    private void agregarPieFactura(Document doc, Factura factura, Usuario usuario)
            throws DocumentException {
        doc.add(separador());

        String nifEmisor = usuario.getNif() != null ? usuario.getNif() : "—";
        String irpfTexto = factura.getIrpfPorcentaje() != null
                ? "Retención IRPF del " +
                  factura.getIrpfPorcentaje().stripTrailingZeros().toPlainString() +
                  "% aplicada según art. 101 LIRPF."
                : "";

        Paragraph pie = new Paragraph(
                "Factura emitida por " +
                (usuario.getNombreComercial() != null ? usuario.getNombreComercial() : usuario.getNombre()) +
                ", NIF: " + nifEmisor + ".\n" +
                irpfTexto + "\n" +
                "Conserve este documento durante al menos 5 años (art. 30 Código de Comercio).",
                FONT_SMALL);
        pie.setSpacingBefore(6);
        doc.add(pie);
    }

    private Chunk separador() {
        LineSeparator linea = new LineSeparator();
        linea.setLineColor(COLOR_SEPARADOR);
        return new Chunk(linea);
    }

    // -------------------------------------------------------------------------
    // Helpers de tabla
    // -------------------------------------------------------------------------

    private void addCelda(PdfPTable tabla, String texto, Font font,
                          Color fondo, int alineacion) {
        PdfPCell cell = new PdfPCell(new Phrase(texto != null ? texto : "", font));
        cell.setBackgroundColor(fondo);
        cell.setPadding(6);
        cell.setHorizontalAlignment(alineacion);
        cell.setBorderColor(new Color(220, 220, 220));
        tabla.addCell(cell);
    }

    private void addFilaTotal(PdfPTable tabla, String etiqueta, String valor,
                              Font font, boolean resaltar) {
        Color fondo = resaltar ? new Color(235, 245, 255) : null;

        PdfPCell cEtiqueta = new PdfPCell(new Phrase(etiqueta, font));
        cEtiqueta.setBorder(Rectangle.NO_BORDER);
        cEtiqueta.setPadding(4);
        cEtiqueta.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (fondo != null) cEtiqueta.setBackgroundColor(fondo);

        PdfPCell cValor = new PdfPCell(new Phrase(valor, font));
        cValor.setBorder(Rectangle.NO_BORDER);
        cValor.setPadding(4);
        cValor.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (fondo != null) cValor.setBackgroundColor(fondo);

        tabla.addCell(cEtiqueta);
        tabla.addCell(cValor);
    }

    // -------------------------------------------------------------------------
    // Helpers de formato
    // -------------------------------------------------------------------------

    private String labelIva(BigDecimal porcentaje) {
        String pct = porcentaje != null
                ? porcentaje.stripTrailingZeros().toPlainString() : "21";
        return "IVA (" + pct + "%):";
    }

    private String labelIrpf(BigDecimal porcentaje) {
        String pct = porcentaje != null
                ? porcentaje.stripTrailingZeros().toPlainString() : "15";
        return "Retención IRPF (" + pct + "%):";
    }

    private String traducirTipo(String tipo) {
        if (tipo == null) return "Servicio";
        return switch (tipo) {
            case "material" -> "Material";
            default         -> "Servicio";
        };
    }

    private String formatDinero(BigDecimal importe) {
        if (importe == null) return "0,00 €";
        return String.format("%,.2f €", importe)
                .replace(",", "X").replace(".", ",").replace("X", ".");
    }

    private String formatDecimal(BigDecimal valor) {
        if (valor == null) return "1";
        return valor.stripTrailingZeros().toPlainString().replace(".", ",");
    }

    private File obtenerDirectorio() {
        String ruta = ConfigUtil.get("pdf.output.dir");
        File dir = (ruta != null && !ruta.isBlank())
                ? new File(ruta)
                : new File(System.getProperty("java.io.tmpdir"), "tugestorai-pdfs");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new ServiceException(
                    "No se pudo crear el directorio de PDFs: " + dir.getAbsolutePath());
        }
        return dir;
    }
}