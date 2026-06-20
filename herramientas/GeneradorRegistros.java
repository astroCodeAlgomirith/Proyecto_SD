import java.io.*;
import java.util.*;

public class GeneradorRegistros {

    public static void main(String[] args) {
        String archivo = "alumnos.csv";

        ArrayList<String> nombres = leerArchivo("nombres.txt");
        ArrayList<String> apellidos = leerArchivo("apellidos.txt");
        Random random = new Random(12345); // semilla fija

        try (FileWriter writer = new FileWriter(archivo)) {
            // Generar combinaciones
            for (String nombre : nombres) {
                for (String ap1 : apellidos) {
                    for (String ap2 : apellidos) {
                        double numero = random.nextDouble() * 10000000;
                        numero = Math.round(numero * 10) / 100.0;
                        String calificacion = String.valueOf(numero);
                        String completo = nombre + "," + ap1 + "," + ap2 + "," + calificacion +"\n";
                        writer.write(completo);
                    }
                }
            }
            System.out.println("Archivo alumnos.csv creado correctamente.");

        } catch (IOException e) {
            System.out.println("Error al escribir el archivo.");
            e.printStackTrace();
        }
    }

    // Método para leer archivo línea por línea
    public static ArrayList<String> leerArchivo(String nombreArchivo) {

        ArrayList<String> lista = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(nombreArchivo))) {

            String linea;

            while ((linea = br.readLine()) != null) {

                linea = linea.trim();

                if (!linea.isEmpty()) { // evitar líneas vacías
                    lista.add(linea);
                }
            }

        } catch (IOException e) {
            System.out.println("Error leyendo archivo: " + nombreArchivo);
            e.printStackTrace();
        }

        return lista;
    }
}
