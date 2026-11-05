-- Register the glbk date UDFs in StarRocks with the same names and arguments as on Hive.
-- Prerequisites:
--   1. enable_udf = true in fe.conf (restart FE).
--   2. Build: mvn -B package  ->  target/glbk-hive-udf-compat-1.0.0.jar
--   3. Put the jar on an HTTP server reachable from every FE and BE, and replace ${JAR_URL} below.
--      Optionally add "md5" = "<md5sum of the jar>" to each PROPERTIES block.
-- GLOBAL makes them callable from every database (Hive's CREATE TEMPORARY FUNCTION was per session).

CREATE GLOBAL FUNCTION gl_date_delta(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateDeltaHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_diff(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateDiffHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_format(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateFormatHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_how_many_day(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlHowManyDayHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_first(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateFirstHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_date_last(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlDateLastHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

-- gl_now needs two registrations: a varargs UDF needs at least one fixed argument.
CREATE GLOBAL FUNCTION gl_now() RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlNowNoArgHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

CREATE GLOBAL FUNCTION gl_now(string, ...) RETURNS string
PROPERTIES ("symbol" = "com.glbk.starrocks.udf.hive.GlNowHive", "type" = "StarrocksJar", "file" = "${JAR_URL}");

-- Smoke test (expected values from the original README; gl_now depends on the current date):
-- SELECT gl_date_delta('2018-09-06','y=+1,m=1,d=1,d=-1');   -- 2018-12-31
-- SELECT gl_date_diff('20180101','20180201','day');         -- -31
-- SELECT gl_date_format('20180102030405','yyyy-MM-dd');     -- 2018-01-02
-- SELECT gl_how_many_day('20190417','year');                -- 107
-- SELECT gl_date_first('20190417','week');                  -- 20190414
-- SELECT gl_date_last('20190417','quarter');                -- 20190630
-- SELECT gl_now(), gl_now('yyyy-MM-dd');
