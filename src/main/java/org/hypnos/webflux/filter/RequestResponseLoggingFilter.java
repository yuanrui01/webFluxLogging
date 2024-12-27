package org.hypnos.webflux.filter;

import org.hypnos.webflux.rewrite.LoggingCachedBodyOutputMessage;
import org.hypnos.webflux.support.LoggingBodyInserterContext;
import org.hypnos.webflux.utils.TimeUtil;
import org.hypnos.webflux.utils.TmpIpUtil;
import org.hypnos.webflux.vo.HttpAccLog;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ReactiveHttpOutputMessage;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.HandlerStrategies;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestResponseLoggingFilter implements WebFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestResponseLoggingFilter.class);

    private static final String LOG_START_TIME = "_LOG_START_TIME_";

    private static final int LIMIT_SIZE = 2000;

    private final List<HttpMessageReader<?>> messageReaders = HandlerStrategies.withDefaults().messageReaders();


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        long startTime = System.currentTimeMillis();
        exchange.getAttributes().put(LOG_START_TIME, startTime);

        ServerHttpRequest request = exchange.getRequest();
        String requestPath = request.getPath().pathWithinApplication().value();

        HttpAccLog httpAccLog = new HttpAccLog();
        httpAccLog.setMethod(request.getMethodValue());
        httpAccLog.setPath(requestPath);
        httpAccLog.setServerAddr(TmpIpUtil.getServerAddr());
        httpAccLog.setReqTime(TimeUtil.getFormatStrFromMillis(startTime));
        httpAccLog.setRemoteAddr(TmpIpUtil.getIp(request));
        MediaType mediaType = request.getHeaders().getContentType();

        if (mediaType == null || MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(mediaType) || MediaType.APPLICATION_JSON.isCompatibleWith(mediaType)) {
            return writeBodyLog(exchange, chain, httpAccLog);
        } else {
            return writeBasicLog(exchange, chain, httpAccLog);
        }
    }

    private Mono<Void> writeBasicLog(ServerWebExchange exchange, WebFilterChain chain, HttpAccLog accessLog) {
        StringBuilder builder = new StringBuilder();
        exchange.getRequest().getQueryParams().forEach((key, value)
                -> builder.append(key).append("=").append(String.join(",", value)));
        accessLog.setRequestBody(builder.toString());
        ServerHttpResponseDecorator decoratedResponse = recordResponseLog(exchange, accessLog);
        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }

    /**
     * 解决 request body 只能读取一次问题，
     * 参考: org.springframework.cloud.gateway.filter.factory.rewrite.ModifyRequestBodyGatewayFilterFactory
     */
    private Mono<Void> writeBodyLog(ServerWebExchange exchange, WebFilterChain chain, HttpAccLog httpAccLog) {
        ServerRequest serverRequest = ServerRequest.create(exchange, messageReaders);

        Mono<String> modifiedBody = serverRequest.bodyToMono(String.class)
                .flatMap(body ->{
                    httpAccLog.setRequestBody(body);
                    return Mono.just(body);
                });

        // 通过 BodyInserter 插入 body(支持修改body), 避免 request body 只能获取一次
        BodyInserter<Mono<String>, ReactiveHttpOutputMessage> bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        LoggingCachedBodyOutputMessage outputMessage = new LoggingCachedBodyOutputMessage(exchange, headers);

        return bodyInserter.insert(outputMessage, new LoggingBodyInserterContext())
                .then(Mono.defer(() -> {
                    ServerHttpRequest decoratedRequest = requestDecorate(exchange, headers, outputMessage);
                    ServerHttpResponseDecorator decoratedResponse = recordResponseLog(exchange, httpAccLog);
                    return chain.filter(exchange.mutate().request(decoratedRequest).response(decoratedResponse).build());
                }));
    }

    /**
     * 打印日志
     * @param httpAccLog 网关日志
     */
    private void writeAccessLog(ServerWebExchange exchange, HttpAccLog httpAccLog) {
        httpAccLog.setStatusCode(exchange.getResponse().getRawStatusCode());
        httpAccLog.setPathVariables(getPathParams(exchange));
        httpAccLog.setQueryParams(getQueryParams(exchange.getRequest().getQueryParams()));
        logger.info(httpAccLog.toString());
    }

    /**
     * 请求装饰器，重新计算 headers
     */
    private ServerHttpRequestDecorator requestDecorate(ServerWebExchange exchange, HttpHeaders headers,
                                                       LoggingCachedBodyOutputMessage outputMessage) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                long contentLength = headers.getContentLength();
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.putAll(super.getHeaders());
                if (contentLength > 0) {
                    httpHeaders.setContentLength(contentLength);
                } else {
                    httpHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
                }
                return httpHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return outputMessage.getBody();
            }
        };
    }

    /**
     * 记录响应日志
     * 通过 DataBufferFactory 解决响应体分段传输问题。
     */
    private ServerHttpResponseDecorator recordResponseLog(ServerWebExchange exchange, HttpAccLog httpAccLog) {
        ServerHttpResponse response = exchange.getResponse();
        DataBufferFactory bufferFactory = response.bufferFactory();

        return new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                long rspTime = System.currentTimeMillis();
                httpAccLog.setRspTime(TimeUtil.getFormatStrFromMillis(rspTime));
                httpAccLog.setProcessTime(rspTime - (long)exchange.getAttribute(LOG_START_TIME));

                // TODO: 粗暴写法，待优化
                String contentEncoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
                if (isTextResponse(response)) {
                    // 分流方式记录响应体日志
                    AtomicInteger loggedBytes = new AtomicInteger(0); // 记录已记录的字节数
                    byte[] content = new byte[LIMIT_SIZE];

                    Flux<DataBuffer> splitBody = Flux.from(body).concatMap(dataBuffer -> {
                        // 记录前 2000 字节内容
                        if (loggedBytes.get() < LIMIT_SIZE) {
                            // 复制当前 DataBuffer，确保原始流不受干扰
                            DataBuffer copyBuffer = bufferFactory.wrap(dataBuffer.asByteBuffer().asReadOnlyBuffer());
                            int readableBytes = dataBuffer.readableByteCount();
                            int bytesToLog = Math.min(LIMIT_SIZE - loggedBytes.get(), readableBytes);
                            dataBuffer.read(content, loggedBytes.get(), bytesToLog); // 将数据拷贝到 logged 数组
                            loggedBytes.addAndGet(bytesToLog);
                            return Mono.just(copyBuffer);
                        }
                        return Mono.just(dataBuffer);
                    });
                    return super.writeWith(splitBody)
                            .doFinally(signal -> {
                                httpAccLog.setResponseBody(processResponseContent(content, loggedBytes.get(), contentEncoding));
                                writeAccessLog(exchange, httpAccLog);
                            });
                }
                return super.writeWith(body).doFinally(signal -> writeAccessLog(exchange, httpAccLog));
            }
        };
    }


    private String processResponseContent(byte[] content, int loggedBytes, String contentEncoding) {
        if (loggedBytes == LIMIT_SIZE) {
            return "response entity exceeds the limit 2000 bytes";
        }
        String responseResult;

        // 如果是 Gzip 编码的响应体
        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(content, 0, loggedBytes);
                 GZIPInputStream gzipInputStream = new GZIPInputStream(byteArrayInputStream);
                 Reader reader = new InputStreamReader(gzipInputStream, StandardCharsets.UTF_8)) {

                // 读取解压后的数据
                StringBuilder stringBuilder = new StringBuilder();
                char[] buffer = new char[1024];
                int bytesRead;
                while ((bytesRead = reader.read(buffer)) != -1) {
                    stringBuilder.append(buffer, 0, bytesRead);
                }
                responseResult = stringBuilder.toString();
            } catch (IOException e) {
                responseResult = "Failed to decode gzip response";
            }
        } else {
            // TODO: deflate，br等其它压缩格式 ==
            // 如果是文本内容，直接用 UTF-8 解码
            try {
                responseResult = new String(content, StandardCharsets.UTF_8);
            } catch (Exception e) {
                // 如果无法解码为 UTF-8，则返回二进制数据的提示
                responseResult = "Binary or unsupported content type";
            }
        }
        if (MediaType.APPLICATION_OCTET_STREAM_VALUE.equals(contentEncoding)) {
            responseResult = "Binary data, not displayed";
        }
        return responseResult;
    }

    /**
     * 获取路径变量，注意SpringCloud Gateway转发的请求是获取该项的值
     */
    private static Map<String, Object> getPathParams(ServerWebExchange exchange) {
        Map<String, Object> pathParams = (Map<String, Object>) exchange.getAttributes().get(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return CollectionUtils.isEmpty(pathParams) ? new HashMap<>() : pathParams;
    }

    /**
     * 获取QueryParams
     */
    private static Map<String, Object> getQueryParams(MultiValueMap<String, String> queryParams) {
        return queryParams.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.join(",",entry.getValue())
                ));
    }

    private boolean isTextResponse(ServerHttpResponse response) {
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType == null) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return mediaType.isCompatibleWith(MediaType.TEXT_PLAIN) ||
                    mediaType.isCompatibleWith(MediaType.APPLICATION_JSON) ||
                    mediaType.isCompatibleWith(MediaType.APPLICATION_XML) ||
                    mediaType.isCompatibleWith(MediaType.TEXT_HTML) ||
                    mediaType.toString().startsWith("text/");
        } catch (Exception e) {
            return false;
        }
    }
}
