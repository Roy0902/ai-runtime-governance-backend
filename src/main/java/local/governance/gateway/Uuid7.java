package local.governance.gateway;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUID version 7 generator.
 *
 * <p>The 48-bit Unix epoch millisecond timestamp occupies the most significant
 * bits, so generated values sort by creation time when compared as 128-bit
 * unsigned values (or as their canonical hex strings). Within one millisecond a
 * 12-bit counter in {@code rand_a} keeps values strictly increasing; the
 * remaining 62 bits are random.
 *
 * <p>If the wall clock moves backwards the generator keeps the last observed
 * millisecond and advances the counter, so a UUIDv7 is never earlier than the
 * value generated before it.
 */
public final class Uuid7 {

    private static final long TIMESTAMP_MASK = 0xFFFFFFFFFFFFL;
    private static final long COUNTER_MASK = 0xFFFL;
    private static final long MAX_COUNTER = 0xFFFL;
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC = 0x8000000000000000L;
    private static final long RANDOM_B_MASK = 0x3FFFFFFFFFFFFFFFL;

    private static final SecureRandom RANDOM = new SecureRandom();

    private static long lastMillis = -1L;
    private static long counter;

    private Uuid7() {}

    public static synchronized UUID generate() {
        long now = System.currentTimeMillis();
        if (now > lastMillis) {
            lastMillis = now;
            counter = 0L;
        } else {
            counter++;
            if (counter > MAX_COUNTER) {
                lastMillis++;
                counter = 0L;
            }
        }

        long timestamp = lastMillis & TIMESTAMP_MASK;
        long randA = counter & COUNTER_MASK;
        long randB = RANDOM.nextLong() & RANDOM_B_MASK;

        long mostSigBits = (timestamp << 16) | VERSION_7 | randA;
        long leastSigBits = randB | VARIANT_RFC;
        return new UUID(mostSigBits, leastSigBits);
    }
}
