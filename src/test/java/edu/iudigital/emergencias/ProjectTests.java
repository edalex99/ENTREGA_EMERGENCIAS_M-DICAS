package edu.iudigital.emergencias;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Pruebas sin dependencias externas: java -ea ...ProjectTests. */
public final class ProjectTests {
    private static int passed;
    private ProjectTests() {}

    public static void main(String[] args) throws Exception {
        test("Distancia geográfica Haversine", ProjectTests::geography);
        test("Prioridad por gravedad", ProjectTests::severity);
        test("Desempate por proximidad", ProjectTests::proximity);
        test("Envejecimiento evita inanición", ProjectTests::aging);
        test("Reserva atómica bajo 12 despachadores", ProjectTests::mutualExclusion);
        test("Flujo completo con 50 llamadas concurrentes", ProjectTests::stress);
        test("Reencolado, límite de intentos y reparación", ProjectTests::failures);
        test("Rechazo de IDs duplicados y parámetros inválidos", ProjectTests::validations);
        System.out.println("RESULTADO PRUEBAS: " + passed + "/8 CORRECTAS");
    }
    private static void test(String name, ThrowingRunnable runnable) throws Exception {
        long start = System.nanoTime();
        try {
            runnable.run(); passed++;
            System.out.printf("[OK] %s (%.2fs)%n", name, (System.nanoTime() - start) / 1e9);
        } catch (Throwable t) {
            System.out.println("[ERROR] " + name + ": " + t);
            throw t;
        }
    }
    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }
    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }
    private static Emergency incident(String id, Emergency.Severity severity, GeoPoint point) {
        return new Emergency(id, severity, point, "prueba");
    }
    private static void geography() {
        GeoPoint a = new GeoPoint(6.2518, -75.5636);
        check(a.kmTo(a) < 0.00000001, "Distancia idéntica distinta de cero");
        check(a.kmTo(new GeoPoint(6.2145, -75.5780)) > 3.5, "Distancia irreal");
    }
    private static void severity() throws Exception {
        var c = new DispatchCoordinator(new ResourcePool(1, 1, 1), 3);
        c.register(incident("L-1", Emergency.Severity.LEVE, new GeoPoint(6.2518, -75.5636)));
        c.register(incident("C-1", Emergency.Severity.CRITICA, new GeoPoint(6.2600, -75.5700)));
        var a = c.claimNext(50);
        check(a != null && a.emergency().id().equals("C-1"), "No se seleccionó caso crítico");
        c.departed(a); c.onScene(a); c.complete(a);
        check(c.snapshot().resources().ambulancesFree() == 1, "No se liberó la ambulancia");
    }
    private static void proximity() throws Exception {
        var c = new DispatchCoordinator(new ResourcePool(1, 1, 1), 2);
        c.register(incident("LEJOS", Emergency.Severity.GRAVE, new GeoPoint(6.195, -75.625)));
        c.register(incident("CERCA", Emergency.Severity.GRAVE, new GeoPoint(6.2518, -75.5636)));
        var a = c.claimNext(50);
        check(a != null && a.emergency().id().equals("CERCA"), "No se prefirió la proximidad");
    }
    private static void aging() {
        double lowWait = PriorityPolicy.score(Emergency.Severity.LEVE, 0, 1);
        double aged = PriorityPolicy.score(Emergency.Severity.LEVE, 70, 1);
        double critical = PriorityPolicy.score(Emergency.Severity.CRITICA, 0, 1);
        check(aged > critical && lowWait < critical, "La espera no cambia la prioridad");
    }
    private static void mutualExclusion() throws Exception {
        var c = new DispatchCoordinator(new ResourcePool(1, 1, 1), 3);
        for (int i = 0; i < 12; i++) c.register(incident("T-" + i,
                Emergency.Severity.MODERADA, new GeoPoint(6.25, -75.56)));
        var workers = Executors.newFixedThreadPool(12);
        try {
            List<Callable<DispatchCoordinator.Assignment>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) tasks.add(() -> c.claimNext(100));
            List<Future<DispatchCoordinator.Assignment>> future = workers.invokeAll(tasks);
            int allocations = 0;
            DispatchCoordinator.Assignment reserved = null;
            for (Future<DispatchCoordinator.Assignment> f : future) {
                if (f.get() != null) { allocations++; reserved = f.get(); }
            }
            check(allocations == 1, "Un recurso fue adjudicado en paralelo a varios casos");
            check(c.snapshot().active() == 1, "Número de casos activos incorrecto");
            c.departed(reserved); c.onScene(reserved); c.complete(reserved);
            check(c.snapshot().resources().medicsFree() == 1, "El médico no retornó");
        } finally { workers.shutdownNow(); }
    }
    private static void stress() throws Exception {
        var cfg = new SimulationEngine.Config(4, 5, 3, 3, 3, 0, 10,
                200, false, false, 3);
        try (var engine = new SimulationEngine(cfg)) {
            engine.start();
            for (int i = 0; i < 50; i++) {
                engine.submit(incident("ST-" + i, Emergency.Severity.values()[i % 4],
                        new GeoPoint(6.20 + (i % 9) * 0.009, -75.61 + (i % 7) * 0.01)));
            }
            check(engine.awaitCompletion(Duration.ofSeconds(25)), "Bloqueo/deadlock en prueba de carga");
            var s = engine.snapshot();
            check(s.registered() == 50 && s.completed() == 50, "Pérdida de incidentes");
            check(s.active() == 0 && s.pending() == 0, "Quedaron procesos pendientes");
            check(s.maxConcurrent() <= 3 && s.maxConcurrent() > 1, "Concurrencia incompatible con recursos");
            check(s.resources().ambulancesFree() == 3 && s.resources().medicsFree() == 3
                    && s.resources().kitsFree() == 3, "Fuga de recursos");
        }
    }
    private static void failures() throws Exception {
        var cfg = new SimulationEngine.Config(2, 3, 1, 1, 1, 1.0, 10,
                200, false, false, 2);
        try (var engine = new SimulationEngine(cfg)) {
            engine.start();
            for (int i = 0; i < 3; i++) engine.submit(incident("FL-" + i,
                    Emergency.Severity.CRITICA, new GeoPoint(6.25, -75.56)));
            check(engine.awaitCompletion(Duration.ofSeconds(15)), "Bloqueo tras fallo");
            Thread.sleep(220); // verificar retorno final de ambulancia fuera de servicio
            var s = engine.snapshot();
            check(s.failed() == 3 && s.completed() == 0, "Límite de reintentos ignorado");
            check(s.retryCount() == 3 && s.assignments() == 6, "Reencolado incorrecto");
            check(s.resources().ambulancesFree() == 1 && s.resources().medicsFree() == 1,
                    "Recursos no reparados/liberados");
        }
    }
    private static void validations() {
        var c = new DispatchCoordinator(new ResourcePool(1, 1, 1), 2);
        var e = incident("DUP", Emergency.Severity.LEVE, new GeoPoint(6.25, -75.56));
        c.register(e);
        boolean duplicated = false;
        try { c.register(e); } catch (IllegalArgumentException x) { duplicated = true; }
        check(duplicated, "Se aceptó un ID duplicado");
        boolean invalid = false;
        try { new GeoPoint(98, -75); } catch (IllegalArgumentException x) { invalid = true; }
        check(invalid, "Se aceptó coordenada inválida");
    }
}
