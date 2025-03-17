/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.apm.plugin.undertow.v2x.handler;

import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import org.apache.skywalking.apm.agent.core.context.CarrierItem;
import org.apache.skywalking.apm.agent.core.context.ContextCarrier;
import org.apache.skywalking.apm.agent.core.context.ContextManager;
import org.apache.skywalking.apm.agent.core.context.tag.StringTag;
import org.apache.skywalking.apm.agent.core.context.tag.Tags;
import org.apache.skywalking.apm.agent.core.context.trace.AbstractSpan;
import org.apache.skywalking.apm.agent.core.context.trace.SpanLayer;
import org.apache.skywalking.apm.network.trace.component.ComponentsDefine;
import org.apache.skywalking.apm.plugin.undertow.v2x.Constants;

public class TracingHandler implements HttpHandler {

    public static final StringTag CLIENT_IP = new StringTag(10, "http.client_ip");
    
    private final String template;
    private final HttpHandler next;

    public TracingHandler(HttpHandler handler) {
        this(null, handler);
    }

    public TracingHandler(String template, HttpHandler handler) {
        this.next = handler;
        this.template = template;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        final HeaderMap headers = exchange.getRequestHeaders();
        final ContextCarrier carrier = new ContextCarrier();
        CarrierItem items = carrier.items();
        while (items.hasNext()) {
            items = items.next();
            items.setHeadValue(headers.getFirst(items.getHeadKey()));
        }
        String operationName;
        if (null == template) {
            operationName = exchange.getRequestPath();
        } else {
            operationName = template;
        }
        final AbstractSpan span = ContextManager.createEntrySpan(exchange.getRequestMethod() + ":" + operationName, carrier);
        Tags.URL.set(span, exchange.getRequestURL());
        Tags.HTTP.METHOD.set(span, exchange.getRequestMethod().toString());
        span.setComponent(ComponentsDefine.UNDERTOW);
        SpanLayer.asHttp(span);
        try {
            CLIENT_IP.set(span, getClientIp(exchange));
            exchange.getResponseHeaders()
                    .add(new HttpString("traceId"), ContextManager.getGlobalTraceId());
            
            span.prepareForAsync();
            exchange.addExchangeCompleteListener(new ExchangeCompletionListener() {
                @Override
                public void exchangeEvent(HttpServerExchange httpServerExchange, NextListener nextListener) {
                    nextListener.proceed();
                    Tags.HTTP_RESPONSE_STATUS_CODE.set(span, httpServerExchange.getStatusCode());
                    if (httpServerExchange.getStatusCode() >= 400) {
                        span.errorOccurred();
                    }
                    span.asyncFinish();
                }
            });
        } catch (Throwable e) {
            ContextManager.activeSpan().log(e);
        }
        try {
            next.handleRequest(exchange);
        } catch (Throwable e) {
            span.log(e);
        } finally {
            ContextManager.stopSpan(span);
            ContextManager.getRuntimeContext().remove(Constants.FORWARD_REQUEST_FLAG);
        }
    }

    private static String getClientIp(HttpServerExchange exchange) {
        String[] headers = {"X-Forwarded-For", "Proxy-Client-IP",
                "WL-Proxy-Client-IP", "HTTP_CLIENT_IP",
                "HTTP_X_FORWARDED_FOR"};
        String ip = null;
        for (String header : headers) {
            ip = exchange.getRequestHeaders().getFirst(header);
            if (isValidIp(ip)) {
                break; 
            }
        }

        if (!isValidIp(ip)) {
            ip = exchange.getSourceAddress().getAddress().getHostAddress();
        }

        return splitFirstValidIp(ip);
    }
    
    private static boolean isValidIp(String ip) {
        return ip != null && !ip.isEmpty()
                && !"unknown".equalsIgnoreCase(ip);
    }

    private static String splitFirstValidIp(String ip) {
        if (ip != null && ip.contains(",")) {
            String[] ips = ip.split(",");
            for (String subIp : ips) {
                if (isValidIp(subIp.trim())) {
                    return subIp.trim();
                }
            }
        }
        return ip;
    }

}
