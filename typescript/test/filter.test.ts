import { describe, expect, it } from "vitest";
import {
  FilterBuilder,
  ValidationError,
  andFilters,
  escapeLikePattern,
  filterLiteral,
  quoteFilterString,
} from "../src/index.js";

describe("FilterBuilder", () => {
  it("renders the ticket example", () => {
    const f = FilterBuilder.create().eq("service.name", "x").in("severity_text", ["WARN", "ERROR"]);
    expect(f.build()).toBe("service.name = 'x' AND severity_text IN ('WARN', 'ERROR')");
    expect(String(f)).toBe(f.build());
  });

  it("renders every operator", () => {
    const f = new FilterBuilder()
      .ne("a", "b")
      .lt("n", 1)
      .lte("n", 2)
      .gt("n", 3)
      .gte("duration_nano", 4.5)
      .eq("ok", true)
      .eq("bad", false)
      .notIn("severity_number", [13, 17])
      .like("service.name", "sdk-%")
      .regexp("body", "prep-.*-abc")
      .exists("run.id")
      .notExists("missing")
      .text("needle")
      .notContains("body", "y");
    expect(f.build()).toBe(
      "a != 'b' AND n < 1 AND n <= 2 AND n > 3 AND duration_nano >= 4.5 AND ok = true AND bad = false" +
        " AND severity_number NOT IN (13, 17) AND service.name LIKE 'sdk-%' AND body REGEXP 'prep-.*-abc'" +
        " AND run.id EXISTS AND missing NOT EXISTS AND 'needle' AND NOT body CONTAINS 'y'",
    );
  });

  it("escapes strings and LIKE patterns (two layers for CONTAINS)", () => {
    expect(quoteFilterString("it's a back\\slash test")).toBe("'it\\'s a back\\\\slash test'");
    expect(escapeLikePattern("50%_off\\")).toBe("50\\%\\_off\\\\");
    // a literal backslash in CONTAINS needs four backslashes in the expression
    expect(new FilterBuilder().contains("body", "back\\slash").build()).toBe(
      "body CONTAINS 'back\\\\\\\\slash'",
    );
    expect(new FilterBuilder().contains("body", "prep_grpc").build()).toBe(
      "body CONTAINS 'prep\\\\_grpc'",
    );
  });

  it("groups with or / not / raw and stays immutable", () => {
    const base = new FilterBuilder().eq("service.name", "a");
    const grouped = base
      .or(new FilterBuilder().eq("x", 1), "y = 2", "", new FilterBuilder())
      .not("z = 3");
    expect(grouped.build()).toBe("service.name = 'a' AND ((x = 1) OR (y = 2)) AND NOT (z = 3)");
    expect(base.build()).toBe("service.name = 'a'");
    expect(base.or("only = 1").build()).toBe("service.name = 'a' AND (only = 1)");
    expect(base.or("", new FilterBuilder())).toBe(base);
    expect(base.not("  ")).toBe(base);
    expect(base.not(new FilterBuilder().eq("q", "w")).build()).toBe(
      "service.name = 'a' AND NOT (q = 'w')",
    );
    expect(base.raw("a = 1 OR b = 2").build()).toBe("service.name = 'a' AND (a = 1 OR b = 2)");
    expect(new FilterBuilder().raw(" has(body, 'x') ").build()).toBe("has(body, 'x')");
    expect(base.raw("")).toBe(base);
    expect(new FilterBuilder().isEmpty).toBe(true);
    expect(base.isEmpty).toBe(false);
  });

  it("rejects bad input with ValidationError", () => {
    expect(() => new FilterBuilder().eq("bad field", "x")).toThrow(ValidationError);
    expect(() => new FilterBuilder().in("a", [])).toThrow(ValidationError);
    expect(() => new FilterBuilder().where("a", "~" as never, 1)).toThrow(ValidationError);
    expect(() => filterLiteral(Number.NaN)).toThrow(ValidationError);
  });

  it("andFilters joins non-empty parts", () => {
    expect(andFilters()).toBe("");
    expect(andFilters(undefined, " ", "a = 1")).toBe("a = 1");
    expect(andFilters("a = 1 OR b = 2", new FilterBuilder().eq("c", 3))).toBe(
      "(a = 1 OR b = 2) AND (c = 3)",
    );
  });
});
