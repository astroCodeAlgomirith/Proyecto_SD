package com.escom.banco.server;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.json.Json;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/transactions/transfer
 * body: {"sourceAccountId":"123","targetAccountId":"456","amount":200.00}
 * Requiere JWT. Ojo: los ids llegan como STRING y amount como decimal.
 */
public class TransferHandler implements HttpHandler {

    private final CuentaRepository repo = CuentaRepository.get();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        if (HttpUtil.usuarioAutenticado(ex) == null) {
            HttpUtil.error(ex, 401, "No autorizado"); return;
        }

        JsonObject in;
        try { in = Json.parse(HttpUtil.leerBody(ex)); }
        catch (Exception e) { HttpUtil.error(ex, 400, "JSON invalido"); return; }

        if (!in.has("sourceAccountId") || !in.has("targetAccountId") || !in.has("amount")) {
            HttpUtil.error(ex, 400, "Faltan campos"); return;
        }

        int origen, destino;
        long centavos;
        try {
            origen  = Integer.parseInt(in.get("sourceAccountId").getAsString().trim());
            destino = Integer.parseInt(in.get("targetAccountId").getAsString().trim());
            centavos = new BigDecimal(in.get("amount").getAsString().trim())
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (Exception e) {
            HttpUtil.error(ex, 400, "Datos invalidos"); return;
        }

        CuentaRepository.Resultado r = repo.transferir(origen, destino, centavos);
        switch (r.estado) {
            case OK -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "ok");
                out.put("seq", r.seq);
                HttpUtil.enviarJson(ex, 200, Json.toJson(out));
            }
            case NO_ENCONTRADA -> HttpUtil.error(ex, 404, "Cuenta no encontrada");
            case MISMA_CUENTA -> HttpUtil.error(ex, 400, "Origen y destino iguales");
            case MONTO_INVALIDO -> HttpUtil.error(ex, 400, "Monto invalido");
            case SALDO_INSUFICIENTE -> HttpUtil.error(ex, 400, "Saldo insuficiente");
        }
    }
}
