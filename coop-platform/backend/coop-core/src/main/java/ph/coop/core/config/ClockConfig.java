package ph.coop.core.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The clock postings read the date from; tests replace it with a fixed one. */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
