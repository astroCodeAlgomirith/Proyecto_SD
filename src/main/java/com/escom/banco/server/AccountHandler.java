package com.escom.banco.server;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * GET /api/accounts/{id} -> {"id": 125, "propietario": "...", "balance": 15750.25}
 * Requiere JWT. Ojo con el JSON: id NUMERICO y campos propietario/balance.
 */
public class AccountHandler implements HttpHandler {

    private final CuentaRepository repo = CuentaRepository.get();

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        if (HttpUtil.usuarioAutenticado(ex) == null) {
            HttpUtil.error(ex, 401, "No autorizado"); return;
        }

        String[] p = ex.getRequestURI().getPath().split("/");
        if (p.length < 4) { HttpUtil.error(ex, 400, "Falta el id"); return; }

        int id;
        try { id = Integer.parseInt(p[3]); }
        catch (NumberFormatException e) { HttpUtil.error(ex, 400, "Id invalido"); return; }

        Cuenta c = repo.get(id);
        if (c == null) { HttpUtil.error(ex, 404, "Cuenta no encontrada"); return; }

        // JSON a mano en la ruta caliente (80% de la carga son lecturas): evita
        // la reflexion de gson. Los nombres del dataset son ASCII sin comillas.
        String json = "{\"id\":" + c.id
                + ",\"propietario\":\"" + c.propietario()
                + "\",\"balance\":" + c.saldoDecimal().toPlainString() + "}";
        HttpUtil.enviarJson(ex, 200, json);
    }
}
