package edu.iudigital.emergencias;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Observador periódico: solo copia un snapshot; nunca bloquea los despachadores por E/S. */
public final class ConsoleMonitor implements Runnable {
    private final DispatchCoordinator coordinator;
    private final long startedNanos;
    public ConsoleMonitor(DispatchCoordinator coordinator, long startedNanos) {
        this.coordinator = coordinator;
        this.startedNanos = startedNanos;
    }
    @Override public void run() {
        DispatchCoordinator.Snapshot s = coordinator.snapshot();
        ResourcePool.Counts r = s.resources();
        synchronized (System.out) {
            System.out.println();
            System.out.printf("================ MONITOR %s | t=%.1fs ================%n",
                    LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    (System.nanoTime() - startedNanos) / 1e9);
            System.out.printf(Locale.US, "CASOS  ingresados=%d  pendientes=%d  en_curso=%d  completados=%d  fallidos=%d%n",
                    s.registered(), s.pending(), s.active(), s.completed(), s.failed());
            System.out.printf("FLOTA  disponibles=%d  en_camino=%d  ocupadas=%d  fuera_servicio=%d%n",
                    r.ambulancesFree(), r.ambulancesEnRoute(), r.ambulancesBusy(), r.ambulancesOutOfService());
            System.out.printf("OTROS  medicos_libres=%d  equipos_libres=%d  reintentos=%d  max_simultaneas=%d%n",
                    r.medicsFree(), r.kitsFree(), s.retryCount(), s.maxConcurrent());
            System.out.printf(Locale.US, "TIEMPOS media_asignacion=%.2fs  media_resolucion=%.2fs%n",
                    s.avgResponseSeconds(), s.avgResolutionSeconds());
            System.out.println("COLA PRIORIZADA (máx. cinco): caso | gravedad | puntaje | espera(s) | km*");
            if (s.pendingDetails().isEmpty()) System.out.println("  (sin emergencias pendientes)");
            s.pendingDetails().stream().limit(5).forEach(p -> System.out.printf(Locale.US,
                    "  %-8s | %-8s | %7.1f | %8.2f | %5.2f%n",
                    p.id(), p.gravity(), p.score(), p.waitingSeconds(), p.km()));
            System.out.println("* km a ambulancia libre; si no existe, base más próxima (estimación).");
            System.out.println("===========================================================");
        }
    }
}
