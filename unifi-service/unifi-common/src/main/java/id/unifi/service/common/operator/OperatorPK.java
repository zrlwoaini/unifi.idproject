package id.unifi.service.common.operator;

public class OperatorPK {
    public final String clientId;
    public final String username;

    public OperatorPK(String clientId, String username) {
        this.clientId = clientId;
        this.username = username;
    }
}
