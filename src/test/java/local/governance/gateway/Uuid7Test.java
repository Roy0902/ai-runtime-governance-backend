package local.governance.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Uuid7Test {

    @Test
    void generatesRfc9562Version7Uuids() {
        UUID id = Uuid7.generate();

        assertEquals(7, id.version());
        assertEquals(2, id.variant());
    }

    @Test
    void valuesAreStrictlyIncreasing() {
        String previous = Uuid7.generate().toString();
        for (int i = 0; i < 10_000; i++) {
            String current = Uuid7.generate().toString();
            assertTrue(previous.compareTo(current) < 0, previous + " !< " + current);
            previous = current;
        }
    }

    @Test
    void embedsTheCurrentTime() {
        long before = System.currentTimeMillis();
        UUID id = Uuid7.generate();
        long after = System.currentTimeMillis();

        long timestamp = id.getMostSignificantBits() >>> 16;
        assertTrue(
                timestamp >= before && timestamp <= after,
                () -> "timestamp " + timestamp + " outside [" + before + ", " + after + "]");
    }

    @Test
    void valuesAreUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(ids.add(Uuid7.generate()));
        }
    }
}
