package id.unifi.service.core;

import com.statemachinesystems.envy.Default;
import com.statemachinesystems.envy.Envy;
import id.unifi.service.common.api.Dispatcher;
import id.unifi.service.common.api.HttpServer;
import id.unifi.service.common.api.Protocol;
import id.unifi.service.common.api.ServiceRegistry;
import id.unifi.service.common.config.UnifiConfigSource;
import id.unifi.service.common.operator.InMemorySessionTokenStore;
import id.unifi.service.common.operator.SessionTokenStore;
import id.unifi.service.common.provider.EmailSenderProvider;
import id.unifi.service.common.provider.LoggingEmailSender;
import id.unifi.service.common.version.VersionInfo;
import static java.net.InetSocketAddress.createUnresolved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;

public class CoreService {
    private static final Logger log = LoggerFactory.getLogger(CoreService.class);

    private interface Config {
        @Default("8000")
        int apiHttpPort();

        @Default("8001")
        int agentServiceHttpPort();
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

        ServiceRegistry registry = new ServiceRegistry(
                Map.of("core", "id.unifi.service.core.services"),
                Map.of(
                        SessionTokenStore.class, new InMemorySessionTokenStore(864000),
                        EmailSenderProvider.class, new LoggingEmailSender()));
        Dispatcher<?> dispatcher =
                new Dispatcher<>(registry, OperatorSessionData.class, s -> new OperatorSessionData());
        InetSocketAddress apiServerSocket = createUnresolved("0.0.0.0", config.apiHttpPort());
        HttpServer apiServer = new HttpServer(
                apiServerSocket,
                "/service",
                dispatcher,
                Set.of(Protocol.JSON, Protocol.MSGPACK));
        apiServer.start();

        ServiceRegistry agentRegistry = new ServiceRegistry(
                Map.of("core", "id.unifi.service.core.agentservices"),
                Map.of());
        Dispatcher<AgentSessionData> agentDispatcher =
                new Dispatcher<>(agentRegistry, AgentSessionData.class, AgentSessionData::new);

        InetSocketAddress agentServerSocket = createUnresolved("0.0.0.0", config.agentServiceHttpPort());
        HttpServer agentServer = new HttpServer(
                agentServerSocket,
                "/agent-service",
                agentDispatcher,
                Set.of(Protocol.MSGPACK));
        agentServer.start();
    }
}
