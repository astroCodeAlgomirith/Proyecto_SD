package pruebas;

/** Harness minimo de pruebas en Java puro (sin JUnit ni dependencias). */
public final class MiniTest {

    private static int pasadas = 0;
    private static int fallidas = 0;

    private MiniTest() {}

    public static void check(boolean cond, String msg) {
        if (cond) {
            pasadas++;
        } else {
            fallidas++;
            System.out.println("  FALLO: " + msg);
        }
    }

    public static void eq(long esperado, long real, String msg) {
        check(esperado == real, msg + " (esperado " + esperado + ", real " + real + ")");
    }

    public static void eq(Object esperado, Object real, String msg) {
        boolean ok = (esperado == null) ? (real == null) : esperado.equals(real);
        check(ok, msg + " (esperado " + esperado + ", real " + real + ")");
    }

    public static int pasadas() { return pasadas; }
    public static int fallidas() { return fallidas; }
}
