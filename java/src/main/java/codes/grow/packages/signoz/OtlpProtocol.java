package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.ConfigException;
import java.util.Locale;

/** OTLP export protocol used for logs and traces sent to the SigNoz collector. */
public enum OtlpProtocol {
  /** OTLP over HTTP with protobuf bodies ({@code "http/protobuf"}, collector port 4318). */
  HTTP_PROTOBUF("http/protobuf"),
  /** OTLP over gRPC ({@code "grpc"}, collector port 4317). */
  GRPC("grpc");

  private final String wire;

  OtlpProtocol(String wire) {
    this.wire = wire;
  }

  /**
   * Returns the wire value.
   *
   * @return {@code "http/protobuf"} or {@code "grpc"}
   */
  public String wireValue() {
    return wire;
  }

  /**
   * Parses a wire value case-insensitively.
   *
   * @param text {@code "http/protobuf"} or {@code "grpc"}
   * @return the protocol
   * @throws ConfigException for any other value
   */
  public static OtlpProtocol parse(String text) {
    String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    for (OtlpProtocol p : values()) {
      if (p.wire.equals(t)) {
        return p;
      }
    }
    throw new ConfigException("config: otlpProtocol must be \"http/protobuf\" or \"grpc\"");
  }
}
