package codes.grow.packages.signoz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.signoz.core.ValidationException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class FilterBuilderTest {
  private static FilterBuilder f() {
    return FilterBuilder.create();
  }

  @Test
  void rendersComparisonsListsAndText() {
    String expr =
        f().eq("service.name", "checkout")
            .in("severity_text", "ERROR", "FATAL")
            .ne("a", 1)
            .gt("b", 2L)
            .gte("c", 1.5)
            .lt("d", (short) 3)
            .lte("e", (byte) 4)
            .eq("ok", true)
            .notIn("n", List.of(13, 17))
            .exists("run.id")
            .notExists("x")
            .like("service.name", "sdk-%")
            .regexp("body", "prep-.*")
            .text("needle")
            .build();
    assertThat(expr)
        .isEqualTo(
            "service.name = 'checkout' AND severity_text IN ('ERROR', 'FATAL') AND a != 1 AND b > 2"
                + " AND c >= 1.5 AND d < 3 AND e <= 4 AND ok = true AND n NOT IN (13, 17)"
                + " AND run.id EXISTS AND x NOT EXISTS AND service.name LIKE 'sdk-%'"
                + " AND body REGEXP 'prep-.*' AND 'needle'");
    assertThat(f().isEmpty()).isTrue();
    assertThat(f().build()).isEmpty();
    assertThat(f().where("a", FilterOperator.GTE, 1).toString()).isEqualTo("a >= 1");
  }

  @Test
  void escapesStringsAndLikePatterns() {
    assertThat(FilterBuilder.quoteFilterString("it's a back\\slash"))
        .isEqualTo("'it\\'s a back\\\\slash'");
    assertThat(FilterBuilder.quoteFilterString(null)).isEqualTo("''");
    assertThat(FilterBuilder.escapeLikePattern("50%_a\\b")).isEqualTo("50\\%\\_a\\\\b");
    assertThat(FilterBuilder.escapeLikePattern(null)).isEmpty();
    // a literal backslash in CONTAINS needs four backslashes in the expression
    assertThat(f().contains("body", "back\\slash").toString())
        .isEqualTo("body CONTAINS 'back\\\\\\\\slash'");
    assertThat(f().contains("body", "it's 50%").toString())
        .isEqualTo("body CONTAINS 'it\\'s 50\\\\%'");
    assertThat(f().notContains("body", "a_b").toString()).isEqualTo("NOT body CONTAINS 'a\\\\_b'");
  }

  @Test
  void literals() {
    assertThat(FilterBuilder.filterLiteral(100.0)).isEqualTo("100");
    assertThat(FilterBuilder.filterLiteral(0.0)).isEqualTo("0");
    assertThat(FilterBuilder.filterLiteral(1.25f)).isEqualTo("1.25");
    assertThat(FilterBuilder.filterLiteral(new BigDecimal("2.50"))).isEqualTo("2.5");
    assertThat(FilterBuilder.filterLiteral(BigInteger.TEN)).isEqualTo("10");
    assertThat(FilterBuilder.filterLiteral(java.time.Duration.ofSeconds(1))).isEqualTo("'PT1S'");
    assertThatThrownBy(() -> FilterBuilder.filterLiteral(Double.NaN))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> FilterBuilder.filterLiteral(Float.POSITIVE_INFINITY))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> FilterBuilder.filterLiteral(null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void groupsRawAndAnd() {
    assertThat(f().eq("a", 1).or(f().eq("b", 1), f().eq("c", 2)).toString())
        .isEqualTo("a = 1 AND ((b = 1) OR (c = 2))");
    assertThat(f().or(f().eq("b", 1), null, f()).toString()).isEqualTo("(b = 1)");
    assertThat(f().or().toString()).isEmpty();
    assertThat(f().or((FilterBuilder[]) null).isEmpty()).isTrue();
    assertThat(f().not(f().exists("seq")).toString()).isEqualTo("NOT (seq EXISTS)");
    assertThat(f().not(null).isEmpty()).isTrue();
    assertThat(f().not(f()).isEmpty()).isTrue();
    assertThat(f().raw("seq = 7 OR seq = 8").toString()).isEqualTo("(seq = 7 OR seq = 8)");
    assertThat(f().raw("seq = 7").toString()).isEqualTo("seq = 7");
    assertThat(f().raw("  ").isEmpty()).isTrue();
    assertThat(f().raw(null).isEmpty()).isTrue();
    assertThat(FilterBuilder.andFilters("a = 1", " ", null, "b = 2"))
        .isEqualTo("(a = 1) AND (b = 2)");
    assertThat(FilterBuilder.andFilters("a = 1")).isEqualTo("a = 1");
    assertThat(FilterBuilder.andFilters((String[]) null)).isEmpty();
  }

  @Test
  void invalidInputSurfacesInBuild() {
    FilterBuilder bad = f().eq("bad field", 1).eq("ok", 1);
    assertThat(bad.toString()).isEqualTo("ok = 1");
    assertThatThrownBy(bad::build)
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("bad field");
    assertThatThrownBy(() -> f().in("x").build()).hasMessageContaining("must not be empty");
    assertThatThrownBy(() -> f().in("x", (Object[]) null).build())
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> f().notIn("x", (Object[]) null).build())
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> f().where("x", null, 1).build())
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> f().eq(null, 1).build()).hasMessageContaining("null");
    assertThatThrownBy(() -> f().or(f().gt("x", Double.NaN)).build())
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> f().not(f().eq("a b", 1)).build())
        .isInstanceOf(ValidationException.class);
    // the first error wins
    assertThatThrownBy(() -> f().eq("a b", 1).eq("c d", 1).build()).hasMessageContaining("a b");
  }
}
