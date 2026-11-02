package local.governance;

import java.util.Arrays;
import local.governance.admin.AdminCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
@org.springframework.scheduling.annotation.EnableScheduling
public class GovernanceApplication {

    public static void main(String[] args) {
        if (args.length > 0 && "admin".equals(args[0])) {
            SpringApplication application = new SpringApplication(GovernanceApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(java.util.Map.of(
                    "spring.main.banner-mode", "off",
                    "logging.level.root", "ERROR"));
            ConfigurableApplicationContext context = application.run();
            int exitCode = context.getBean(AdminCli.class).run(Arrays.copyOfRange(args, 1, args.length));
            context.close();
            System.exit(exitCode);
        }
        SpringApplication.run(GovernanceApplication.class, args);
    }
}
