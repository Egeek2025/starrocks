// Test-only stub of Hadoop's Text with the two behaviours the original UDFs depend on:
// toString() returns the value, and set(null) throws NullPointerException (Hadoop's
// Text.set(String) encodes the string and fails on null).
package org.apache.hadoop.io;

public class Text {
    private String value = "";

    public Text() {
    }

    public Text(String value) {
        set(value);
    }

    public void set(String value) {
        if (value == null) {
            throw new NullPointerException();
        }
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
