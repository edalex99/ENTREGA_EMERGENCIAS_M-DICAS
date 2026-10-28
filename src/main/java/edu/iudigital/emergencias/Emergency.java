package edu.iudigital.emergencias;

import java.util.Objects;

/** Incidente: su ciclo de vida se modifica exclusivamente bajo el cerrojo del coordinador. */
public final class Emergency {
    public enum Severity {
        CRITICA(1000), GRAVE(700), MODERADA(400), LEVE(100);
        private final int points;
        Severity(int points) { this.points = points; }
        public int points() { return points; }
    }
    public enum Status {
        REGISTRADA, EN_ESPERA, ASIGNADA, EN_CAMINO, EN_ATENCION, COMPLETADA, FALLIDA
    }

    private final String id;
    private final Severity severity;
    private final GeoPoint location;
    private final String description;
    private final long createdNanos;
    private volatile Status status = Status.REGISTRADA;
    private volatile int attempts;
    private volatile long firstAssignmentNanos;
    private volatile long finishedNanos;

    public Emergency(String id, Severity severity, GeoPoint location, String description) {
        this.id = Objects.requireNonNull(id);
        this.severity = Objects.requireNonNull(severity);
        this.location = Objects.requireNonNull(location);
        this.description = Objects.requireNonNull(description);
        this.createdNanos = System.nanoTime();
        if (id.isBlank()) throw new IllegalArgumentException("ID vacío");
    }
    public String id() { return id; }
    public Severity severity() { return severity; }
    public GeoPoint location() { return location; }
    public String description() { return description; }
    public long createdNanos() { return createdNanos; }
    public Status status() { return status; }
    public int attempts() { return attempts; }
    public long firstAssignmentNanos() { return firstAssignmentNanos; }
    public long finishedNanos() { return finishedNanos; }

    // Solo el coordinador modifica estos campos bajo su único cerrojo.
    void status(Status value) { status = value; }
    void nextAttempt() { attempts++; }
    void firstAssignedAt(long nanos) { if (firstAssignmentNanos == 0L) firstAssignmentNanos = nanos; }
    void finishedAt(long nanos) { finishedNanos = nanos; }
}
