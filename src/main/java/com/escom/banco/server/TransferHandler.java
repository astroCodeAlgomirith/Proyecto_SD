package com.escom.banco.server;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.json.Json;
import com.escom.banco.server.http.Manejador;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Solicitud;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * POST /api/transactions/transfer
 * body: {"sourceAccountId":"123","targetAccountId":"456","amount":200.00}
 * Requiere JWT. Ids como STRING y amount como decimal. Se sirve INLINE: el body
 * es chico (gson sobre ~60 bytes) y el lock de transferir es una seccion corta.
 */
public class TransferHandler implements Manejador {

    private final CuentaRepository repo = CuentaRepository.get();

    @Override
    public Respuesta manejar(Solicitud s) {
        if (!"POST".equalsIgnoreCase(s.metodo())) return Respuesta.sinCuerpo(405);
        if (HttpUtil.usuarioAutenticado(s) == null) return Respuesta.error(401, "No autorizado");

        JsonObject in;
        try { in = Json.parse(s.bodyTexto()); }
        catch (Exception e) { return Respuesta.error(400, "JSON invalido"); }

        if (!in.has("sourceAccountId") || !in.has("targetAccountId") || !in.has("amount")) {
            return Respuesta.error(400, "Faltan campos");
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
            return Respuesta.error(400, "Datos invalidos");
        }

        CuentaRepository.Resultado r = repo.transferir(origen, destino, centavos);
        switch (r.estado) {
            case OK:                return Respuesta.json(200, "{\"status\":\"ok\",\"seq\":" + r.seq + "}");
            case NO_ENCONTRADA:     return Respuesta.error(404, "Cuenta no encontrada");
            case MISMA_CUENTA:      return Respuesta.error(400, "Origen y destino iguales");
            case MONTO_INVALIDO:    return Respuesta.error(400, "Monto invalido");
            case SALDO_INSUFICIENTE: return Respuesta.error(400, "Saldo insuficiente");
            default:                return Respuesta.error(500, "Error interno");
        }
    }
}
