# FilterBuilder

`FilterBuilder` renders SigNoz filter expressions (the v5 search syntax) with correct quoting and
escaping, so values from users can never change the meaning of a query. It is immutable: every
method returns a new builder. Clauses are joined with `AND`; `or(...)` and `not(...)` group
sub-expressions in parentheses.

| Method | Renders |
| - | - |
| `eq` / `ne` / `lt` / `lte` / `gt` / `gte(name, value)` | `name = 'v'`, `name != 'v'`, `name < 5`, … |
| `where(name, op, value)` | any comparison operator |
| `in` / `notIn(name, values)` (Rust `in_list`, Python `in_`) | `name IN ('a', 'b')` / `name NOT IN (...)`; an empty list is `VALIDATION` |
| `contains` / `notContains(name, text)` | `name CONTAINS 'text'` with `%`, `_` and `\` escaped (SigNoz treats CONTAINS as a LIKE pattern) |
| `like(name, pattern)` / `regexp(name, pattern)` | `name LIKE 'pattern'` / `name REGEXP 'pattern'` (pattern as given) |
| `exists` / `notExists(name)` | `name EXISTS` / `name NOT EXISTS` |
| `text(value)` | a quoted full-text term (searches the body) |
| `raw(expression)` | an expression added as is |
| `or(...alternatives)` / `not(expression)` (Python `or_` / `not_`) | `(a OR b)` / `NOT (a)` |
| `build()` / `toString()` | the expression (Go `Build()` returns `(string, error)`, Rust `Result<String>`) |

Helpers: `andFilters(...parts)` joins non-empty expressions as `(a) AND (b)`;
`quoteFilterString(s)` quotes a string literal (`'it\'s'`); `escapeLikePattern(s)` escapes `%`,
`_` and `\`; `filterLiteral(value)` renders a string, number or boolean literal. Field names are
checked (`VALIDATION` for empty names or names with spaces/quotes).

=== "TypeScript"

    ```ts
    import { FilterBuilder, andFilters } from "@grow-codes/signoz";

    const f = new FilterBuilder()
      .eq("service.name", "checkout")
      .in("severity_text", ["ERROR", "FATAL"])
      .contains("body", "50% off")
      .or(new FilterBuilder().gte("http.status_code", 500), "retry = true");
    f.build(); // service.name = 'checkout' AND severity_text IN ('ERROR', 'FATAL') AND body CONTAINS '50\% off' AND (...)
    andFilters(f, "deployment.environment = 'prod'");
    ```

=== "Rust"

    ```rust
    use grow_signoz::{and_filters, FilterBuilder};

    let f = FilterBuilder::new()
        .eq("service.name", "checkout")
        .in_list("severity_text", ["ERROR", "FATAL"])
        .contains("body", "50% off")
        .build()?;
    let all = and_filters([f.as_str(), "deployment.environment = 'prod'"]);
    ```

=== "Go"

    ```go
    f, err := signoz.NewFilter().
        Eq("service.name", "checkout").
        In("severity_text", "ERROR", "FATAL").
        Contains("body", "50% off").
        Or(signoz.NewFilter().Gte("http.status_code", 500), signoz.NewFilter().Raw("retry = true")).
        Build()
    all := signoz.AndFilters(f, "deployment.environment = 'prod'")
    ```

=== "Python"

    ```python
    from grow_signoz import FilterBuilder, and_filters

    f = (
        FilterBuilder()
        .eq("service.name", "checkout")
        .in_("severity_text", ["ERROR", "FATAL"])
        .contains("body", "50% off")
        .or_(FilterBuilder().gte("http.status_code", 500), "retry = true")
    )
    expr = f.build()
    both = and_filters(f, "deployment.environment = 'prod'")
    ```

=== "Java"

    ```java
    String f =
        FilterBuilder.create()
            .eq("service.name", "checkout")
            .in("severity_text", "ERROR", "FATAL")
            .contains("body", "50% off")
            .or(FilterBuilder.create().gte("http.status_code", 500), FilterBuilder.create().raw("retry = true"))
            .build();
    String all = FilterBuilder.andFilters(f, "deployment.environment = 'prod'");
    ```
