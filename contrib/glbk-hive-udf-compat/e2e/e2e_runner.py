#!/usr/bin/env python3
"""Register the UDFs (from a register.sql template) in StarRocks and check a CSV of SQL -> expected results.

Markers in the expected column: NULL = SQL NULL, ERROR = the query must fail.
Only the first column of the first row is compared (as text).
"""
import argparse
import csv
import re
import sys

try:
    import pymysql
except ImportError:
    sys.exit("pymysql missing: pip install pymysql  (run_e2e.sh does this automatically)")


def connect(a, db=None):
    return pymysql.connect(host=a.host, port=a.port, user=a.user, password=a.password,
                           database=db, charset="utf8mb4", autocommit=True, connect_timeout=10, read_timeout=a.timeout)


def split_statements(text):
    lines = [l for l in text.splitlines() if not l.strip().startswith("--")]
    return [s.strip() for s in "\n".join(lines).split(";") if s.strip()]


def register(a):
    sql = open(a.register_sql, encoding="utf-8").read()
    sql = sql.replace("${JAR_URL}", a.jar_url)
    if a.md5:  # add "md5" to every PROPERTIES that has a "file"
        sql = re.sub(r'("file"\s*=\s*"[^"]*")', r'\1, "md5" = "%s"' % a.md5, sql)
    stmts = split_statements(sql)
    conn = connect(a)
    cur = conn.cursor()
    # drop first so re-runs work: derive DROP from each CREATE GLOBAL FUNCTION name(args)
    for s in stmts:
        m = re.match(r"CREATE\s+GLOBAL\s+FUNCTION\s+(\w+\s*\(.*?\))\s+RETURNS", s, re.I | re.S)
        if m:
            cur.execute("DROP GLOBAL FUNCTION IF EXISTS " + m.group(1))
    ok = True
    for s in stmts:
        try:
            cur.execute(s)
        except Exception as e:  # noqa
            ok = False
            print("REGISTER FAIL: %s\n    %s" % (s.splitlines()[0], e))
    cur.execute("SHOW GLOBAL FUNCTIONS")
    names = sorted(r[0] for r in cur.fetchall() if r[0].startswith("gl_"))
    print("registered %d statements, global functions: %s" % (len(stmts), ", ".join(names)))
    conn.close()
    return ok


def run_sql_file(a, path):
    conn = connect(a)
    cur = conn.cursor()
    for s in split_statements(open(path, encoding="utf-8").read()):
        cur.execute(s)
    conn.close()


def norm(v):
    return None if v is None else str(v)


def check(a):
    conn = connect(a, a.db)
    cur = conn.cursor()
    for kv in a.set_vars:
        cur.execute("SET " + kv)
    with open(a.csv, newline="", encoding="utf-8-sig") as f:
        rows = list(csv.DictReader(f))
    if not rows:
        sys.exit("empty csv: " + a.csv)
    for col in (a.sql_col, a.expected_col):
        if col not in rows[0]:
            sys.exit("column %r not in %s (columns: %s)" % (col, a.csv, list(rows[0])))
    passed = failed = skipped = 0
    for i, r in enumerate(rows, 2):  # line number in file (header = 1) for single-line rows
        sql = (r[a.sql_col] or "").strip().rstrip(";").strip()
        exp = r[a.expected_col]
        if not sql or (a.skip_blank_expected and (exp is None or exp == "")):
            skipped += 1
            continue
        if not re.match(r"(select|with|show|values|\()", sql, re.I):
            sql = "SELECT " + sql
        err = actual = None
        try:
            cur.execute(sql)
            row = cur.fetchone()
            actual = "<no rows>" if row is None else norm(row[0])
            if row is not None:
                cur.fetchall()
        except Exception as e:  # noqa
            err = e
        if exp == "ERROR":
            good, shown = err is not None, ("ERROR: %s" % str(err)[:120] if err else repr(actual))
        elif err is not None:
            good, shown = False, "ERROR: %s" % str(err)[:200]
        elif exp == "NULL":
            good, shown = actual is None, repr(actual)
        else:
            good, shown = actual == exp, repr(actual)
        if good:
            passed += 1
            if a.verbose:
                print("PASS  %s -> %s" % (sql, shown))
        else:
            failed += 1
            print("FAIL  row %d: %s\n      expected %r, got %s" % (i, sql, exp, shown))
    print("\n== %s: %d passed, %d failed, %d skipped (expected column %r) ==" % (a.csv, passed, failed, skipped, a.expected_col))
    conn.close()
    return failed == 0


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=9030)
    p.add_argument("--user", default="root")
    p.add_argument("--password", default="")
    p.add_argument("--timeout", type=int, default=120)
    p.add_argument("--db", default="glbk_e2e")
    p.add_argument("--register-sql")
    p.add_argument("--jar-url")
    p.add_argument("--md5")
    p.add_argument("--setup-sql", action="append", default=[], help="executed after registration, before checks")
    p.add_argument("--set", dest="set_vars", action="append", default=[], help='session variable for the checks, e.g. "enable_column_expr_predicate=false"')
    p.add_argument("--csv")
    p.add_argument("--sql-col", default="sql")
    p.add_argument("--expected-col", default="new_result")
    p.add_argument("--skip-blank-expected", action="store_true")
    p.add_argument("--verbose", "-v", action="store_true")
    a = p.parse_args()
    c = connect(a)
    c.cursor().execute("CREATE DATABASE IF NOT EXISTS " + a.db)
    c.close()
    ok = True
    if a.register_sql:
        ok = register(a)
        if not ok:
            print("registration failed; aborting")
            return 1
    for s in a.setup_sql:
        run_sql_file(a, s)
    if a.csv:
        ok = check(a) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
