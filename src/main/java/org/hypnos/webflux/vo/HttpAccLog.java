package org.hypnos.webflux.vo;

import lombok.Data;

import java.util.Map;

@Data
public class HttpAccLog {

    /**
     * 跟踪链ID
     */
    private String traceId;

    /**
     * HTTP method
     */
    private String method;

    /**
     * 请求路径
     */
    private String path;

    /**
     * 请求时间，格式如：2021-06-15 13:07:24.270
     */
    private String reqTime;

    /**
     * 响应时间，格式如：2021-06-15 13:07:24.270
     */
    private String rspTime;

    /**
     * 处理时间（毫秒）
     */
    private long processTime;

    /**
     * 响应状态码
     */
    private Integer statusCode;

    /**
     * 请求体
     */
    private String requestBody;

    /**
     * 路径参数
     */
    private Map<String, Object> pathVariables;

    /**
     * 请求参数
     */
    private Map<String, Object> queryParams;

    /**
     * 表单数据
     */
    private Map<String, Object> formData;

    /**
     * 响应体
     */
    private String responseBody;

    /**
     * 服务端地址，也就是本机地址
     */
    private String serverAddr;

    /**
     * 请求方地址
     */
    private String remoteAddr;
}
