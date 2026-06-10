package com.example.dart.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Disclosure(
        @JsonProperty("corp_name") String corpName,
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("report_nm") String reportNm,
        @JsonProperty("rcept_no") String rceptNo,
        @JsonProperty("rcept_dt") String rceptDt,
        @JsonProperty("flr_nm") String flrNm
) {}
