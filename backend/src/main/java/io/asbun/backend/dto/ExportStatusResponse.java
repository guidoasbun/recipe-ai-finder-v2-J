package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExportStatusResponse {

    private ExportStatus status;
    private String downloadUrl;
    private String error;

    public enum ExportStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }
}
