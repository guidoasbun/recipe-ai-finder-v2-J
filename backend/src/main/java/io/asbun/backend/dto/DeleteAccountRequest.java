package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DeleteAccountRequest {

    @NotBlank
    @Pattern(regexp = "^(soft|immediate)$", message = "type must be 'soft' or 'immediate'")
    private String type;
}
