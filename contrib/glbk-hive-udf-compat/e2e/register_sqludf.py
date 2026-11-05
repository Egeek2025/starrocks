#!/usr/bin/env python3
"""Register sql/register_sqludf.sql (StarRocks 4.1+): gl_* as SQL UDFs routing to built-ins, Java as gl_*_jni.

Drops every global gl_* / glbk_* function except the reconciliation set gl_*_hive first (the Java production
set from sql/register.sql would clash with the SQL UDF names), then executes the file with ${JAR_URL} replaced.
Usage: python3 register_sqludf.py JAR_URL [MD5]
Run ./run_e2e.sh <csv> first so the jar is built and served, or pass any reachable URL.
"""
import re
import sys

import pymysql

HERE = __file__.rsplit("/", 1)[0]
url = sys.argv[1]
md5 = sys.argv[2] if len(sys.argv) > 2 else None

con = pymysql.connect(host="127.0.0.1", port=9030, user="root", autocommit=True)
cur = con.cursor()
cur.execute("SHOW FULL GLOBAL FUNCTIONS")
for sig, *_ in cur.fetchall():
    name = sig.split("(", 1)[0]
    if (name.startswith("gl_") or name.startswith("glbk_")) and not name.endswith("_hive"):
        # SHOW prints VARCHAR without a length, which DROP does not accept; STRING is the same type
        cur.execute(f"DROP GLOBAL FUNCTION {re.sub(r'VARCHAR(?!\()', 'STRING', sig)}")

text = open(f"{HERE}/../sql/register_sqludf.sql", encoding="utf-8").read()
text = "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("--"))
props = f'"file" = "{url}"' + (f', "md5" = "{md5}"' if md5 else "")
text = text.replace('"file" = "${JAR_URL}"', props)
n = 0
for stmt in (s.strip() for s in text.split(";")):
    if stmt:
        try:
            cur.execute(stmt)
        except Exception as e:
            sys.exit(f"FAILED: {stmt[:200]}\n  -> {e}")
        n += 1
cur.execute("SHOW FULL GLOBAL FUNCTIONS")
names = sorted({re.sub(r"\(.*", "", r[0]) for r in cur.fetchall()})
print(f"registered {n} statements; global functions: {', '.join(names)}")
