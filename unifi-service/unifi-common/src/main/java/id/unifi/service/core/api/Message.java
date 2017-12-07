package id.unifi.service.core.api;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Pattern;

public class Message {
    public final Version protocolVersion;
    public final Version releaseVersion;
    public final byte[] correlationId;
    public final String messageType;
    public final JsonNode payload;

    public static class Version {
        public final int major;
        public final int minor;
        public final int revision;

        private final Pattern versionSplitter = Pattern.compile("\\.");

        @JsonCreator
        public Version(String version) {
            String[] split = versionSplitter.split(version, 3);
            this.major = Integer.parseInt(split[0]);
            this.minor = Integer.parseInt(split[1]);
            this.revision = Integer.parseInt(split[2]);
        }

        public Version(int major, int minor, int revision) {
            this.major = major;
            this.minor = minor;
            this.revision = revision;
        }

        @JsonValue
        public String toString() {
            return major + "." + minor + "." + revision;
        }
    }

    @JsonCreator
    public Message(Version protocolVersion,
                   Version releaseVersion,
                   byte[] correlationId,
                   String messageType,
                   JsonNode payload) {
        this.protocolVersion = protocolVersion;
        this.releaseVersion = releaseVersion;
        this.correlationId = correlationId;
        this.messageType = messageType;
        this.payload = payload;
    }
}
