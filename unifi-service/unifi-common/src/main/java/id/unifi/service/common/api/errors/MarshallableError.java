package id.unifi.service.common.api.errors;

import com.fasterxml.jackson.annotation.JsonIgnore;

public interface MarshallableError {
    String getMessage();

    @JsonIgnore
    String getProtocolMessageType();
}
