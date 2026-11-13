package local.governance.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {

    @Bean
    public Clock clock(GovernanceProperties properties) {
        if (properties.getTestControl().isEnabled()) {
            return MutableClock.utc();
        }
        return Clock.systemUTC();
    }
}
