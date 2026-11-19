package codes.grow.packages.signoz;

import java.util.Optional;

/**
 * A log field known to SigNoz. A name may exist in several contexts (for example an attribute and a
 * resource attribute).
 *
 * @param name field name, for example {@code service.name} or {@code body}
 * @param context {@code log}, {@code resource}, {@code attribute} or {@code scope}
 * @param dataType {@code string}, {@code number} or {@code bool}
 * @param description the server's description, if any
 */
public record LogField(String name, String context, String dataType, Optional<String> description) {
  /**
   * Fills {@code null} values.
   *
   * @param name name
   * @param context context
   * @param dataType data type
   * @param description description
   */
  public LogField {
    name = name == null ? "" : name;
    context = context == null ? "" : context;
    dataType = dataType == null ? "" : dataType;
    description = description == null ? Optional.empty() : description;
  }
}
