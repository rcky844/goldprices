package com.goldmonitor.http;

/** Minimal HTTP response used by both the legacy and the modern transport. */
public final class HttpResponse {

    public final int status;
    public final byte[] body;
    public final String contentType;
    public final String location;

    public HttpResponse(int status, byte[] body, String contentType, String location) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
        this.contentType = contentType;
        this.location = location;
    }

    public String bodyAsString() {
        try {
            return new String(body, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return new String(body);
        }
    }
}
