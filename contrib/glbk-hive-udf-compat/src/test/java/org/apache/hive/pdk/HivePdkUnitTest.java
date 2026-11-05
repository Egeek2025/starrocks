// Test-only stub so the original Hive UDF sources compile without hive-pdk.
package org.apache.hive.pdk;

public @interface HivePdkUnitTest {
    String query();

    String result();
}
