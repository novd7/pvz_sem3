package pvz_mirea;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@Profile("demo")
class DemoData {
    @Bean
    ApplicationRunner seedDemo(DataSource dataSource) {
        return args -> new ResourceDatabasePopulator(new ClassPathResource("demo/seed.sql")).execute(dataSource);
    }
}
