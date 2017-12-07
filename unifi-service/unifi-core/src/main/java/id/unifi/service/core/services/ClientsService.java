package id.unifi.service.core.services;

import id.unifi.service.core.api.annotations.ApiOperation;
import id.unifi.service.core.api.annotations.ApiService;
import id.unifi.service.core.db.Database;
import static id.unifi.service.core.db.Tables.CLIENT;
import static java.util.stream.Collectors.toList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

@ApiService("client")
public class ClientsService {
    private static final Logger log = LoggerFactory.getLogger(ClientsService.class);

    private final Database db;

    public ClientsService(Database db) {
        this.db = db;
    }

    private static class Client {
        public final String clientId;
        public final String displayName;
        public final byte[] logo;

        Client(String clientId, String displayName, byte[] logo) {
            this.clientId = clientId;
            this.displayName = displayName;
            this.logo = logo;
        }

        public String toString() {
            return "Client{" +
                    "clientId='" + clientId + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", logo=" + Arrays.toString(logo) +
                    '}';
        }
    }

    @ApiOperation
    public List<Client> listClients() {
        log.info("Listing clients");
        return db.execute(sql -> sql.selectFrom(CLIENT).fetch().stream()
                .map(c -> new Client(c.getClientId(), c.getDisplayName(), c.getLogo()))
                .collect(toList()));
    }

    @ApiOperation
    public void registerClient(String clientId, String displayName, byte[] logo) {
        log.info("Registering client " + clientId);
        db.execute(sql -> sql.insertInto(CLIENT, CLIENT.CLIENT_ID, CLIENT.DISPLAY_NAME, CLIENT.LOGO)
                .values(clientId, displayName, logo)
                .execute());
    }
}
