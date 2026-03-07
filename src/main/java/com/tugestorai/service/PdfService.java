package com.tugestorai.service;

import org.openpdf.text.*;
import org.openpdf.text.pdf.*;
import org.openpdf.text.pdf.draw.LineSeparator;
import com.tugestorai.exception.ServiceException;
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
import java.time.format.DateTimeFormatter;

/**
 * Genera documentos PDF profesionales de presupuestos con OpenPDF.
 */
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    // Fuentes
    private static final Font FONT_TITULO     = new Font(Font.HELVETICA, 18, Font.BOLD,  new Color(44, 62, 80));
    private static final Font FONT_SUBTITULO  = new Font(Font.HELVETICA, 11, Font.BOLD,  new Color(44, 62, 80));
    private static final Font FONT_NORMAL     = new Font(Font.HELVETICA, 10, Font.NORMAL);
    private static final Font FONT_SMALL      = new Font(Font.HELVETICA,  8, Font.NORMAL, new Color(100, 100, 100));
    private static final Font FONT_CABECERA   = new Font(Font.HELVETICA, 10, Font.BOLD,  Color.WHITE);
    private static final Font FONT_TOTAL      = new Font(Font.HELVETICA, 11, Font.BOLD);
    private static final Font FONT_TOTAL_BOLD = new Font(Font.HELVETICA, 12, Font.BOLD,  new Color(41, 128, 185));

    // Colores
    private static final Color COLOR_PRIMARIO = new Color(41, 128, 185);
    private static final Color COLOR_ALTERNO  = new Color(245, 245, 245);

    private static final DateTimeFormatter FECHA_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * Genera un PDF de presupuesto profesional.
     *
     * @param presupuesto datos completos del presupuesto (con líneas de detalle)
     * @param usuario     datos fiscales del autónomo emisor
     * @return fichero PDF generado
     * @throws ServiceException si ocurre algún error al generar el PDF
     */
    public File generarPresupuesto(Presupuesto presupuesto, Usuario usuario) {
        File directorio = obtenerDirectorio();
        String nombre = "presupuesto_" + presupuesto.getNumero().replace("/", "-") + ".pdf";
        File fichero = new File(directorio, nombre);

        try (Document doc = new Document(PageSize.A4, 50, 50, 50, 50)) {
            PdfWriter.getInstance(doc, new FileOutputStream(fichero));
            doc.open();

            agregarCabecera(doc, usuario);
            agregarTituloPresupuesto(doc, presupuesto);
            agregarDatosCliente(doc, presupuesto);
            agregarDescripcion(doc, presupuesto);
            agregarTablaConceptos(doc, presupuesto);
            agregarTotales(doc, presupuesto);
            agregarPie(doc, presupuesto);

        } catch (DocumentException | IOException e) {
            throw new ServiceException("Error generando PDF presupuesto " + presupuesto.getNumero(), e);
        }

        log.info("PDF generado: {}", fichero.getAbsolutePath());
        return fichero;
    }

    // -------------------------------------------------------------------------
    // Secciones del documento
    // -------------------------------------------------------------------------

    private void agregarCabecera(Document doc, Usuario usuario) throws DocumentException {
        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{1, 2});
        tabla.setSpacingAfter(5);

        // Celda izquierda — reservada para logo futuro
        PdfPCell celdaLogo = new PdfPCell();
        celdaLogo.setBorder(Rectangle.NO_BORDER);
        tabla.addCell(celdaLogo);

        // Celda derecha — datos fiscales del autónomo
        StringBuilder datos = new StringBuilder();
        String nombre = usuario.getNombreComercial() != null
                ? usuario.getNombreComercial() : usuario.getNombre();
        datos.append(nombre);
        if (usuario.getNif() != null)       datos.append("\nNIF: ").append(usuario.getNif());
        if (usuario.getDireccion() != null)  datos.append("\n").append(usuario.getDireccion());
        if (usuario.getTelefono() != null)   datos.append("\nTel: ").append(usuario.getTelefono());
        if (usuario.getEmail() != null)      datos.append("\n").append(usuario.getEmail());

        PdfPCell celdaDatos = new PdfPCell(new Phrase(datos.toString(), FONT_NORMAL));
        celdaDatos.setBorder(Rectangle.NO_BORDER);
        celdaDatos.setHorizontalAlignment(Element.ALIGN_RIGHT);
        celdaDatos.setPaddingBottom(8);
        tabla.addCell(celdaDatos);

        doc.add(tabla);

        // Línea separadora azul
        LineSeparator linea = new LineSeparator();
        linea.setLineColor(COLOR_PRIMARIO);
        linea.setLineWidth(2f);
        doc.add(new Chunk(linea));
        doc.add(Chunk.NEWLINE);
    }

    private void agregarTituloPresupuesto(Document doc, Presupuesto presupuesto)
            throws DocumentException {
        Paragraph titulo = new Paragraph("PRESUPUESTO", FONT_TITULO);
        titulo.setAlignment(Element.ALIGN_LEFT);
        titulo.setSpacingBefore(10);
        doc.add(titulo);

        String fecha = presupuesto.getCreatedAt() != null
                ? presupuesto.getCreatedAt().format(FECHA_FMT) : "";
        Paragraph num = new Paragraph(
                "Nº " + presupuesto.getNumero() + "   |   Fecha: " + fecha, FONT_NORMAL);
        num.setSpacingAfter(12);
        doc.add(num);
    }

    private void agregarDatosCliente(Document doc, Presupuesto presupuesto)
            throws DocumentException {
        PdfPTable tabla = new PdfPTable(1);
        tabla.setWidthPercentage(50);
        tabla.setHorizontalAlignment(Element.ALIGN_LEFT);
        tabla.setSpacingAfter(10);

        PdfPCell cabecera = new PdfPCell(new Phrase("DATOS DEL CLIENTE", FONT_CABECERA));
        cabecera.setBackgroundColor(COLOR_PRIMARIO);
        cabecera.setPadding(6);
        tabla.addCell(cabecera);

        String nombreCliente = presupuesto.getClienteNombre() != null
                ? presupuesto.getClienteNombre() : "—";
        PdfPCell datos = new PdfPCell(new Phrase(nombreCliente, FONT_NORMAL));
        datos.setPadding(6);
        tabla.addCell(datos);

        doc.add(tabla);
    }

    private void agregarDescripcion(Document doc, Presupuesto presupuesto)
            throws DocumentException {
        if (presupuesto.getDescripcion() == null || presupuesto.getDescripcion().isBlank()) return;

        Paragraph p = new Paragraph("Descripción: " + presupuesto.getDescripcion(), FONT_NORMAL);
        p.setSpacingAfter(8);
        doc.add(p);
    }

    private void agregarTablaConceptos(Document doc, Presupuesto presupuesto)
            throws DocumentException {
        PdfPTable tabla = new PdfPTable(5);
        tabla.setWidthPercentage(100);
        tabla.setWidths(new float[]{4f, 1.2f, 1f, 1.5f, 1.5f});
        tabla.setSpacingBefore(8);
        tabla.setSpacingAfter(8);

        // Cabecera de columnas
        for (String h : new String[]{"Concepto", "Tipo", "Cant.", "Precio unit.", "Importe"}) {
            PdfPCell cell = new PdfPCell(new Phrase(h, FONT_CABECERA));
            cell.setBackgroundColor(COLOR_PRIMARIO);
            cell.setPadding(7);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setBorderColor(COLOR_PRIMARIO);
            tabla.addCell(cell);
        }

        // Filas de detalle
        boolean alterno = false;
        for (LineaDetalle linea : presupuesto.getLineas()) {
            Color fondo = alterno ? COLOR_ALTERNO : Color.WHITE;
            addCelda(tabla, linea.getConcepto(), FONT_NORMAL, fondo, Element.ALIGN_LEFT);
            addCelda(tabla, traducirTipo(linea.getTipo()), FONT_SMALL, fondo, Element.ALIGN_CENTER);
            addCelda(tabla, formatDecimal(linea.getCantidad()), FONT_NORMAL, fondo, Element.ALIGN_CENTER);
            addCelda(tabla, formatDinero(linea.getPrecioUnitario()), FONT_NORMAL, fondo, Element.ALIGN_RIGHT);
            addCelda(tabla, formatDinero(linea.getImporte()), FONT_NORMAL, fondo, Element.ALIGN_RIGHT);
            alterno = !alterno;
        }

        doc.add(tabla);
    }

    private void agregarTotales(Document doc, Presupuesto presupuesto)
            throws DocumentException {
        PdfPTable tabla = new PdfPTable(2);
        tabla.setWidthPercentage(38);
        tabla.setHorizontalAlignment(Element.ALIGN_RIGHT);
        tabla.setSpacingBefore(4);
        tabla.setSpacingAfter(16);

        addFilaTotal(tabla, "Subtotal:", formatDinero(presupuesto.getSubtotal()), FONT_NORMAL, false);
        String labelIva = "IVA (" + presupuesto.getIvaPorcentaje().stripTrailingZeros().toPlainString() + "%):";
        addFilaTotal(tabla, labelIva, formatDinero(presupuesto.getIvaImporte()), FONT_NORMAL, false);
        addFilaTotal(tabla, "TOTAL:", formatDinero(presupuesto.getTotal()), FONT_TOTAL_BOLD, true);

        doc.add(tabla);
    }

    private void agregarPie(Document doc, Presupuesto presupuesto)
            throws DocumentException {
        LineSeparator linea = new LineSeparator();
        linea.setLineColor(new Color(200, 200, 200));
        doc.add(new Chunk(linea));

        Paragraph pie = new Paragraph(
                "Este presupuesto tiene una validez de 30 días desde su emisión.\n" +
                "Precios sin IVA incluido. IVA al " +
                presupuesto.getIvaPorcentaje().stripTrailingZeros().toPlainString() + "%.",
                FONT_SMALL);
        pie.setSpacingBefore(6);
        doc.add(pie);
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
        PdfPCell cEtiqueta = new PdfPCell(new Phrase(etiqueta, font));
        cEtiqueta.setBorder(Rectangle.NO_BORDER);
        cEtiqueta.setPadding(4);
        cEtiqueta.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (resaltar) cEtiqueta.setBackgroundColor(new Color(235, 245, 255));

        PdfPCell cValor = new PdfPCell(new Phrase(valor, font));
        cValor.setBorder(Rectangle.NO_BORDER);
        cValor.setPadding(4);
        cValor.setHorizontalAlignment(Element.ALIGN_RIGHT);
        if (resaltar) cValor.setBackgroundColor(new Color(235, 245, 255));

        tabla.addCell(cEtiqueta);
        tabla.addCell(cValor);
    }

    // -------------------------------------------------------------------------
    // Helpers de formato
    // -------------------------------------------------------------------------

    private String traducirTipo(String tipo) {
        if (tipo == null) return "Servicio";
        return switch (tipo) {
            case "material" -> "Material";
            case "servicio" -> "Servicio";
            default         -> "Servicio";
        };
    }

    private String formatDinero(BigDecimal importe) {
        if (importe == null) return "0,00 €";
        return String.format("%,.2f €", importe).replace(",", "X").replace(".", ",").replace("X", ".");
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
            throw new ServiceException("No se pudo crear el directorio de PDFs: " + dir.getAbsolutePath());
        }
        return dir;
    }
}