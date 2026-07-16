package com.example.dart.disclosure.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Disclosure(
        @JsonProperty("corp_name") String corpName,
        @JsonProperty("corp_code") String corpCode,
        @JsonProperty("stock_code") String stockCode,
        @JsonProperty("corp_cls") String corpCls,
        @JsonProperty("report_nm") String reportNm,
        @JsonProperty("rcept_no") String rceptNo,
        @JsonProperty("rcept_dt") String rceptDt,
        @JsonProperty("flr_nm") String flrNm
) {

    /** DART corp_cls 코드(Y/K/N/E)를 시장 이름으로 변환. */
    public String marketName() {
        if (corpCls == null) return "기타";
        return switch (corpCls) {
            case "Y" -> "코스피";
            case "K" -> "코스닥";
            case "N" -> "코넥스";
            default -> "기타";
        };
    }
}
