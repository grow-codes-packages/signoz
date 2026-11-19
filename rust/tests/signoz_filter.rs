//! `FilterBuilder`: rendering, escaping and validation (same expressions as Go/TypeScript).

use grow_signoz as sdk;
use sdk::core::ErrorCode;
use sdk::{
    and_filters, escape_like_pattern, filter_literal, quote_filter_string, FilterBuilder,
    FilterOperator, FilterValue,
};

#[test]
fn comparisons_and_lists() {
    let f = FilterBuilder::new()
        .eq("service.name", "checkout")
        .ne("env", "dev")
        .gt("seq", 1)
        .gte("seq", 2i64)
        .lt("seq", 3u32)
        .lte("ratio", 0.5)
        .eq("ok", true)
        .in_list("severity_text", ["ERROR", "FATAL"])
        .not_in("severity_number", [13, 17])
        .where_op("k8s.pod:name", FilterOperator::Eq, String::from("p-1"));
    assert_eq!(
        f.build().unwrap(),
        "service.name = 'checkout' AND env != 'dev' AND seq > 1 AND seq >= 2 AND seq < 3 AND \
         ratio <= 0.5 AND ok = true AND severity_text IN ('ERROR', 'FATAL') AND \
         severity_number NOT IN (13, 17) AND k8s.pod:name = 'p-1'"
    );
    assert_eq!(f.to_string(), f.expression());
    assert_eq!(String::from(f.clone()), f.expression());
    assert!(!f.is_empty());
    assert!(FilterBuilder::new().is_empty());
    assert_eq!(FilterBuilder::new().build().unwrap(), "");
}

#[test]
fn text_operators_escape() {
    let f = FilterBuilder::new()
        .contains("body", "it's 50% a_b back\\slash")
        .not_contains("body", "x")
        .like("service.name", "sdk-%")
        .regexp("body", "^tail-x?")
        .exists("run.id")
        .not_exists("seq")
        .text("logger 'quoted'");
    assert_eq!(
        f.expression(),
        r"body CONTAINS 'it\'s 50\\% a\\_b back\\\\slash' AND NOT body CONTAINS 'x' AND service.name LIKE 'sdk-%' AND body REGEXP '^tail-x?' AND run.id EXISTS AND seq NOT EXISTS AND 'logger \'quoted\''"
    );
}

#[test]
fn groups_and_raw() {
    let base = FilterBuilder::new().eq("service.name", "s");
    let f = base
        .clone()
        .or([
            FilterBuilder::new().eq("seq", 7),
            FilterBuilder::new().eq("seq", 8),
            FilterBuilder::new(),
        ])
        .not(FilterBuilder::new().exists("seq"))
        .raw("a = 1 or b = 2")
        .raw("  ")
        .raw("color = 'orange'");
    assert_eq!(
        f.expression(),
        "service.name = 's' AND ((seq = 7) OR (seq = 8)) AND NOT (seq EXISTS) AND (a = 1 or b = 2) AND color = 'orange'"
    );
    assert_eq!(
        base.clone()
            .or([FilterBuilder::new().eq("a", 1)])
            .expression(),
        "service.name = 's' AND (a = 1)"
    );
    assert_eq!(base.clone().or([]).expression(), "service.name = 's'");
    assert_eq!(
        base.clone().not(FilterBuilder::new()).expression(),
        "service.name = 's'"
    );
}

#[test]
fn validation_errors_surface_in_build() {
    let cases = [
        FilterBuilder::new().eq("bad field", 1),
        FilterBuilder::new().eq("", 1),
        FilterBuilder::new().in_list::<i32>("x", []),
        FilterBuilder::new().not_in("x", [f64::NAN]),
        FilterBuilder::new().gt("x", f64::INFINITY),
        FilterBuilder::new().exists("a'b"),
        FilterBuilder::new().or([FilterBuilder::new().eq("bad field", 1)]),
        FilterBuilder::new().not(FilterBuilder::new().eq("bad field", 1)),
    ];
    for c in cases {
        let e = c.build().unwrap_err();
        assert_eq!(e.code(), ErrorCode::Validation, "{c:?}");
    }
    // the first error wins and later conditions still render
    let f = FilterBuilder::new().eq("bad field", 1).eq("ok", 1);
    assert!(f.build().unwrap_err().message().contains("bad field"));
    assert_eq!(f.expression(), "ok = 1");
}

#[test]
fn helpers() {
    assert_eq!(quote_filter_string(r"it's \ok"), r"'it\'s \\ok'");
    assert_eq!(escape_like_pattern(r"50%_\"), r"50\%\_\\");
    assert_eq!(and_filters(["a = 1"]), "a = 1");
    assert_eq!(
        and_filters(["a = 1", " ", "b = 2 OR c = 3"]),
        "(a = 1) AND (b = 2 OR c = 3)"
    );
    assert_eq!(and_filters(Vec::<String>::new()), "");
    assert_eq!(filter_literal(&FilterValue::from(1.25f32)).unwrap(), "1.25");
    assert_eq!(
        filter_literal(&FilterValue::from(120_000_000.0)).unwrap(),
        "120000000"
    );
    assert_eq!(filter_literal(&FilterValue::from(7usize)).unwrap(), "7");
    assert_eq!(filter_literal(&FilterValue::from(-3i8)).unwrap(), "-3");
    assert_eq!(
        filter_literal(&FilterValue::from(&String::from("x"))).unwrap(),
        "'x'"
    );
    assert_eq!(filter_literal(&FilterValue::from(false)).unwrap(), "false");
    for op in [
        FilterOperator::Eq,
        FilterOperator::Ne,
        FilterOperator::Lt,
        FilterOperator::Lte,
        FilterOperator::Gt,
        FilterOperator::Gte,
    ] {
        assert!(!op.as_str().is_empty());
    }
    // ORDER is not an OR keyword
    assert_eq!(
        FilterBuilder::new()
            .raw("order = 1 AND color = 'x'")
            .expression(),
        "order = 1 AND color = 'x'"
    );
    assert_eq!(FilterBuilder::new().raw("OR").expression(), "(OR)");
}
