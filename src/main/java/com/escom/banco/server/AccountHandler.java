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

        // Extrae el id del ultimo segmento sin split('/') (evita String[] + substrings).
        String path = s.path();
        String pref = "/api/accounts/";
        if (!path.startsWith(pref)) return Respuesta.error(400, "Falta el id");
        int ini = pref.length();
        int fin = path.indexOf('/', ini);
        if (fin < 0) fin = path.length();
        if (ini >= fin) return Respuesta.error(400, "Falta el id");

        int id;
        try { id = Integer.parseInt(path, ini, fin, 10); }
        catch (NumberFormatException e) { return Respuesta.error(400, "Id invalido"); }

        Cuenta c = repo.get(id);
        if (c == null) return Respuesta.error(404, "Cuenta no encontrada");

        // JSON en un solo StringBuilder: propietario precomputado y saldo desde
        // long (sin BigDecimal), para minimizar allocations por GET.
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"id\":").append(c.id)
          .append(",\"propietario\":\"").append(c.propietario)
          .append("\",\"balance\":");
        c.appendSaldo(sb);
        sb.append('}');
        return Respuesta.json(200, sb.toString());
    }
}
