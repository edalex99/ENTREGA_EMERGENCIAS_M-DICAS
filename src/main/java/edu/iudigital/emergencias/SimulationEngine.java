package edu.iudigital.emergencias;

import java.time.Duration;
import java.util.SplittableRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fachada de la simulación: operadores (productor/consumidor), despachadores
 * (pool fijo), ambulancias (pool fijo), hospital (actor por mensajes) y monitor.
 */
public final class SimulationEngine implements AutoCloseable {
    public record Config(int operators, int dispatchers, int ambulances, int medics, int kits,
                         double failureRate, double speed, int monitorIntervalMs,
                         boolean verbose, boolean monitorEnabled, int maxAttempts) {
        public Config {
            if (operators < 1 || dispatchers < 1 || ambulances < 1 || medics < 1 || kits < 1)
                throw new IllegalArgumentException("Los trabajadores y recursos deben ser positivos");
            if (failureRate < 0 || failureRate > 1 || !Double.isFinite(failureRate))
                throw new IllegalArgumentException("failureRate debe estar entre 0 y 1");
            if (speed <= 0 || !Double.isFinite(speed))
                throw new IllegalArgumentException("speed debe ser positivo");
            if (monitorIntervalMs < 100 || maxAttempts < 1)
                throw new IllegalArgumentException("Configuración de monitor/reintentos inválida");
        }
    }
    private final Config config;
    private final BlockingQueue<Emergency> inbound = new ArrayBlockingQueue<>(250);
    private final DispatchCoordinator coordinator;
    private final Communications communications;
    private final ExecutorService operators;
    private final ExecutorService dispatchers;
    private final ExecutorService ambulances;
    private final ScheduledExecutorService schedules = Executors.newScheduledThreadPool(2);
    private final AtomicInteger submitted = new AtomicInteger();
    // Registros rechazados (p. ej., identificadores duplicados) no quedan sin contabilizar.
    private final AtomicInteger rejected = new AtomicInteger();
    private final AtomicInteger operatorsWorking = new AtomicInteger();
    private final AtomicInteger tripId = new AtomicInteger();
    private volatile boolean running;
    private long startedNanos;

    public SimulationEngine(Config config) {
        this.config = config;
        this.coordinator = new DispatchCoordinator(new ResourcePool(
                config.ambulances(), config.medics(), config.kits()), config.maxAttempts());
        this.communications = new Communications(config.verbose());
        this.operators = Executors.newFixedThreadPool(config.operators(),
                r -> new Thread(r, "operador"));
        this.dispatchers = Executors.newFixedThreadPool(config.dispatchers(),
                r -> new Thread(r, "despachador"));
        this.ambulances = Executors.newFixedThreadPool(config.ambulances(),
                r -> new Thread(r, "ambulancia-equipo"));
    }
    public void start() {
        if (running) throw new IllegalStateException("Motor iniciado previamente");
        running = true;
        startedNanos = System.nanoTime();
        communications.start();
        for (int i = 0; i < config.operators(); i++) operators.submit(() -> {
            try {
                while (running || !inbound.isEmpty()) {
                    Emergency e = inbound.poll(100, TimeUnit.MILLISECONDS);
                    if (e == null) continue;
                    operatorsWorking.incrementAndGet();
                    try {
                        coordinator.register(e);
                        communications.event("OPERADOR", "llamada %s gravedad=%s".formatted(e.id(), e.severity()));
                    } catch (IllegalArgumentException invalidCall) {
                        // Rechazar el incidente inválido sin terminar el hilo operador.
                        rejected.incrementAndGet();
                        communications.event("RECHAZADA", "llamada %s: %s".formatted(
                                e.id(), invalidCall.getMessage()));
                    } finally { operatorsWorking.decrementAndGet(); }
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        for (int i = 0; i < config.dispatchers(); i++) dispatchers.submit(() -> {
            try {
                while (running) {
                    DispatchCoordinator.Assignment a = coordinator.claimNext(150);
                    if (a == null) continue;
                    communications.event("DESPACHO", "%s -> %s / %s / %s (%.2f km)".formatted(
                            a.emergency().id(), a.resources().ambulance().id(),
                            a.resources().medic().id(), a.resources().kit().id(),
                            a.resources().distanceKm()));
                    ambulances.submit(() -> performTrip(a));
                }
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        if (config.monitorEnabled()) schedules.scheduleAtFixedRate(
                new ConsoleMonitor(coordinator, startedNanos), 0, config.monitorIntervalMs(), TimeUnit.MILLISECONDS);
    }
    public void submit(Emergency emergency) throws InterruptedException {
        if (!running) throw new IllegalStateException("Debe iniciar el motor antes de enviar llamadas");
        if (!inbound.offer(emergency, 2, TimeUnit.SECONDS))
            throw new IllegalStateException("Cola de llamadas llena: aplicar reintento externo");
        submitted.incrementAndGet();
    }
    private void performTrip(DispatchCoordinator.Assignment a) {
        try {
            coordinator.departed(a);
            communications.hospital(new Communications.HospitalMessage(
                    a.emergency().id(), "ambulancia en camino", a.resources().ambulance().id()));
            sleepSimulated(480 + a.resources().distanceKm() * 145);
            // Semilla aislada por trayecto: no hay Random compartido mutable sin protección.
            long seed = ((long) a.emergency().id().hashCode() << 32)
                    ^ a.emergency().attempts() ^ tripId.incrementAndGet();
            boolean faulty = new SplittableRandom(seed).nextDouble() < config.failureRate();
            if (faulty) {
                boolean requeue = coordinator.tripFailed(a);
                communications.event("FALLO", "%s: fallo de conexión / ambulancia %s; %s".formatted(
                        a.emergency().id(), a.resources().ambulance().id(),
                        requeue ? "reencolado" : "agotó reintentos"));
                communications.hospital(new Communications.HospitalMessage(
                        a.emergency().id(), "incidencia de traslado", a.resources().ambulance().id()));
                long repairMs = Math.max(90L, Math.round(500 / config.speed()));
                schedules.schedule(() -> {
                    coordinator.repair(a.resources().ambulance());
                    communications.event("RECUPERADO", a.resources().ambulance().id() + " vuelve a servicio");
                }, repairMs, TimeUnit.MILLISECONDS);
                return;
            }
            coordinator.onScene(a);
            communications.hospital(new Communications.HospitalMessage(
                    a.emergency().id(), "equipo llegó al lugar", a.resources().ambulance().id()));
            sleepSimulated(650 + (a.emergency().severity() == Emergency.Severity.CRITICA ? 350 : 100));
            coordinator.complete(a);
            communications.hospital(new Communications.HospitalMessage(
                    a.emergency().id(), "atención finalizada", a.resources().ambulance().id()));
            communications.event("ATENDIDA", a.emergency().id() + " completada; recursos liberados");
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            // Interrupción de apagado: se registra por la supervisión (no reencolar al cerrar).
        }
    }
    private void sleepSimulated(double baseMs) throws InterruptedException {
        Thread.sleep(Math.max(70L, Math.round(baseMs / config.speed())));
    }
    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        long limit = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < limit) {
            DispatchCoordinator.Snapshot s = coordinator.snapshot();
            int total = submitted.get();
            int invalid = rejected.get();
            // Los rechazos terminales no deben provocar un falso timeout.
            if (total == s.registered() + invalid && inbound.isEmpty()
                    && operatorsWorking.get() == 0
                    && s.completed() + s.failed() + invalid == total)
                return true;
            Thread.sleep(35);
        }
        return false;
    }
    public DispatchCoordinator.Snapshot snapshot() { return coordinator.snapshot(); }
    public int submitted() { return submitted.get(); }
    public int rejected() { return rejected.get(); }
    public int hospitalMessages() { return communications.deliveredMessages(); }
    public long elapsedNanos() { return System.nanoTime() - startedNanos; }
    @Override public void close() {
        running = false;
        coordinator.stop();
        operators.shutdown(); dispatchers.shutdown(); ambulances.shutdown();
        try {
            operators.awaitTermination(2, TimeUnit.SECONDS);
            dispatchers.awaitTermination(2, TimeUnit.SECONDS);
            ambulances.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        operators.shutdownNow(); dispatchers.shutdownNow(); ambulances.shutdownNow();
        // No interrumpir los mensajes pendientes al hospital hasta vaciar su cola.
        communications.close();
        schedules.shutdownNow();
    }
}
