package com.documind.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP del cliente. Detrás del proxy (nginx) Tomcat ya resuelve la IP real a partir de
 * X-Forwarded-For ({@code server.forward-headers-strategy=native}) y solo confía en proxies de
 * redes internas, así que un cliente no puede falsearla enviando la cabecera.
 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
