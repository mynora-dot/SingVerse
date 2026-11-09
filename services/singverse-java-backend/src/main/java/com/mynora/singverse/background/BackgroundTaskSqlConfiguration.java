package com.mynora.singverse.background;

import java.util.Properties;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BackgroundTaskSqlConfiguration {
    @Bean DatabaseIdProvider databaseIdProvider() {
        var names = new Properties(); names.setProperty("SQLite", "sqlite"); names.setProperty("MySQL", "mysql");
        var provider = new VendorDatabaseIdProvider(); provider.setProperties(names); return provider;
    }
}
