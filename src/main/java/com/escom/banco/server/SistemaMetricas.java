package com.escom.banco.server;

import com.sun.management.OperatingSystemMXBean;

import java.io.File;
import java.lang.management.ManagementFactory;

/** Metricas de sistema del nodo (CPU/RAM/Disco) con solo APIs del JDK. */
public final class SistemaMetricas {

    private static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private static final File RAIZ = new File("/");

    private SistemaMetricas() {}

    /** Uso de CPU del sistema en %, 0..100 (puede dar 0 en la primera lectura). */
    public static double cpuPorcentaje() {
        double carga = OS.getCpuLoad();
        return carga < 0 ? 0.0 : redondear(carga * 100.0);
    }

    /** RAM usada en %, 0..100. */
    public static double ramPorcentaje() {
        long total = OS.getTotalMemorySize();
        if (total <= 0) return 0.0;
        long usada = total - OS.getFreeMemorySize();
        return redondear(100.0 * usada / total);
    }

    /** Disco usado en % del sistema de archivos raiz, 0..100. */
    public static double discoPorcentaje() {
        long total = RAIZ.getTotalSpace();
        if (total <= 0) return 0.0;
        long usado = total - RAIZ.getUsableSpace();
        return redondear(100.0 * usado / total);
    }

    private static double redondear(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
