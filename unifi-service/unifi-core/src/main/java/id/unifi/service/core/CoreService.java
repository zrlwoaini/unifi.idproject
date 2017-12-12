package id.unifi.service.core;

import com.statemachinesystems.envy.Default;
import com.statemachinesystems.envy.Envy;
import id.unifi.service.common.api.Dispatcher;
import id.unifi.service.common.api.HttpServer;
import id.unifi.service.common.api.ServiceRegistry;
import id.unifi.service.common.config.UnifiConfigSource;
import id.unifi.service.common.db.Database;
import id.unifi.service.common.db.DatabaseConfig;
import id.unifi.service.common.db.DatabaseUtils;
import id.unifi.service.common.operator.InMemorySessionTokenStore;
import id.unifi.service.common.operator.SessionTokenStore;
import id.unifi.service.common.provider.EmailSenderProvider;
import id.unifi.service.common.provider.LoggingEmailSender;
import id.unifi.service.common.version.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class CoreService {
    private static final Logger log = LoggerFactory.getLogger(CoreService.class);

    private interface Config {
        @Default("8000")
        int httpPort();

        DatabaseConfig core();
    }

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)
                    .error("Uncaught exception in thread '" + t.getName() + "'", e);
            System.exit(1);
        });

        log.info("Starting unifi.id Core");
        VersionInfo.log();

        Config config = Envy.configure(Config.class, UnifiConfigSource.get());
        Database coreDb = DatabaseUtils.prepareSqlDatabase(DatabaseUtils.CORE_DB_NAME, config.core());

        ServiceRegistry registry = new ServiceRegistry(
                Map.of("core", "id.unifi.service.core.services"),
                Map.of(
                        SessionTokenStore.class, new InMemorySessionTokenStore(864000),
                        Database.class, coreDb,
                        EmailSenderProvider.class, new LoggingEmailSender()));
        Dispatcher dispatcher = new Dispatcher(registry, SessionData.class);
        HttpServer server = new HttpServer(config.httpPort(), dispatcher);
        server.start();
    }
}
