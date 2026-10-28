package edu.iudigital.emergencias;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Paso de mensajes asíncronos centro de despacho -> hospital mediante BlockingQueue. */
public final class Communications implements AutoCloseable {
    public record HospitalMessage(String incidentId, String event, String ambulanceId) {}
    private final BlockingQueue<HospitalMessage> toHospital = new LinkedBlockingQueue<>();
    private final AtomicInteger delivered = new AtomicInteger();
    private final boolean verbose;
    private volatile boolean running;
    private Thread hospitalThread;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public Communications(boolean verbose) { this.verbose = verbose; }
    public void start() {
        running = true;
        hospitalThread = new Thread(() -> {
            try {
                while (running || !toHospital.isEmpty()) {
                    HospitalMessage m = toHospital.poll(150, TimeUnit.MILLISECONDS);
                    if (m != null) {
                        delivered.incrementAndGet();
                        event("HOSPITAL", "mensaje recibido: %s | %s | %s".formatted(
                                m.incidentId(), m.event(), m.ambulanceId()));
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "hospital-receptor");
        hospitalThread.start();
    }
    public void hospital(HospitalMessage m) { toHospital.add(m); }
    public int deliveredMessages() { return delivered.get(); }
    public void event(String actor, String message) {
        if (verbose) {
            synchronized (System.out) {
                System.out.printf("[%s] [%-11s] %s%n", LocalTime.now().format(TIME), actor, message);
            }
        }
    }
    @Override public void close() {
        running = false;
        if (hospitalThread != null) {
            try { hospitalThread.join(1500); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (hospitalThread.isAlive()) hospitalThread.interrupt();
        }
    }
}
