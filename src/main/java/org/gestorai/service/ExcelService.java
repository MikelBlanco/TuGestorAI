package org.gestorai.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.gestorai.exception.ServiceException;
import org.gestorai.model.LineaDetalle;
import org.gestorai.model.Presupuesto;
import org.gestorai.model.Autonomo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * Generación de presupuestos en formato Excel (.xlsx) usando Apache POI.
 * El fichero se genera en memoria y se devuelve como {@code byte[]}.
 */
public class ExcelService {

    private static final Logger log = LoggerFactory.getLogger(ExcelService.class);

    private static final DateTimeFormatter FMT_FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Paleta de colores
    private static final byte[] COLOR_AZUL_CABECERA = hexToRgb("1F4E79");   // azul oscuro
    private static final byte[] COLOR_GRIS_SECCION   = hexToRgb("D9E1F2");   // azul grisáceo suave
    private static final byte[] COLOR_FILA_PAR       = hexToRgb("F2F7FC");   // azul muy claro
    private static final byte[] COLOR_TOTAL_BG       = hexToRgb("E8F0FE");   // fondo fila totales

    /**
     * Genera un fichero .xlsx con los datos del presupuesto.
     *
     * @param presupuesto presupuesto guardado (con número, líneas y totales)
     * @param usuario     autónomo propietario (datos de cabecera)
     * @return bytes del fichero xlsx listo para enviar o adjuntar
     * @throws ServiceException si hay error al serializar el workbook
     */
    public byte[] generarPresupuesto(Presupuesto presupuesto, Autonomo usuario) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet sheet = wb.createSheet("Presupuesto");

            // Estilos reutilizables
            Estilos estilos = new Estilos(wb);

            int fila = 0;

            // ── Título ──────────────────────────────────────────────────────
            fila = escribirTitulo(sheet, estilos, presupuesto, fila);
            fila++; // fila en blanco

            // ── Datos del autónomo ───────────────────────────────────────────
            fila = escribirSeccion(sheet, estilos, "Datos del emisor", fila);
            fila = escribirCampo(sheet, estilos, "Nombre / razón social",
                    nvl(usuario.getNombreComercial(), usuario.getNombre()), fila);
            fila = escribirCampo(sheet, estilos, "NIF / CIF", nvl(usuario.getNif()), fila);
            fila = escribirCampo(sheet, estilos, "Dirección", nvl(usuario.getDireccion()), fila);
            fila = escribirCampo(sheet, estilos, "Teléfono", nvl(usuario.getTelefono()), fila);
            fila = escribirCampo(sheet, estilos, "Email", nvl(usuario.getEmail()), fila);
            fila++; // fila en blanco

            // ── Datos del cliente ────────────────────────────────────────────
            fila = escribirSeccion(sheet, estilos, "Datos del cliente", fila);
            fila = escribirCampo(sheet, estilos, "Nombre", nvl(presupuesto.getClienteNombre()), fila);
            fila++; // fila en blanco

            // ── Número y fecha ───────────────────────────────────────────────
            fila = escribirSeccion(sheet, estilos, "Documento", fila);
            fila = escribirCampo(sheet, estilos, "Nº presupuesto", presupuesto.getNumero(), fila);
            String fechaStr = presupuesto.getCreatedAt() != null
                    ? presupuesto.getCreatedAt().format(FMT_FECHA) : "";
            fila = escribirCampo(sheet, estilos, "Fecha", fechaStr, fila);
            if (presupuesto.getNotas() != null && !presupuesto.getNotas().isBlank()) {
                fila = escribirCampo(sheet, estilos, "Descripción", presupuesto.getNotas(), fila);
            }
            fila++; // fila en blanco

            // ── Tabla de conceptos ───────────────────────────────────────────
            fila = escribirCabeceraTabla(sheet, estilos, fila);
            int primeraFilaDatos = fila;
            for (int i = 0; i < presupuesto.getLineas().size(); i++) {
                fila = escribirLineaDetalle(sheet, estilos, presupuesto.getLineas().get(i), fila, i % 2 == 0);
            }
            // Si no hay líneas, escribir fila vacía para no dejar la tabla sin cuerpo
            if (presupuesto.getLineas().isEmpty()) {
                fila = escribirLineaVacia(sheet, estilos, fila);
            }
            fila++; // fila en blanco

            // ── Totales ──────────────────────────────────────────────────────
            fila = escribirTotales(sheet, estilos, presupuesto, fila);

            // ── Anchos de columna ────────────────────────────────────────────
            sheet.setColumnWidth(0, 36 * 256);  // Concepto
            sheet.setColumnWidth(1, 14 * 256);  // Tipo
            sheet.setColumnWidth(2, 12 * 256);  // Cantidad
            sheet.setColumnWidth(3, 18 * 256);  // Precio unitario
            sheet.setColumnWidth(4, 18 * 256);  // Importe

            // ── Serializar a bytes ───────────────────────────────────────────
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);

            log.info("Excel presupuesto {} generado ({} bytes)",
                    presupuesto.getNumero(), baos.size());
            return baos.toByteArray();

        } catch (IOException e) {
            throw new ServiceException("Error generando Excel del presupuesto " + presupuesto.getNumero(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Escritura de secciones
    // -------------------------------------------------------------------------

    private int escribirTitulo(XSSFSheet sheet, Estilos e, Presupuesto p, int fila) {
        Row row = sheet.createRow(fila);
        row.setHeightInPoints(28);
        Cell cell = row.createCell(0);
        cell.setCellValue("PRESUPUESTO  " + p.getNumero());
        cell.setCellStyle(e.titulo);
        // Fusionar columnas A–E
        sheet.addMergedRegion(new CellRangeAddress(fila, fila, 0, 4));
        return fila + 1;
    }

    private int escribirSeccion(XSSFSheet sheet, Estilos e, String titulo, int fila) {
        Row row = sheet.createRow(fila);
        row.setHeightInPoints(18);
        Cell cell = row.createCell(0);
        cell.setCellValue(titulo);
        cell.setCellStyle(e.seccion);
        sheet.addMergedRegion(new CellRangeAddress(fila, fila, 0, 4));
        return fila + 1;
    }

    private int escribirCampo(XSSFSheet sheet, Estilos e, String etiqueta, String valor, int fila) {
        Row row = sheet.createRow(fila);
        Cell label = row.createCell(0);
        label.setCellValue(etiqueta);
        label.setCellStyle(e.campoEtiqueta);

        Cell val = row.createCell(1);
        val.setCellValue(valor);
        val.setCellStyle(e.campoValor);
        sheet.addMergedRegion(new CellRangeAddress(fila, fila, 1, 4));
        return fila + 1;
    }

    private int escribirCabeceraTabla(XSSFSheet sheet, Estilos e, int fila) {
        Row row = sheet.createRow(fila);
        row.setHeightInPoints(20);
        String[] cabeceras = {"Concepto", "Tipo", "Cantidad", "Precio unit. (€)", "Importe (€)"};
        for (int c = 0; c < cabeceras.length; c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(cabeceras[c]);
            cell.setCellStyle(e.tablaCabecera);
        }
        return fila + 1;
    }

    private int escribirLineaDetalle(XSSFSheet sheet, Estilos e, LineaDetalle linea,
                                     int fila, boolean filaPar) {
        Row row = sheet.createRow(fila);
        CellStyle estiloTexto  = filaPar ? e.tablaTextoPar  : e.tablaTextoImpar;
        CellStyle estiloNumero = filaPar ? e.tablaNumeroPar : e.tablaNumeroImpar;

        celda(row, 0, nvl(linea.getConcepto()), estiloTexto);
        celda(row, 1, etiquetaTipo(linea.getTipo()), estiloTexto);
        celdaDecimal(row, 2, linea.getCantidad(), estiloNumero);
        celdaDecimal(row, 3, linea.getPrecioUnitario(), estiloNumero);
        celdaDecimal(row, 4, linea.getImporte(), estiloNumero);
        return fila + 1;
    }

    private int escribirLineaVacia(XSSFSheet sheet, Estilos e, int fila) {
        Row row = sheet.createRow(fila);
        for (int c = 0; c < 5; c++) row.createCell(c).setCellStyle(e.tablaTextoPar);
        return fila + 1;
    }

    private int escribirTotales(XSSFSheet sheet, Estilos e, Presupuesto p, int fila) {
        fila = escribirFilaTotal(sheet, e, "Subtotal", p.getSubtotal(), fila, false);
        String ivaLabel = "IVA (" + p.getIvaPorcentaje().stripTrailingZeros().toPlainString() + "%)";
        fila = escribirFilaTotal(sheet, e, ivaLabel, p.getIvaImporte(), fila, false);
        fila = escribirFilaTotal(sheet, e, "TOTAL", p.getTotal(), fila, true);
        return fila;
    }

    private int escribirFilaTotal(XSSFSheet sheet, Estilos e, String etiqueta,
                                   BigDecimal valor, int fila, boolean esTotal) {
        Row row = sheet.createRow(fila);
        if (esTotal) row.setHeightInPoints(20);

        Cell label = row.createCell(3);
        label.setCellValue(etiqueta);
        label.setCellStyle(esTotal ? e.totalEtiqueta : e.subtotalEtiqueta);

        Cell val = row.createCell(4);
        val.setCellValue(valor != null ? valor.doubleValue() : 0.0);
        val.setCellStyle(esTotal ? e.totalValor : e.subtotalValor);
        return fila + 1;
    }

    // -------------------------------------------------------------------------
    // Helpers de celda
    // -------------------------------------------------------------------------

    private static void celda(Row row, int col, String valor, CellStyle estilo) {
        Cell c = row.createCell(col);
        c.setCellValue(valor != null ? valor : "");
        c.setCellStyle(estilo);
    }

    private static void celdaDecimal(Row row, int col, BigDecimal valor, CellStyle estilo) {
        Cell c = row.createCell(col);
        c.setCellValue(valor != null ? valor.doubleValue() : 0.0);
        c.setCellStyle(estilo);
    }

    private static String nvl(String... valores) {
        for (String v : valores) {
            if (v != null && !v.isBlank()) return v;
        }
        return "—";
    }

    private static String etiquetaTipo(String tipo) {
        if (tipo == null) return "";
        return switch (tipo) {
            case LineaDetalle.TIPO_MATERIAL -> "Material";
            case LineaDetalle.TIPO_SERVICIO -> "Servicio";
            default -> tipo;
        };
    }

    private static byte[] hexToRgb(String hex) {
        int r = Integer.parseInt(hex.substring(0, 2), 16);
        int g = Integer.parseInt(hex.substring(2, 4), 16);
        int b = Integer.parseInt(hex.substring(4, 6), 16);
        return new byte[]{(byte) r, (byte) g, (byte) b};
    }

    // -------------------------------------------------------------------------
    // Clase interna de estilos
    // -------------------------------------------------------------------------

    /**
     * Centraliza la creación de estilos para no recrearlos en cada celda
     * (POI tiene límite de estilos por workbook).
     */
    private static final class Estilos {

        final CellStyle titulo;
        final CellStyle seccion;
        final CellStyle campoEtiqueta;
        final CellStyle campoValor;
        final CellStyle tablaCabecera;
        final CellStyle tablaTextoPar;
        final CellStyle tablaTextoImpar;
        final CellStyle tablaNumeroPar;
        final CellStyle tablaNumeroImpar;
        final CellStyle subtotalEtiqueta;
        final CellStyle subtotalValor;
        final CellStyle totalEtiqueta;
        final CellStyle totalValor;

        Estilos(XSSFWorkbook wb) {
            DataFormat fmt = wb.createDataFormat();
            short fmtDecimal = fmt.getFormat("#,##0.00");

            // Fuentes
            XSSFFont fuenteTitulo = wb.createFont();
            fuenteTitulo.setBold(true);
            fuenteTitulo.setFontHeightInPoints((short) 16);
            fuenteTitulo.setColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));

            XSSFFont fuenteSeccion = wb.createFont();
            fuenteSeccion.setBold(true);
            fuenteSeccion.setFontHeightInPoints((short) 11);

            XSSFFont fuenteNegrita = wb.createFont();
            fuenteNegrita.setBold(true);

            XSSFFont fuenteTablaCab = wb.createFont();
            fuenteTablaCab.setBold(true);
            fuenteTablaCab.setColor(new XSSFColor(new byte[]{(byte)255, (byte)255, (byte)255}, null));

            XSSFFont fuenteTotal = wb.createFont();
            fuenteTotal.setBold(true);
            fuenteTotal.setFontHeightInPoints((short) 12);

            // ── título ───────────────────────────────────────────────────
            titulo = wb.createCellStyle();
            ((XSSFCellStyle) titulo).setFillForegroundColor(
                    new XSSFColor(COLOR_AZUL_CABECERA, null));
            titulo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            titulo.setFont(fuenteTitulo);
            titulo.setAlignment(HorizontalAlignment.CENTER);
            titulo.setVerticalAlignment(VerticalAlignment.CENTER);

            // ── sección ──────────────────────────────────────────────────
            seccion = wb.createCellStyle();
            ((XSSFCellStyle) seccion).setFillForegroundColor(
                    new XSSFColor(COLOR_GRIS_SECCION, null));
            seccion.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            seccion.setFont(fuenteSeccion);
            seccion.setAlignment(HorizontalAlignment.LEFT);
            setBorde(seccion, BorderStyle.THIN);

            // ── campo etiqueta ───────────────────────────────────────────
            campoEtiqueta = wb.createCellStyle();
            campoEtiqueta.setFont(fuenteNegrita);
            setBordeSuave(campoEtiqueta);

            // ── campo valor ──────────────────────────────────────────────
            campoValor = wb.createCellStyle();
            setBordeSuave(campoValor);

            // ── cabecera tabla ───────────────────────────────────────────
            tablaCabecera = wb.createCellStyle();
            ((XSSFCellStyle) tablaCabecera).setFillForegroundColor(
                    new XSSFColor(COLOR_AZUL_CABECERA, null));
            tablaCabecera.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            tablaCabecera.setFont(fuenteTablaCab);
            tablaCabecera.setAlignment(HorizontalAlignment.CENTER);
            tablaCabecera.setVerticalAlignment(VerticalAlignment.CENTER);
            setBorde(tablaCabecera, BorderStyle.THIN);

            // ── filas de datos (pares e impares) ─────────────────────────
            tablaTextoPar   = estiloTabla(wb, true,  false, fmtDecimal, false);
            tablaTextoImpar = estiloTabla(wb, false, false, fmtDecimal, false);
            tablaNumeroPar   = estiloTabla(wb, true,  true,  fmtDecimal, false);
            tablaNumeroImpar = estiloTabla(wb, false, true,  fmtDecimal, false);

            // ── subtotales ───────────────────────────────────────────────
            subtotalEtiqueta = wb.createCellStyle();
            subtotalEtiqueta.setFont(fuenteNegrita);
            subtotalEtiqueta.setAlignment(HorizontalAlignment.RIGHT);
            setBordeSuave(subtotalEtiqueta);

            subtotalValor = wb.createCellStyle();
            subtotalValor.setDataFormat(fmtDecimal);
            subtotalValor.setAlignment(HorizontalAlignment.RIGHT);
            setBordeSuave(subtotalValor);

            // ── total ────────────────────────────────────────────────────
            totalEtiqueta = wb.createCellStyle();
            ((XSSFCellStyle) totalEtiqueta).setFillForegroundColor(
                    new XSSFColor(COLOR_TOTAL_BG, null));
            totalEtiqueta.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalEtiqueta.setFont(fuenteTotal);
            totalEtiqueta.setAlignment(HorizontalAlignment.RIGHT);
            setBorde(totalEtiqueta, BorderStyle.MEDIUM);

            totalValor = wb.createCellStyle();
            ((XSSFCellStyle) totalValor).setFillForegroundColor(
                    new XSSFColor(COLOR_TOTAL_BG, null));
            totalValor.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            totalValor.setFont(fuenteTotal);
            totalValor.setDataFormat(fmtDecimal);
            totalValor.setAlignment(HorizontalAlignment.RIGHT);
            setBorde(totalValor, BorderStyle.MEDIUM);
        }

        private CellStyle estiloTabla(XSSFWorkbook wb, boolean par, boolean numero,
                                       short fmtDecimal, boolean unused) {
            XSSFCellStyle cs = wb.createCellStyle();
            if (par) {
                cs.setFillForegroundColor(new XSSFColor(COLOR_FILA_PAR, null));
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            if (numero) {
                cs.setDataFormat(fmtDecimal);
                cs.setAlignment(HorizontalAlignment.RIGHT);
            }
            setBorde(cs, BorderStyle.THIN);
            return cs;
        }

        private static void setBorde(CellStyle cs, BorderStyle bs) {
            cs.setBorderTop(bs);
            cs.setBorderBottom(bs);
            cs.setBorderLeft(bs);
            cs.setBorderRight(bs);
        }

        private static void setBordeSuave(CellStyle cs) {
            setBorde(cs, BorderStyle.HAIR);
        }
    }
}
