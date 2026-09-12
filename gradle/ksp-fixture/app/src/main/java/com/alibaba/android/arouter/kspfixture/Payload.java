package com.alibaba.android.arouter.kspfixture;

/** Deliberately neither Parcelable nor Serializable: injection must use TypeWrapper. */
public final class Payload {
    public final String value;
    public Payload(String value) { this.value = value; }
}
