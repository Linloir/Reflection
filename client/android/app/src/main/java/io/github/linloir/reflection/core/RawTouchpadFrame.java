package io.github.linloir.reflection.core;

import java.util.HashSet;
import java.util.Set;

/** Complete contact snapshots for a negotiated native touchpad device. */
public final class RawTouchpadFrame {
    public static final int DOWN = 1, UPDATE = 2, UP = 3, MAX_CONTACTS = 5;
    public final Contact[] contacts;
    public final boolean cancelled;
    public final long timeNanos;

    public static final class Contact {
        public final int id, phase;
        public final float x, y;
        public final boolean confident;
        public Contact(int id, int phase, float x, float y, boolean confident) {
            if (id < 0 || phase < DOWN || phase > UP || !finite(x) || !finite(y)
                    || x < 0 || x > 1 || y < 0 || y > 1)
                throw new IllegalArgumentException("Invalid touchpad contact");
            this.id = id; this.phase = phase; this.x = x; this.y = y; this.confident = confident;
        }
    }

    public RawTouchpadFrame(Contact[] contacts, boolean cancelled, long timeNanos) {
        if (contacts == null || timeNanos < 0 || contacts.length > MAX_CONTACTS
                || (cancelled ? contacts.length != 0 : contacts.length == 0))
            throw new IllegalArgumentException("Invalid touchpad frame");
        Set<Integer> ids = new HashSet<>();
        for (Contact contact : contacts)
            if (contact == null || !ids.add(contact.id)) throw new IllegalArgumentException("Duplicate touchpad contact");
        this.contacts = contacts.clone(); this.cancelled = cancelled; this.timeNanos = timeNanos;
    }

    public static RawTouchpadFrame cancel(long timeNanos) {
        return new RawTouchpadFrame(new Contact[0], true, timeNanos);
    }
    public static boolean validDimension(int value) { return value >= 500 && value <= 100000; }
    public static int physicalDimension(int pixels, float dpi, float density, int fallback) {
        if (pixels <= 0) return fallback;
        if (!finite(dpi) || dpi < 40 || dpi > 2000) dpi = finite(density) && density > 0 ? density * 160 : 160;
        return Math.max(500, Math.min(100000, Math.round(pixels * 2540f / dpi)));
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }

    /** Rejects incomplete snapshots and tails from a gesture whose DOWN was lost. */
    public static final class State {
        private Set<Integer> active = new HashSet<>();
        public boolean active() { return !active.isEmpty(); }
        public void reset() { active.clear(); }
        public boolean accept(RawTouchpadFrame frame) {
            if (frame.cancelled) { reset(); return true; }
            Set<Integer> represented = new HashSet<>(), next = new HashSet<>();
            for (Contact contact : frame.contacts) {
                represented.add(contact.id);
                if (contact.phase == DOWN ? active.contains(contact.id) : !active.contains(contact.id)) return false;
                if (contact.phase != UP) next.add(contact.id);
            }
            if (!represented.containsAll(active)) return false;
            active = next; return true;
        }
    }
}
