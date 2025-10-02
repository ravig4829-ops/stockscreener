package com.ravi.stockscreener.util;


import org.springframework.http.HttpHeaders;

public class HttpResponseException extends RuntimeException {
    private final int status;
    private final HttpHeaders headers;

    public HttpResponseException(int status, HttpHeaders headers, String body) {
        super("HTTP " + status + (body != null ? ": " + (body.length() > 200 ? body.substring(0, 200) + "..." : body) : ""));
        this.status = status;
        this.headers = headers;
    }

    public int status() {
        return status;
    }

    public HttpHeaders headers() {
        return headers;
    }
}
