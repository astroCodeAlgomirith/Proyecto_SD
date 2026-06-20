package com.escom.banco.server;

import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;
import com.escom.banco.server.http.Manejador;
import com.escom.banco.server.http.Respuesta;
import com.escom.banco.server.http.Solicitud;

/**
 * GET /api/accounts/{id} -> {"id": 125, "propietario": "...", "balance": 15750.25}
 * Requiere JWT. Ruta caliente (80% de la carga): se sirve INLINE en el reactor,
 * con el JSON armado a mano (sin gson) para minimizar CPU por peticion.
 */
public class AccountHandler implements Manejador {

    private final CuentaRepository repo = CuentaRepository.get();

    @Override
    public Respuesta manejar(Solicitud s) {
        if (!"GET".equalsIgnoreCase(s.metodo())) return Respuesta.sinCuerpo(405);
        if (HttpUtil.usuarioAutenticado(s) == null) return Respuesta.error(401, "No autorizado");

        String[] p = s.path().split("/");
        if (p.length < 4) return Respuesta.error(400, "Falta el id");

        int id;
        try { id = Integer.parseInt(p[3]); }
        catch (NumberFormatException e) { return Respuesta.error(400, "Id invalido"); }

        Cuenta c = repo.get(id);
        if (c == null) return Respuesta.error(404, "Cuenta no encontrada");

        String json = "{\"id\":" + c.id
                + ",\"propietario\":\"" + c.propietario()
                + "\",\"balance\":" + c.saldoDecimal().toPlainString() + "}";
        return Respuesta.json(200, json);
    }
}
