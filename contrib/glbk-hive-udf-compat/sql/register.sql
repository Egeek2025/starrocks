-- Register the glbk date UDFs in StarRocks.
--   gl_*       : production set, original names and arguments, bugs fixed (see README "与 Hive 的差异").
--   gl_*_hive  : reconciliation set, bug-for-bug identical to the original Hive jar (Locale pinned to US).
--                Only for comparing old Hive results during migration; drop these after migration.
-- Prerequisites:
--   1. enable_udf = true in fe.conf (restart FE).
--   2. Build: mvn -B package  ->  target/glbk-hive-udf-compat-1.0.0.jar (class version must stay <= 17).
--   3. BE: add -Duser.timezone=Asia/Shanghai to JAVA_OPTS in be.conf (gl_now and the *_hive set use the JVM zone).
--   4. Put the jar on an HTTP server reachable from every FE and BE, and replace ${JAR_URL} below.
--      e2e/run_e2e.sh does steps 2 and 4 automatically and adds "md5".
-- A varargs UDF needs at least one fixed argument, so gl_now() / gl_now_hive() are registered separately.

-- ===== Production set =====

CREATE GLOBAL FUNCTION gl_date_delta(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateDelta", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_diff(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateDiff", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_format(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateFormat", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_how_many_day(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlHowManyDay", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_first(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateFirst", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_last(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlDateLast", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_now() RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlNowNoArg", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_now(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.GlNow", "type" = "StarrocksJar", "file" = "${JAR_URL}");

-- ===== Reconciliation set (bug-for-bug Hive) =====

CREATE GLOBAL FUNCTION gl_date_delta_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateDeltaHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_diff_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateDiffHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_format_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateFormatHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_how_many_day_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlHowManyDayHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_first_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateFirstHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_last_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateLastHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_now_hive() RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlNowNoArgHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_now_hive(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlNowHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");
