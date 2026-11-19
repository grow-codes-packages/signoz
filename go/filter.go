package signoz

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
)

// fieldNameRE is the set of field names FilterBuilder accepts.
var fieldNameRE = regexp.MustCompile(`^[A-Za-z0-9_.:\-]+$`)

// FilterOperator is a comparison operator for FilterBuilder.Where.
type FilterOperator string

// Comparison operators.
const (
	OpEq  FilterOperator = "="
	OpNe  FilterOperator = "!="
	OpLt  FilterOperator = "<"
	OpLte FilterOperator = "<="
	OpGt  FilterOperator = ">"
	OpGte FilterOperator = ">="
)

// orRE detects a top-level-looking OR in raw expressions.
var orRE = regexp.MustCompile(`(?i)\bOR\b`)

// FilterBuilder renders SigNoz filter expressions (query builder v5 search
// syntax), for example
//
//	signoz.NewFilter().Eq("service.name", "checkout").In("severity_text", "ERROR", "FATAL").String()
//	// service.name = 'checkout' AND severity_text IN ('ERROR', 'FATAL')
//
// Conditions are joined with AND; use Or and Not to group. Values are quoted
// and escaped; Contains escapes LIKE wildcards so the text matches literally.
// A FilterBuilder is immutable: every method returns a new builder, so a base
// filter can be reused. Invalid input (a bad field name, an empty IN list, a
// non-finite number) is reported by Build.
type FilterBuilder struct {
	parts []string
	err   error
}

// NewFilter returns an empty filter (matches everything).
func NewFilter() *FilterBuilder { return &FilterBuilder{} }

func (f *FilterBuilder) with(expr string, err error) *FilterBuilder {
	n := &FilterBuilder{parts: append(append([]string(nil), f.parts...), expr), err: f.err}
	if expr == "" {
		n.parts = n.parts[:len(n.parts)-1]
	}
	if n.err == nil && err != nil {
		n.err = err
	}
	return n
}

func (f *FilterBuilder) fail(err error) *FilterBuilder { return f.with("", err) }

func checkField(name string) error {
	if !fieldNameRE.MatchString(name) {
		return ValidationError("filter", fmt.Sprintf("invalid field name %q", name))
	}
	return nil
}

// cond adds "<field> <rest>" after validating field.
func (f *FilterBuilder) cond(prefix, field, rest string, err error) *FilterBuilder {
	if ferr := checkField(field); ferr != nil {
		return f.fail(ferr)
	}
	if err != nil {
		return f.fail(err)
	}
	return f.with(prefix+field+" "+rest, nil)
}

// Where adds "field <op> value" for one of the comparison operators.
func (f *FilterBuilder) Where(field string, op FilterOperator, value any) *FilterBuilder {
	switch op {
	case OpEq, OpNe, OpLt, OpLte, OpGt, OpGte:
	default:
		return f.fail(ValidationError("filter", fmt.Sprintf("unknown operator %q", string(op))))
	}
	lit, err := literal(value)
	return f.cond("", field, string(op)+" "+lit, err)
}

// Eq adds "field = value".
func (f *FilterBuilder) Eq(field string, value any) *FilterBuilder {
	return f.Where(field, OpEq, value)
}

// Ne adds "field != value".
func (f *FilterBuilder) Ne(field string, value any) *FilterBuilder {
	return f.Where(field, OpNe, value)
}

// Gt adds "field > value".
func (f *FilterBuilder) Gt(field string, value any) *FilterBuilder {
	return f.Where(field, OpGt, value)
}

// Gte adds "field >= value".
func (f *FilterBuilder) Gte(field string, value any) *FilterBuilder {
	return f.Where(field, OpGte, value)
}

// Lt adds "field < value".
func (f *FilterBuilder) Lt(field string, value any) *FilterBuilder {
	return f.Where(field, OpLt, value)
}

// Lte adds "field <= value".
func (f *FilterBuilder) Lte(field string, value any) *FilterBuilder {
	return f.Where(field, OpLte, value)
}

func list(values []any) (string, error) {
	if len(values) == 0 {
		return "", ValidationError("filter", "IN lists must not be empty")
	}
	q := make([]string, len(values))
	for i, v := range values {
		lit, err := literal(v)
		if err != nil {
			return "", err
		}
		q[i] = lit
	}
	return "(" + strings.Join(q, ", ") + ")", nil
}

// In adds "field IN (v1, v2, ...)" (at least one value).
func (f *FilterBuilder) In(field string, values ...any) *FilterBuilder {
	l, err := list(values)
	return f.cond("", field, "IN "+l, err)
}

// NotIn adds "field NOT IN (v1, v2, ...)" (at least one value).
func (f *FilterBuilder) NotIn(field string, values ...any) *FilterBuilder {
	l, err := list(values)
	return f.cond("", field, "NOT IN "+l, err)
}

// Contains adds "field CONTAINS 'text'" matching text literally
// (case-insensitive; LIKE wildcards % and _ in text are escaped).
func (f *FilterBuilder) Contains(field, text string) *FilterBuilder {
	return f.cond("", field, "CONTAINS "+QuoteFilterString(EscapeLikePattern(text)), nil)
}

// NotContains adds "NOT field CONTAINS 'text'" (text matched literally).
func (f *FilterBuilder) NotContains(field, text string) *FilterBuilder {
	return f.cond("NOT ", field, "CONTAINS "+QuoteFilterString(EscapeLikePattern(text)), nil)
}

// Like adds "field LIKE 'pattern'"; % and _ in pattern are wildcards.
func (f *FilterBuilder) Like(field, pattern string) *FilterBuilder {
	return f.cond("", field, "LIKE "+QuoteFilterString(pattern), nil)
}

// Regexp adds "field REGEXP 'pattern'" (RE2 syntax, evaluated by ClickHouse).
func (f *FilterBuilder) Regexp(field, pattern string) *FilterBuilder {
	return f.cond("", field, "REGEXP "+QuoteFilterString(pattern), nil)
}

// Exists adds "field EXISTS".
func (f *FilterBuilder) Exists(field string) *FilterBuilder {
	return f.cond("", field, "EXISTS", nil)
}

// NotExists adds "field NOT EXISTS".
func (f *FilterBuilder) NotExists(field string) *FilterBuilder {
	return f.cond("", field, "NOT EXISTS", nil)
}

// Text adds a quoted full-text term that searches the log body.
func (f *FilterBuilder) Text(value string) *FilterBuilder {
	return f.with(QuoteFilterString(value), nil)
}

// Raw adds expr unchanged; it is parenthesised when it contains OR.
func (f *FilterBuilder) Raw(expr string) *FilterBuilder {
	t := strings.TrimSpace(expr)
	if t == "" {
		return f
	}
	if orRE.MatchString(t) {
		t = "(" + t + ")"
	}
	return f.with(t, nil)
}

// Or adds "((a) OR (b) ...)" built from the non-empty alternatives ("(a)" for
// a single one).
func (f *FilterBuilder) Or(alternatives ...*FilterBuilder) *FilterBuilder {
	var parts []string
	var err error
	for _, a := range alternatives {
		if a == nil {
			continue
		}
		if a.err != nil && err == nil {
			err = a.err
		}
		if s := a.String(); s != "" {
			parts = append(parts, s)
		}
	}
	switch len(parts) {
	case 0:
		return f.with("", err)
	case 1:
		return f.with("("+parts[0]+")", err)
	}
	return f.with("(("+strings.Join(parts, ") OR (")+"))", err)
}

// Not adds "NOT (g)".
func (f *FilterBuilder) Not(g *FilterBuilder) *FilterBuilder {
	if g == nil || g.String() == "" {
		if g != nil {
			return f.with("", g.err)
		}
		return f
	}
	return f.with("NOT ("+g.String()+")", g.err)
}

// IsEmpty reports whether no condition was added.
func (f *FilterBuilder) IsEmpty() bool { return len(f.parts) == 0 }

// String renders the expression ("" when empty). It ignores validation errors;
// use Build to see them.
func (f *FilterBuilder) String() string { return strings.Join(f.parts, " AND ") }

// Build renders the expression or returns the first VALIDATION error.
func (f *FilterBuilder) Build() (string, error) {
	if f.err != nil {
		return "", f.err
	}
	return f.String(), nil
}

// AndFilters joins non-empty expressions with AND, parenthesising each when
// there is more than one.
func AndFilters(parts ...string) string {
	var out []string
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	if len(out) <= 1 {
		return strings.Join(out, "")
	}
	return "(" + strings.Join(out, ") AND (") + ")"
}

// QuoteFilterString quotes s in single quotes with \ and ' escaped.
func QuoteFilterString(s string) string {
	return "'" + strings.NewReplacer(`\`, `\\`, `'`, `\'`).Replace(s) + "'"
}

// FilterLiteral renders a filter value: strings quoted (QuoteFilterString),
// booleans true/false, numbers bare. Non-finite floats are VALIDATION errors;
// other types are rendered with fmt and quoted.
func FilterLiteral(v any) (string, error) { return literal(v) }

func literal(v any) (string, error) {
	switch x := v.(type) {
	case string:
		return QuoteFilterString(x), nil
	case bool:
		return strconv.FormatBool(x), nil
	case int, int8, int16, int32, int64, uint, uint8, uint16, uint32, uint64:
		return fmt.Sprint(x), nil
	case float32:
		return literal(float64(x))
	case float64:
		if math.IsNaN(x) || math.IsInf(x, 0) {
			return "", ValidationError("filter", "numbers must be finite")
		}
		return strconv.FormatFloat(x, 'f', -1, 64), nil
	case fmt.Stringer:
		return QuoteFilterString(x.String()), nil
	}
	return QuoteFilterString(fmt.Sprint(v)), nil
}

// EscapeLikePattern escapes \, % and _ so text matches literally inside
// CONTAINS or LIKE (quote it afterwards).
func EscapeLikePattern(text string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(text)
}
