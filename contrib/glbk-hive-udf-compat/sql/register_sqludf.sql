-- EXPERIMENTAL (StarRocks 4.1+): gl_* as SQL UDFs that expand into built-in expressions, with the Java UDF
-- as fallback. Common calls with constant parameters (input in the 4 standard shapes, the 4 standard output
-- patterns, known dtype/interval, one-item offsets such as '+3' / 'day=+3' / 'month=-1') are folded by the FE
-- into pure built-in expressions and never cross JNI. Everything else (multi-item offsets, set-values, custom
-- patterns, unknown/NULL parameters that must raise an error) is routed to the Java production UDF gl_*_jni.
-- Same semantics as sql/register.sql's production set; verified with samples/samples.csv.
--
-- Requires the Java jar too (see sql/register.sql for prerequisites). gl_now stays a Java UDF: it must use the
-- BE JVM time zone, while now() uses the session time_zone.

-- ---------- Java fallback (same classes as the production set) ----------
CREATE GLOBAL FUNCTION gl_date_delta_jni(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateDelta", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_date_diff_jni(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateDiff", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_date_format_jni(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateFormat", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_how_many_day_jni(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlHowManyDay", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_date_first_jni(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateFirst", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_date_last_jni(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateLast", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_now() RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlNowNoArg", "type" = "StarrocksJar", "file" = "${JAR_URL}");
CREATE GLOBAL FUNCTION gl_now(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlNow", "type" = "StarrocksJar", "file" = "${JAR_URL}");

-- ---------- helpers ----------
-- input digits after removing ' ', '-', ':'
CREATE GLOBAL FUNCTION glbk_norm(s STRING) RETURNS translate(s, ' -:', '');
-- strict parse of normalized digits: 8 or 14 digits, a real date/time, year >= 1; otherwise NULL.
-- Written without branches that would be skipped anyway (CASE/IF evaluate every branch on every row):
-- translate() checks "digits only" (much cheaper than regexp), str_to_date() is strict (rejects 2019-02-29,
-- month 13, hour 24) and year 0000 is excluded explicitly.
CREATE GLOBAL FUNCTION glbk_digits(n STRING) RETURNS substr(n, 1, 4) <> '0000' AND translate(n, '0123456789', '') = '';
CREATE GLOBAL FUNCTION glbk_parse_n(n STRING) RETURNS
  if((length(n) = 8 OR length(n) = 14) AND glbk_digits(n),
     str_to_date(n, if(length(n) = 8, '%Y%m%d', '%Y%m%d%H%i%s')), NULL);
CREATE GLOBAL FUNCTION glbk_parse(s STRING) RETURNS glbk_parse_n(glbk_norm(s));
-- MySQL pattern for the 4 standard Java patterns, NULL for anything else (foldable for constants)
CREATE GLOBAL FUNCTION glbk_std_fmt(f STRING) RETURNS
  CASE f WHEN 'yyyyMMdd' THEN '%Y%m%d' WHEN 'yyyy-MM-dd' THEN '%Y-%m-%d'
         WHEN 'yyyyMMddHHmmss' THEN '%Y%m%d%H%i%s' WHEN 'yyyy-MM-dd HH:mm:ss' THEN '%Y-%m-%d %H:%i:%s' END;
-- default output style of gl_date_delta: same shape as the input
CREATE GLOBAL FUNCTION glbk_style(s STRING) RETURNS
  if(length(glbk_norm(s)) = 8,
     if(length(glbk_norm(s)) <> length(s), '%Y-%m-%d', '%Y%m%d'),
     if(length(glbk_norm(s)) <> length(s), '%Y-%m-%d %H:%i:%s', '%Y%m%d%H%i%s'));
-- one-item offsets that built-ins handle: '' -> NONE, '+3'/'-3' -> DAY, 'unit=+N' / 'unit=-N' -> unit
CREATE GLOBAL FUNCTION glbk_delta_kind(o STRING) RETURNS
  CASE
    WHEN o = '' THEN 'NONE'
    WHEN regexp_replace(o, '^[+-][0-9]{1,6}$', '') = '' THEN 'DAY'
    WHEN regexp_replace(o, '^(day|d)=[+-][0-9]{1,6}$', '') = '' THEN 'DAY'
    WHEN regexp_replace(o, '^(month|m)=[+-][0-9]{1,5}$', '') = '' THEN 'MONTH'
    WHEN regexp_replace(o, '^(year|y)=[+-][0-9]{1,4}$', '') = '' THEN 'YEAR'
    WHEN regexp_replace(o, '^(hour|h)=[+-][0-9]{1,7}$', '') = '' THEN 'HOUR'
    WHEN regexp_replace(o, '^(minute|n)=[+-][0-9]{1,9}$', '') = '' THEN 'MINUTE'
    WHEN regexp_replace(o, '^(second|s)=[+-][0-9]{1,9}$', '') = '' THEN 'SECOND'
    ELSE 'JNI'
  END;
CREATE GLOBAL FUNCTION glbk_delta_n(o STRING) RETURNS cast(regexp_replace(o, '^[a-z]+=', '') AS INT);
-- result year must stay in 1..9999
CREATE GLOBAL FUNCTION glbk_in_range(t DATETIME) RETURNS if(year(t) BETWEEN 1 AND 9999, t, NULL);
CREATE GLOBAL FUNCTION glbk_sunday(t DATETIME) RETURNS days_sub(date_trunc('day', t), dayofweek(t) - 1);

-- ---------- gl_date_format ----------
CREATE GLOBAL FUNCTION gl_date_format(d STRING) RETURNS date_format(glbk_parse(d), '%Y%m%d');
CREATE GLOBAL FUNCTION gl_date_format(d STRING, f STRING) RETURNS
  if(glbk_std_fmt(f) IS NOT NULL, date_format(glbk_parse(d), glbk_std_fmt(f)), gl_date_format_jni(d, f));

-- ---------- gl_date_delta ----------
CREATE GLOBAL FUNCTION gl_date_delta(d STRING) RETURNS d;
CREATE GLOBAL FUNCTION glbk_delta_t(d STRING, o STRING) RETURNS
  CASE glbk_delta_kind(o)
    WHEN 'NONE' THEN glbk_parse(d)
    WHEN 'DAY' THEN glbk_in_range(days_add(glbk_parse(d), glbk_delta_n(o)))
    WHEN 'MONTH' THEN glbk_in_range(months_add(glbk_parse(d), glbk_delta_n(o)))
    WHEN 'YEAR' THEN glbk_in_range(years_add(glbk_parse(d), glbk_delta_n(o)))
    WHEN 'HOUR' THEN glbk_in_range(hours_add(glbk_parse(d), glbk_delta_n(o)))
    WHEN 'MINUTE' THEN glbk_in_range(minutes_add(glbk_parse(d), glbk_delta_n(o)))
    WHEN 'SECOND' THEN glbk_in_range(seconds_add(glbk_parse(d), glbk_delta_n(o)))
  END;
CREATE GLOBAL FUNCTION gl_date_delta(d STRING, o STRING) RETURNS
  if(glbk_delta_kind(coalesce(o, '')) <> 'JNI',
     date_format(glbk_delta_t(d, coalesce(o, '')), glbk_style(d)),
     gl_date_delta_jni(d, o));
CREATE GLOBAL FUNCTION gl_date_delta(d STRING, o STRING, f STRING) RETURNS
  if(glbk_delta_kind(coalesce(o, '')) <> 'JNI' AND (f = '' OR glbk_std_fmt(f) IS NOT NULL),
     date_format(glbk_delta_t(d, coalesce(o, '')), if(f = '', glbk_style(d), glbk_std_fmt(f))),
     gl_date_delta_jni(d, o, f));

-- ---------- dtype functions ----------
CREATE GLOBAL FUNCTION gl_date_first(d STRING, t STRING) RETURNS
  CASE t
    WHEN 'year' THEN date_format(glbk_parse(d), '%Y0101')
    WHEN 'y' THEN date_format(glbk_parse(d), '%Y0101')
    WHEN 'quarter' THEN date_format(date_trunc('quarter', glbk_parse(d)), '%Y%m%d')
    WHEN 'q' THEN date_format(date_trunc('quarter', glbk_parse(d)), '%Y%m%d')
    WHEN 'month' THEN date_format(glbk_parse(d), '%Y%m01')
    WHEN 'm' THEN date_format(glbk_parse(d), '%Y%m01')
    WHEN 'week' THEN date_format(glbk_in_range(glbk_sunday(glbk_parse(d))), '%Y%m%d')
    WHEN 'w' THEN date_format(glbk_in_range(glbk_sunday(glbk_parse(d))), '%Y%m%d')
    ELSE gl_date_first_jni(d, t)
  END;
CREATE GLOBAL FUNCTION gl_date_last(d STRING, t STRING) RETURNS
  CASE t
    WHEN 'year' THEN date_format(glbk_parse(d), '%Y1231')
    WHEN 'y' THEN date_format(glbk_parse(d), '%Y1231')
    WHEN 'quarter' THEN date_format(last_day(months_add(date_trunc('quarter', glbk_parse(d)), 2)), '%Y%m%d')
    WHEN 'q' THEN date_format(last_day(months_add(date_trunc('quarter', glbk_parse(d)), 2)), '%Y%m%d')
    WHEN 'month' THEN date_format(last_day(glbk_parse(d)), '%Y%m%d')
    WHEN 'm' THEN date_format(last_day(glbk_parse(d)), '%Y%m%d')
    WHEN 'week' THEN date_format(glbk_in_range(days_add(glbk_sunday(glbk_parse(d)), 6)), '%Y%m%d')
    WHEN 'w' THEN date_format(glbk_in_range(days_add(glbk_sunday(glbk_parse(d)), 6)), '%Y%m%d')
    ELSE gl_date_last_jni(d, t)
  END;
CREATE GLOBAL FUNCTION gl_how_many_day(d STRING, t STRING) RETURNS
  CASE t
    WHEN 'year' THEN cast(dayofyear(glbk_parse(d)) AS STRING)
    WHEN 'y' THEN cast(dayofyear(glbk_parse(d)) AS STRING)
    WHEN 'quarter' THEN cast(datediff(glbk_parse(d), date_trunc('quarter', glbk_parse(d))) + 1 AS STRING)
    WHEN 'q' THEN cast(datediff(glbk_parse(d), date_trunc('quarter', glbk_parse(d))) + 1 AS STRING)
    WHEN 'month' THEN cast(dayofmonth(glbk_parse(d)) AS STRING)
    WHEN 'm' THEN cast(dayofmonth(glbk_parse(d)) AS STRING)
    WHEN 'week' THEN cast(dayofweek(glbk_parse(d)) AS STRING)
    WHEN 'w' THEN cast(dayofweek(glbk_parse(d)) AS STRING)
    ELSE gl_how_many_day_jni(d, t)
  END;

-- ---------- gl_date_diff ----------
CREATE GLOBAL FUNCTION glbk_diff(a DATETIME, b DATETIME, i STRING) RETURNS
  CASE i
    WHEN 'year' THEN year(a) - year(b)
    WHEN 'y' THEN year(a) - year(b)
    WHEN 'quarter' THEN (year(a) * 4 + quarter(a)) - (year(b) * 4 + quarter(b))
    WHEN 'q' THEN (year(a) * 4 + quarter(a)) - (year(b) * 4 + quarter(b))
    WHEN 'month' THEN (year(a) * 12 + month(a)) - (year(b) * 12 + month(b))
    WHEN 'm' THEN (year(a) * 12 + month(a)) - (year(b) * 12 + month(b))
    WHEN 'week' THEN datediff(glbk_sunday(a), glbk_sunday(b)) DIV 7
    WHEN 'w' THEN datediff(glbk_sunday(a), glbk_sunday(b)) DIV 7
    WHEN 'day' THEN datediff(a, b)
    WHEN 'd' THEN datediff(a, b)
    WHEN 'hour' THEN hours_diff(date_trunc('hour', a), date_trunc('hour', b))
    WHEN 'h' THEN hours_diff(date_trunc('hour', a), date_trunc('hour', b))
    WHEN 'minute' THEN minutes_diff(date_trunc('minute', a), date_trunc('minute', b))
    WHEN 'n' THEN minutes_diff(date_trunc('minute', a), date_trunc('minute', b))
    WHEN 'second' THEN seconds_diff(a, b)
    WHEN 's' THEN seconds_diff(a, b)
  END;
CREATE GLOBAL FUNCTION gl_date_diff(a STRING, b STRING) RETURNS
  cast(datediff(glbk_parse(a), glbk_parse(b)) AS STRING);
CREATE GLOBAL FUNCTION gl_date_diff(a STRING, b STRING, i STRING) RETURNS
  if(i IN ('year', 'y', 'quarter', 'q', 'month', 'm', 'week', 'w', 'day', 'd', 'hour', 'h', 'minute', 'n',
           'second', 's'),
     cast(glbk_diff(glbk_parse(a), glbk_parse(b), i) AS STRING),
     gl_date_diff_jni(a, b, i));

-- ---------- wrong arity -> NULL, like the original (SQL UDFs have a fixed arity; 1..5 args covered) ----------
CREATE GLOBAL FUNCTION gl_date_delta(a1 STRING, a2 STRING, a3 STRING, a4 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_delta(a1 STRING, a2 STRING, a3 STRING, a4 STRING, a5 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4, a5), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_diff(a1 STRING) RETURNS cast(if(false, concat(a1), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_diff(a1 STRING, a2 STRING, a3 STRING, a4 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_diff(a1 STRING, a2 STRING, a3 STRING, a4 STRING, a5 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4, a5), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_format(a1 STRING, a2 STRING, a3 STRING) RETURNS cast(if(false, concat(a1, a2, a3), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_format(a1 STRING, a2 STRING, a3 STRING, a4 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_format(a1 STRING, a2 STRING, a3 STRING, a4 STRING, a5 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4, a5), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_how_many_day(a1 STRING) RETURNS cast(if(false, concat(a1), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_how_many_day(a1 STRING, a2 STRING, a3 STRING) RETURNS cast(if(false, concat(a1, a2, a3), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_how_many_day(a1 STRING, a2 STRING, a3 STRING, a4 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_how_many_day(a1 STRING, a2 STRING, a3 STRING, a4 STRING, a5 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4, a5), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_first(a1 STRING) RETURNS cast(if(false, concat(a1), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_first(a1 STRING, a2 STRING, a3 STRING) RETURNS cast(if(false, concat(a1, a2, a3), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_first(a1 STRING, a2 STRING, a3 STRING, a4 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_first(a1 STRING, a2 STRING, a3 STRING, a4 STRING, a5 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4, a5), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_last(a1 STRING) RETURNS cast(if(false, concat(a1), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_last(a1 STRING, a2 STRING, a3 STRING) RETURNS cast(if(false, concat(a1, a2, a3), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_last(a1 STRING, a2 STRING, a3 STRING, a4 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4), NULL) AS STRING);
CREATE GLOBAL FUNCTION gl_date_last(a1 STRING, a2 STRING, a3 STRING, a4 STRING, a5 STRING) RETURNS cast(if(false, concat(a1, a2, a3, a4, a5), NULL) AS STRING);
