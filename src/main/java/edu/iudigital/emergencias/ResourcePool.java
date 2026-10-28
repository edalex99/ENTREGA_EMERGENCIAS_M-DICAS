package edu.iudigital.emergencias;

import java.util.ArrayList;
import java.util.List;

/** Entidad de recursos. Todas las operaciones mutables son invocadas bajo el lock del coordinador. */
public final class ResourcePool {
    public enum State { DISPONIBLE, EN_CAMINO, OCUPADO, FUERA_DE_SERVICIO }
    public static final class Ambulance {
        private final String id;
        private final GeoPoint base;
        private State state = State.DISPONIBLE;
        private String incidentId;
        private Ambulance(String id, GeoPoint base) { this.id = id; this.base = base; }
        public String id() { return id; }
        public GeoPoint base() { return base; }
        public State state() { return state; }
        public String incidentId() { return incidentId; }
    }
    public static final class Unit {
        private final String id;
        private State state = State.DISPONIBLE;
        private String incidentId;
        private Unit(String id) { this.id = id; }
        public String id() { return id; }
        public State state() { return state; }
        public String incidentId() { return incidentId; }
    }
    public record Bundle(Ambulance ambulance, Unit medic, Unit kit, double distanceKm) {}
    public record Counts(int ambulancesFree, int ambulancesEnRoute, int ambulancesBusy,
                         int ambulancesOutOfService, int medicsFree, int kitsFree) {}

    private static final GeoPoint[] BASES = {
        new GeoPoint(6.2518, -75.5636), new GeoPoint(6.2145, -75.5780),
        new GeoPoint(6.2890, -75.5570), new GeoPoint(6.2290, -75.6090),
        new GeoPoint(6.2710, -75.5980), new GeoPoint(6.2000, -75.5500)
    };
    private final List<Ambulance> ambulances = new ArrayList<>();
    private final List<Unit> medics = new ArrayList<>();
    private final List<Unit> kits = new ArrayList<>();

    public ResourcePool(int ambulanceCount, int medicCount, int kitCount) {
        if (ambulanceCount < 1 || medicCount < 1 || kitCount < 1)
            throw new IllegalArgumentException("Debe existir al menos un recurso de cada clase");
        for (int i = 0; i < ambulanceCount; i++) {
            GeoPoint p = BASES[i % BASES.length];
            ambulances.add(new Ambulance("AMB-%02d".formatted(i + 1), p));
        }
        for (int i = 0; i < medicCount; i++) medics.add(new Unit("MED-%02d".formatted(i + 1)));
        for (int i = 0; i < kitCount; i++) kits.add(new Unit("EQU-%02d".formatted(i + 1)));
    }
    public int ambulanceCount() { return ambulances.size(); }

    /** Devuelve un paquete completo, o null sin reservar nada. */
    Bundle findBundle(GeoPoint destination) {
        Unit medic = medics.stream().filter(x -> x.state == State.DISPONIBLE).findFirst().orElse(null);
        Unit kit = kits.stream().filter(x -> x.state == State.DISPONIBLE).findFirst().orElse(null);
        if (medic == null || kit == null) return null;
        Ambulance closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Ambulance a : ambulances) {
            if (a.state == State.DISPONIBLE) {
                double d = a.base.kmTo(destination);
                if (d < distance) { closest = a; distance = d; }
            }
        }
        return closest == null ? null : new Bundle(closest, medic, kit, distance);
    }
    double nearestBaseDistance(GeoPoint location) {
        return ambulances.stream().mapToDouble(a -> a.base.kmTo(location)).min().orElse(0.0);
    }
    void reserve(Bundle b, String incidentId) {
        if (b.ambulance.state != State.DISPONIBLE || b.medic.state != State.DISPONIBLE
                || b.kit.state != State.DISPONIBLE) throw new IllegalStateException("Asignación duplicada");
        b.ambulance.state = State.EN_CAMINO;
        b.medic.state = State.OCUPADO;
        b.kit.state = State.OCUPADO;
        b.ambulance.incidentId = incidentId;
        b.medic.incidentId = incidentId;
        b.kit.incidentId = incidentId;
    }
    void onScene(Bundle b) { b.ambulance.state = State.OCUPADO; }
    void release(Bundle b) {
        b.ambulance.state = State.DISPONIBLE;
        b.ambulance.incidentId = null;
        releaseUnit(b.medic); releaseUnit(b.kit);
    }
    void outOfService(Bundle b) {
        b.ambulance.state = State.FUERA_DE_SERVICIO;
        b.ambulance.incidentId = null;
        releaseUnit(b.medic); releaseUnit(b.kit);
    }
    void repair(Ambulance ambulance) {
        if (ambulance.state == State.FUERA_DE_SERVICIO) ambulance.state = State.DISPONIBLE;
    }
    private void releaseUnit(Unit unit) { unit.state = State.DISPONIBLE; unit.incidentId = null; }
    Counts counts() {
        return new Counts((int) ambulances.stream().filter(a -> a.state == State.DISPONIBLE).count(),
                (int) ambulances.stream().filter(a -> a.state == State.EN_CAMINO).count(),
                (int) ambulances.stream().filter(a -> a.state == State.OCUPADO).count(),
                (int) ambulances.stream().filter(a -> a.state == State.FUERA_DE_SERVICIO).count(),
                (int) medics.stream().filter(m -> m.state == State.DISPONIBLE).count(),
                (int) kits.stream().filter(k -> k.state == State.DISPONIBLE).count());
    }
}
