// Test-only stub so the original Hive UDF sources compile without hive-exec.
package org.apache.hadoop.hive.ql.exec;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@Retention(RetentionPolicy.RUNTIME)
public @interface Description {
    String name();

    String value() default "";

    String extended() default "";
}
