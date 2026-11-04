package local.governance.guard;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GuardConfiguration {

    @Bean
    public GuardDecisionCore guardDecisionCore() {
        return new GuardDecisionCore();
    }

    @Bean
    public GuardDetectorCatalog guardDetectorCatalog(GuardDecisionCore core) {
        return new GuardDetectorCatalog(core);
    }
}
