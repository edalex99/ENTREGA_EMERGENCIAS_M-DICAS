package edu.iudigital.emergencias;

import java.time.Duration;
import java.util.Locale;
import java.util.Random;

/** Entrada de consola, completamente reproducible con opciones --clave=valor. */
public final class Main {
    private Main() {}
    public static void main(String[] args) throws Exception {
        String scenario = option(args, "scenario", "demo");
        if (has(args, "--help")) { help(); return; }
        if (!scenario.equals("demo") && !scenario.equals("stress") && !scenario.equals("failure")
                && !scenario.equals("priority")) {
            throw new IllegalArgumentException("Escenario desconocido: " + scenario);
        }
        int count = Integer.parseInt(option(args, "calls", scenario.equals("stress") ? "60" : "14"));
        if (count < 1 || count > 10000) throw new IllegalArgumentException("calls: rango 1-10000");
        int amb = Integer.parseInt(option(args, "ambulances", scenario.equals("stress") ? "5" : "3"));
        int med = Integer.parseInt(option(args, "medics", Integer.toString(amb)));
        int kits = Integer.parseInt(option(args, "kits", Integer.toString(amb)));
        double failure = Double.parseDouble(option(args, "failure-rate",
                scenario.equals("failure") ? "0.50" : "0.0"));
        double speed = Double.parseDouble(option(args, "speed", scenario.equals("stress") ? "5" : "2"));
        int interval = Integer.parseInt(option(args, "interval-ms", "1000"));
        int timeoutSec = Integer.parseInt(option(args, "timeout-sec", "120"));
        long seed = Long.parseLong(option(args, "seed", "2026"));
        boolean verbose = !has(args, "--quiet");
        boolean monitor = !has(args, "--no-monitor");
        var config = new SimulationEngine.Config(3, 3, amb, med, kits, failure, speed,
                interval, verbose, monitor, 3);
        System.out.printf("SIMULADOR DE EMERGENCIAS | escenario=%s | llamadas=%d | amb=%d | fallo=%.0f%% | velocidad=%.1fx%n",
                scenario, count, amb, failure * 100, speed);
        System.out.println("Modelo académico con coordenadas y tiempos simulados. No es software de despacho clínico.");
        boolean success;
        DispatchCoordinator.Snapshot result;
        int hospitalMessages;
        double elapsedSeconds;
        try (SimulationEngine engine = new SimulationEngine(config)) {
            engine.start();
            Random random = new Random(seed);
            for (int i = 1; i <= count; i++) {
                Emergency.Severity severity;
                if (scenario.equals("priority")) severity = i % 3 == 0
                        ? Emergency.Severity.CRITICA : Emergency.Severity.LEVE;
                else {
                    int pick = random.nextInt(100);
                    severity = pick < 16 ? Emergency.Severity.CRITICA : pick < 41
                            ? Emergency.Severity.GRAVE : pick < 77
                            ? Emergency.Severity.MODERADA : Emergency.Severity.LEVE;
                }
                double lat = 6.19 + random.nextDouble() * 0.11;
                double lon = -75.62 + random.nextDouble() * 0.095;
                engine.submit(new Emergency("EM-%03d".formatted(i), severity,
                        new GeoPoint(lat, lon), "Llamada simulada #" + i));
                if (i < count) Thread.sleep(scenario.equals("stress") ? 8 : 75);
            }
            success = engine.awaitCompletion(Duration.ofSeconds(timeoutSec));
            // Una breve ventana permite vaciar la mensajería asíncrona del hospital.
            Thread.sleep(200);
            result = engine.snapshot();
            hospitalMessages = engine.hospitalMessages();
            elapsedSeconds = engine.elapsedNanos() / 1e9;
        }
        System.out.println();
        System.out.println("=================== RESULTADO FINAL ===================");
        System.out.printf("estado=%s | llamadas=%d | completadas=%d | fallidas=%d | reintentos=%d%n",
                success ? "FINALIZADO" : "TIEMPO_AGOTADO", count, result.completed(),
                result.failed(), result.retryCount());
        System.out.printf(Locale.US,
                "asignaciones=%d | espera_max_cola=%d | concurrencia_max=%d | promedio_asignacion=%.3fs%n",
                result.assignments(), result.maxPending(), result.maxConcurrent(), result.avgResponseSeconds());
        System.out.printf(Locale.US,
                "promedio_resolucion=%.3fs | tiempo_real=%.3fs | utilizacion_ambulancias_aprox=%.1f%% | mensajes_hospital=%d%n",
                result.avgResolutionSeconds(), elapsedSeconds,
                Math.min(100.0, 100.0 * result.ambulanceBusyNanos() / 1e9 / elapsedSeconds / amb), hospitalMessages);
        System.out.println("=======================================================");
        if (!success) System.exit(2);
    }
    private static String option(String[] args, String name, String fallback) {
        String prefix = "--" + name + "=";
        for (String arg : args) if (arg.startsWith(prefix)) return arg.substring(prefix.length());
        return fallback;
    }
    private static boolean has(String[] args, String value) {
        for (String a : args) if (a.equals(value)) return true;
        return false;
    }
    private static void help() {
        System.out.println("Uso: java ... edu.iudigital.emergencias.Main [opciones]");
        System.out.println("  --scenario=demo|stress|failure|priority");
        System.out.println("  --calls=N --ambulances=N --medics=N --kits=N");
        System.out.println("  --failure-rate=0.0..1.0 --speed=FACTOR --seed=N");
        System.out.println("  --interval-ms=N --timeout-sec=N --no-monitor --quiet");
    }
}
