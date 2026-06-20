package com.escom.banco.server;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.json.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /stats -> snapshot O(1) del nodo para el generador de carga y el panel.
 * Publico (sin JWT) porque es solo monitoreo de un solo nodo.
 * El saldo total se da en centavos (long exacto) para verificar el invariante
 * en UNA sola peticion en vez de recorrer las 820k cuentas por HTTP.
 */
public class StatsHandler implements HttpHandler {

    private final CuentaRepository repo = CuentaRepository.get();
    private final String rol;

    public StatsHandler() {
        String lider = System.getenv("BANCO_LIDER_HOST");
        this.rol = (lider == null || lider.isBlank()) ? "LIDER" : "REPLICA";
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        long totalCent = repo.saldoTotalCentavos();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rol", rol);
        out.put("cuentas", repo.tamano());
        out.put("saldoTotalCentavos", totalCent);
        out.put("saldoTotal", java.math.BigDecimal.valueOf(totalCent, 2));
        out.put("transferencias", repo.totalTransferencias());
        out.put("ultimaTxId", repo.ultimaTxId());
        out.put("secuencia", repo.secuenciaActual());
        HttpUtil.enviarJson(ex, 200, Json.toJson(out));
    }
}
