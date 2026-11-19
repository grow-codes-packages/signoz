package codes.grow.packages.signoz;

import codes.grow.packages.signoz.core.ValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Renders SigNoz filter expressions (query builder v5 search syntax), for example
 *
 * <pre>{@code
 * FilterBuilder.create().eq("service.name", "checkout").in("severity_text", "ERROR", "FATAL").build()
 * // service.name = 'checkout' AND severity_text IN ('ERROR', 'FATAL')
 * }</pre>
 *
 * <p>Conditions are joined with {@code AND}; use {@link #or} and {@link #not} to group. Values are
 * quoted and escaped; {@link #contains} escapes LIKE wildcards so the text matches literally. A
 * builder is immutable: every method returns a new builder, so a base filter can be reused. Invalid
 * input (a bad field name, an empty IN list, a non-finite number) is reported by {@link #build()}.
 */
public final class FilterBuilder {
  private static final Pattern FIELD = Pattern.compile("^[A-Za-z0-9_.:\\-]+$");
  private static final Pattern OR = Pattern.compile("(?i)\\bOR\\b");

  private final List<String> parts;
  private final ValidationException error;

  private FilterBuilder(List<String> parts, ValidationException error) {
    this.parts = parts;
    this.error = error;
  }

  /**
   * Returns an empty filter (matches everything).
   *
   * @return a new builder
   */
  public static FilterBuilder create() {
    return new FilterBuilder(List.of(), null);
  }

  private FilterBuilder with(String expr, ValidationException err) {
    List<String> next = new ArrayList<>(parts);
    if (expr != null && !expr.isEmpty()) {
      next.add(expr);
    }
    return new FilterBuilder(List.copyOf(next), error != null ? error : err);
  }

  private FilterBuilder fail(ValidationException err) {
    return with(null, err);
  }

  /**
   * Checks a field name ({@code [A-Za-z0-9_.:-]+}).
   *
   * @param name field name
   * @return the name
   * @throws ValidationException when it is invalid
   */
  static String checkField(String name) {
    if (name == null || !FIELD.matcher(name).matches()) {
      throw new ValidationException(
          "filter: invalid field name \"" + (name == null ? "null" : name) + "\"");
    }
    return name;
  }

  private FilterBuilder cond(String prefix, String field, Supplier rest) {
    try {
      checkField(field);
      return with(prefix + field + " " + rest.get(), null);
    } catch (ValidationException e) {
      return fail(e);
    }
  }

  @FunctionalInterface
  private interface Supplier {
    String get();
  }

  /**
   * Adds {@code field <op> value}.
   *
   * @param field field name
   * @param op comparison operator
   * @param value string, number or boolean
   * @return a new builder
   */
  public FilterBuilder where(String field, FilterOperator op, Object value) {
    if (op == null) {
      return fail(new ValidationException("filter: operator must not be null"));
    }
    return cond("", field, () -> op.symbol() + " " + filterLiteral(value));
  }

  /**
   * Adds {@code field = value}.
   *
   * @param field field name
   * @param value value
   * @return a new builder
   */
  public FilterBuilder eq(String field, Object value) {
    return where(field, FilterOperator.EQ, value);
  }

  /**
   * Adds {@code field != value}.
   *
   * @param field field name
   * @param value value
   * @return a new builder
   */
  public FilterBuilder ne(String field, Object value) {
    return where(field, FilterOperator.NE, value);
  }

  /**
   * Adds {@code field > value}.
   *
   * @param field field name
   * @param value value
   * @return a new builder
   */
  public FilterBuilder gt(String field, Object value) {
    return where(field, FilterOperator.GT, value);
  }

  /**
   * Adds {@code field >= value}.
   *
   * @param field field name
   * @param value value
   * @return a new builder
   */
  public FilterBuilder gte(String field, Object value) {
    return where(field, FilterOperator.GTE, value);
  }

  /**
   * Adds {@code field < value}.
   *
   * @param field field name
   * @param value value
   * @return a new builder
   */
  public FilterBuilder lt(String field, Object value) {
    return where(field, FilterOperator.LT, value);
  }

  /**
   * Adds {@code field <= value}.
   *
   * @param field field name
   * @param value value
   * @return a new builder
   */
  public FilterBuilder lte(String field, Object value) {
    return where(field, FilterOperator.LTE, value);
  }

  private static String list(Collection<?> values) {
    if (values == null || values.isEmpty()) {
      throw new ValidationException("filter: IN lists must not be empty");
    }
    List<String> q = new ArrayList<>();
    for (Object v : values) {
      q.add(filterLiteral(v));
    }
    return "(" + String.join(", ", q) + ")";
  }

  /**
   * Adds {@code field IN (v1, v2, ...)} (at least one value).
   *
   * @param field field name
   * @param values values
   * @return a new builder
   */
  public FilterBuilder in(String field, Object... values) {
    return in(field, values == null ? List.of() : Arrays.asList(values));
  }

  /**
   * Adds {@code field IN (v1, v2, ...)} (at least one value).
   *
   * @param field field name
   * @param values values
   * @return a new builder
   */
  public FilterBuilder in(String field, Collection<?> values) {
    return cond("", field, () -> "IN " + list(values));
  }

  /**
   * Adds {@code field NOT IN (v1, v2, ...)} (at least one value).
   *
   * @param field field name
   * @param values values
   * @return a new builder
   */
  public FilterBuilder notIn(String field, Object... values) {
    return notIn(field, values == null ? List.of() : Arrays.asList(values));
  }

  /**
   * Adds {@code field NOT IN (v1, v2, ...)} (at least one value).
   *
   * @param field field name
   * @param values values
   * @return a new builder
   */
  public FilterBuilder notIn(String field, Collection<?> values) {
    return cond("", field, () -> "NOT IN " + list(values));
  }

  /**
   * Adds {@code field CONTAINS 'text'} matching {@code text} literally (case-insensitive; LIKE
   * wildcards {@code %} and {@code _} are escaped).
   *
   * @param field field name
   * @param text literal text
   * @return a new builder
   */
  public FilterBuilder contains(String field, String text) {
    return cond("", field, () -> "CONTAINS " + quoteFilterString(escapeLikePattern(text)));
  }

  /**
   * Adds {@code NOT field CONTAINS 'text'} ({@code text} matched literally).
   *
   * @param field field name
   * @param text literal text
   * @return a new builder
   */
  public FilterBuilder notContains(String field, String text) {
    return cond("NOT ", field, () -> "CONTAINS " + quoteFilterString(escapeLikePattern(text)));
  }

  /**
   * Adds {@code field LIKE 'pattern'}; {@code %} and {@code _} are wildcards.
   *
   * @param field field name
   * @param pattern LIKE pattern
   * @return a new builder
   */
  public FilterBuilder like(String field, String pattern) {
    return cond("", field, () -> "LIKE " + quoteFilterString(pattern));
  }

  /**
   * Adds {@code field REGEXP 'pattern'} (RE2 syntax, evaluated by ClickHouse).
   *
   * @param field field name
   * @param pattern regular expression
   * @return a new builder
   */
  public FilterBuilder regexp(String field, String pattern) {
    return cond("", field, () -> "REGEXP " + quoteFilterString(pattern));
  }

  /**
   * Adds {@code field EXISTS}.
   *
   * @param field field name
   * @return a new builder
   */
  public FilterBuilder exists(String field) {
    return cond("", field, () -> "EXISTS");
  }

  /**
   * Adds {@code field NOT EXISTS}.
   *
   * @param field field name
   * @return a new builder
   */
  public FilterBuilder notExists(String field) {
    return cond("", field, () -> "NOT EXISTS");
  }

  /**
   * Adds a quoted full-text term that searches the log body.
   *
   * @param value text
   * @return a new builder
   */
  public FilterBuilder text(String value) {
    return with(quoteFilterString(value), null);
  }

  /**
   * Adds an expression unchanged; it is parenthesised when it contains {@code OR}.
   *
   * @param expression filter expression
   * @return a new builder
   */
  public FilterBuilder raw(String expression) {
    String t = expression == null ? "" : expression.trim();
    if (t.isEmpty()) {
      return this;
    }
    if (OR.matcher(t).find()) {
      t = "(" + t + ")";
    }
    return with(t, null);
  }

  /**
   * Adds {@code ((a) OR (b) ...)} built from the non-empty alternatives ({@code (a)} for a single
   * one).
   *
   * @param alternatives alternatives
   * @return a new builder
   */
  public FilterBuilder or(FilterBuilder... alternatives) {
    List<String> alts = new ArrayList<>();
    ValidationException err = null;
    for (FilterBuilder a : alternatives == null ? new FilterBuilder[0] : alternatives) {
      if (a == null) {
        continue;
      }
      if (err == null) {
        err = a.error;
      }
      String s = a.toString();
      if (!s.isEmpty()) {
        alts.add(s);
      }
    }
    if (alts.isEmpty()) {
      return with(null, err);
    }
    if (alts.size() == 1) {
      return with("(" + alts.get(0) + ")", err);
    }
    return with("((" + String.join(") OR (", alts) + "))", err);
  }

  /**
   * Adds {@code NOT (g)}.
   *
   * @param group negated group
   * @return a new builder
   */
  public FilterBuilder not(FilterBuilder group) {
    if (group == null) {
      return this;
    }
    String s = group.toString();
    return with(s.isEmpty() ? null : "NOT (" + s + ")", group.error);
  }

  /**
   * Whether no condition was added.
   *
   * @return true when empty
   */
  public boolean isEmpty() {
    return parts.isEmpty();
  }

  /**
   * Renders the expression ({@code ""} when empty), ignoring validation errors; use {@link
   * #build()} to see them.
   *
   * @return the expression
   */
  @Override
  public String toString() {
    return String.join(" AND ", parts);
  }

  /**
   * Renders the expression or throws the first validation error.
   *
   * @return the expression
   * @throws ValidationException for invalid input
   */
  public String build() {
    if (error != null) {
      throw new ValidationException(error.getMessage());
    }
    return toString();
  }

  /**
   * Joins non-empty expressions with {@code AND}, parenthesising each when there is more than one.
   *
   * @param parts expressions
   * @return the joined expression
   */
  public static String andFilters(String... parts) {
    List<String> out = new ArrayList<>();
    for (String p : parts == null ? new String[0] : parts) {
      String t = p == null ? "" : p.trim();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    if (out.size() <= 1) {
      return String.join("", out);
    }
    return "(" + String.join(") AND (", out) + ")";
  }

  /**
   * Quotes a string in single quotes with {@code \} and {@code '} escaped.
   *
   * @param s text
   * @return the quoted literal
   */
  public static String quoteFilterString(String s) {
    String t = s == null ? "" : s;
    return "'" + t.replace("\\", "\\\\").replace("'", "\\'") + "'";
  }

  /**
   * Escapes {@code \}, {@code %} and {@code _} so text matches literally inside {@code CONTAINS} or
   * {@code LIKE} (quote it afterwards).
   *
   * @param text text
   * @return the escaped pattern
   */
  public static String escapeLikePattern(String text) {
    String t = text == null ? "" : text;
    return t.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  /**
   * Renders a filter value: strings quoted ({@link #quoteFilterString}), booleans {@code
   * true}/{@code false}, numbers bare; other objects are quoted by their text.
   *
   * @param value value
   * @return the literal
   * @throws ValidationException for {@code null} or non-finite numbers
   */
  public static String filterLiteral(Object value) {
    if (value == null) {
      throw new ValidationException("filter: values must not be null");
    }
    if (value instanceof String) {
      return quoteFilterString((String) value);
    }
    if (value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Integer
        || value instanceof Long
        || value instanceof Short
        || value instanceof Byte
        || value instanceof BigInteger) {
      return value.toString();
    }
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (!Double.isFinite(d)) {
        throw new ValidationException("filter: numbers must be finite");
      }
      return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
    if (value instanceof BigDecimal) {
      return ((BigDecimal) value).stripTrailingZeros().toPlainString();
    }
    return quoteFilterString(value.toString());
  }
}
