package local.governance.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** App clock that the test profile can advance. Production uses the system clock. */
public class MutableClock extends Clock {

    private volatile Instant instant;
    private final ZoneId zone;

    public MutableClock(Instant instant, ZoneId zone) {
        this.instant = instant;
        this.zone = zone;
    }

    public void set(Instant next) {
        this.instant = next;
    }

    public void advanceMillis(long millis) {
        this.instant = this.instant.plusMillis(millis);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId nextZone) {
        return new MutableClock(instant, nextZone);
    }

    @Override
    public Instant instant() {
        return instant;
    }

    public static MutableClock utc() {
        return new MutableClock(Instant.now(), ZoneOffset.UTC);
    }
}
