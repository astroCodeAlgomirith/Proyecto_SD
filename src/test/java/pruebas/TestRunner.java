package pruebas;

/** Corre todas las suites y termina con codigo != 0 si algo falla. */
public final class TestRunner {

    private TestRunner() {}

    public static void main(String[] args) throws Exception {
        System.out.println("== CuentaRepositoryTest ==");
        CuentaRepositoryTest.run();
        System.out.println("== AlumnosLoaderTest ==");
        AlumnosLoaderTest.run();
        System.out.println("== ReplicacionTest ==");
        ReplicacionTest.run();
        System.out.println("== ReplicacionTcpTest ==");
        ReplicacionTcpTest.run();
        System.out.printf("%nResultado: %d pasadas, %d fallidas%n",
                MiniTest.pasadas(), MiniTest.fallidas());
        if (MiniTest.fallidas() > 0) System.exit(1);
    }
}
