package edu.iudigital.emergencias;

/** Ponderaciones didácticas configuradas para la simulación, no equivalen a triaje clínico. */
public final class PriorityPolicy {
    private PriorityPolicy() {}
    public static double score(Emergency.Severity severity, double waitingSeconds, double distanceKm) {
        if (waitingSeconds < 0 || distanceKm < 0) throw new IllegalArgumentException("Valores negativos");
        return severity.points() + (15.0 * waitingSeconds) - (10.0 * distanceKm);
    }
    public static double score(Emergency emergency, double distanceKm, long nowNanos) {
        double seconds = Math.max(0, nowNanos - emergency.createdNanos()) / 1_000_000_000.0;
        return score(emergency.severity(), seconds, distanceKm);
    }
}
