package org.hypnos.webflux.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * requestBody
 */
@Data
public class ContentDTO {

    private Integer num;

    private String str;

    private Double d;
}
