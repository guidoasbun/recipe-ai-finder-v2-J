package io.asbun.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.asbun.backend.model.enums.ConsentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GrantConsentRequest {

    @NotNull
    private ConsentType consentType;

    @Size(max = 20, message = "version must be at most 20 characters")
    private String version;
}
