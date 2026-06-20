package pruebas;

import com.escom.banco.data.AlumnosLoader;
import com.escom.banco.data.CuentaRepository;
import com.escom.banco.model.Cuenta;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AlumnosLoaderTest {

    private AlumnosLoaderTest() {}

    public static void run() throws IOException {
        Path csv = Files.createTempFile("alumnos", ".csv");
        Files.writeString(csv,
                "ABRAHAM,AGUILAR,AGUILAR,361803.11\n"
              + "AKARI,AGUILERA,ALONSO,100.50\n"
              + "YAIR,ZAMORA,ZARAGOZA,0.00\n");
        try {
            CuentaRepository repo = new CuentaRepository();
            int n = AlumnosLoader.cargar(repo, csv);
            MiniTest.eq(3L, (long) n, "numero de filas cargadas");
            MiniTest.eq(3L, (long) repo.tamano(), "tamano del repositorio");

            Cuenta c1 = repo.get(1);
            MiniTest.check(c1 != null, "la fila 1 es id 1 (base 1)");
            MiniTest.eq("ABRAHAM AGUILAR AGUILAR", c1.propietario(), "propietario de id 1");
            MiniTest.eq(36180311L, c1.getSaldoCentavos(), "centavos exactos de id 1");
            MiniTest.eq(10050L, repo.get(2).getSaldoCentavos(), "centavos de id 2 (100.50)");
            MiniTest.check(repo.get(0) == null, "no existe id 0 (base 1)");
        } finally {
            Files.deleteIfExists(csv);
        }
    }
}
