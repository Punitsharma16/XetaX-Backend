package com.xetax.crm.meta;

/** A Meta-side failure with a message that is safe to show the user. */
public class MetaApiException extends RuntimeException {
    public MetaApiException(String message) {
        super(message);
    }
}
