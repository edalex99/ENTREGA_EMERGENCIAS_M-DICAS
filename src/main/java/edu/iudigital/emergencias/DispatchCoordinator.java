package edu.iudigital.emergencias;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Monitor de asignación: un solo lock protege cola, casos, reservas y contadores.
 * Nunca se realizan operaciones de E/S ni esperas de viaje sosteniendo el lock.
 * No se usa PriorityBlockingQueue con comparador temporal: la prioridad se recalcula
 * al despacho, preservando la semántica de envejecimiento.
 */
public final class DispatchCoordinator {
    public record Assignment(Emergency emergency, ResourcePool.Bundle resources, long assignedNanos) {}
    public record Pending(String id, String gravity, double score, double waitingSeconds, double km) {}
    public record Snapshot(int registered, int completed, int failed, int active, int pending,
                           int retryCount, int assignments, int maxConcurrent, int maxPending,
                           double avgResponseSeconds, double avgResolutionSeconds,
                           ResourcePool.Counts resources, List<Pending> pendingDetails,
                           long ambulanceBusyNanos) {}

    private final Object lock = new Object();
    private final ResourcePool pool;
    private final int maxAttempts;
    private final List<Emergency> waiting = new ArrayList<>();
    private final Map<String, Emergency> cases = new LinkedHashMap<>();
    private boolean shutdown;
    private int active;
    private int completed;
    private int failed;
    private int retryCount;
    private int assignments;
    private int maxConcurrent;
    private int maxPending;
    private long firstResponseTotalNanos;
    private long resolutionTotalNanos;
    private long ambulanceBusyTotalNanos;

    public DispatchCoordinator(ResourcePool pool, int maxAttempts) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts debe ser >=1");
        this.pool = pool;
        this.maxAttempts = maxAttempts;
    }
    public void register(Emergency e) {
        synchronized (lock) {
            if (shutdown) throw new IllegalStateException("Coordinador detenido");
            if (cases.containsKey(e.id())) throw new IllegalArgumentException("ID duplicado: " + e.id());
            e.status(Emergency.Status.EN_ESPERA);
            cases.put(e.id(), e);
            waiting.add(e);
            maxPending = Math.max(maxPending, waiting.size());
            lock.notifyAll();
        }
    }
    public Assignment claimNext(long maxWaitMillis) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(maxWaitMillis).toNanos();
        synchronized (lock) {
            while (!shutdown) {
                long now = System.nanoTime();
                Emergency chosen = null;
                ResourcePool.Bundle chosenBundle = null;
                double best = -Double.MAX_VALUE;
                for (Emergency e : waiting) {
                    ResourcePool.Bundle b = pool.findBundle(e.location());
                    if (b == null) continue;
                    double score = PriorityPolicy.score(e, b.distanceKm(), now);
                    if (score > best || (score == best && chosen != null
                            && e.createdNanos() < chosen.createdNanos())) {
                        chosen = e; chosenBundle = b; best = score;
                    }
                }
                if (chosen != null) {
                    // Reserva all-or-nothing efectuada dentro de la sección crítica.
                    pool.reserve(chosenBundle, chosen.id());
                    waiting.remove(chosen);
                    chosen.status(Emergency.Status.ASIGNADA);
                    chosen.nextAttempt();
                    chosen.firstAssignedAt(now);
                    active++; assignments++;
                    maxConcurrent = Math.max(maxConcurrent, active);
                    return new Assignment(chosen, chosenBundle, now);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                lock.wait(Math.max(1L, Math.min(120L, Duration.ofNanos(remaining).toMillis())));
            }
            return null;
        }
    }
    public void departed(Assignment a) {
        synchronized (lock) {
            if (a.emergency.status() == Emergency.Status.ASIGNADA)
                a.emergency.status(Emergency.Status.EN_CAMINO);
        }
    }
    public void onScene(Assignment a) {
        synchronized (lock) {
            if (a.emergency.status() != Emergency.Status.EN_CAMINO)
                throw new IllegalStateException("Transición de estado no válida");
            pool.onScene(a.resources());
            a.emergency.status(Emergency.Status.EN_ATENCION);
        }
    }
    public void complete(Assignment a) {
        synchronized (lock) {
            if (a.emergency.status() != Emergency.Status.EN_ATENCION)
                throw new IllegalStateException("Solo se finalizan casos en atención");
            long now = System.nanoTime();
            a.emergency.status(Emergency.Status.COMPLETADA);
            a.emergency.finishedAt(now);
            pool.release(a.resources());
            active--; completed++;
            firstResponseTotalNanos += a.emergency.firstAssignmentNanos() - a.emergency.createdNanos();
            resolutionTotalNanos += now - a.emergency.createdNanos();
            ambulanceBusyTotalNanos += now - a.assignedNanos();
            lock.notifyAll();
        }
    }
    /** Marca una ambulancia como averiada; libera médico y equipo, y reencola o cierra. */
    public boolean tripFailed(Assignment a) {
        synchronized (lock) {
            Emergency.Status status = a.emergency.status();
            if (status != Emergency.Status.EN_CAMINO && status != Emergency.Status.ASIGNADA)
                throw new IllegalStateException("No se puede fallar desde " + status);
            pool.outOfService(a.resources());
            ambulanceBusyTotalNanos += System.nanoTime() - a.assignedNanos();
            active--;
            if (a.emergency.attempts() < maxAttempts) {
                a.emergency.status(Emergency.Status.EN_ESPERA);
                waiting.add(a.emergency);
                retryCount++;
                maxPending = Math.max(maxPending, waiting.size());
                lock.notifyAll();
                return true;
            }
            long now = System.nanoTime();
            a.emergency.status(Emergency.Status.FALLIDA);
            a.emergency.finishedAt(now);
            failed++;
            firstResponseTotalNanos += a.emergency.firstAssignmentNanos() - a.emergency.createdNanos();
            resolutionTotalNanos += now - a.emergency.createdNanos();
            lock.notifyAll();
            return false;
        }
    }
    public void repair(ResourcePool.Ambulance a) {
        synchronized (lock) { pool.repair(a); lock.notifyAll(); }
    }
    public Snapshot snapshot() {
        synchronized (lock) {
            long now = System.nanoTime();
            List<Pending> details = new ArrayList<>();
            for (Emergency e : waiting) {
                ResourcePool.Bundle available = pool.findBundle(e.location());
                double km = available == null ? pool.nearestBaseDistance(e.location())
                        : available.distanceKm();
                details.add(new Pending(e.id(), e.severity().name(),
                        PriorityPolicy.score(e, km, now),
                        Math.max(0.0, (now - e.createdNanos()) / 1e9), km));
            }
            details.sort(Comparator.comparingDouble(Pending::score).reversed());
            int terminal = completed + failed;
            return new Snapshot(cases.size(), completed, failed, active, waiting.size(),
                    retryCount, assignments, maxConcurrent, maxPending,
                    terminal == 0 ? 0 : firstResponseTotalNanos / 1e9 / terminal,
                    terminal == 0 ? 0 : resolutionTotalNanos / 1e9 / terminal,
                    pool.counts(), List.copyOf(details), ambulanceBusyTotalNanos);
        }
    }
    public void stop() { synchronized (lock) { shutdown = true; lock.notifyAll(); } }
    public Emergency.Status statusOf(String id) {
        synchronized (lock) {
            Emergency e = cases.get(id);
            return e == null ? null : e.status();
        }
    }
}
