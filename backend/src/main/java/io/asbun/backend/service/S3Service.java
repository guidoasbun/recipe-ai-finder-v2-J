package io.asbun.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class S3Service {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;

    @Value("${s3.bucket}")
    private String bucket;

    public String uploadImage(String recipeId, byte[] imageBytes, String contentType) {
        // Deterministic key (recipes/{recipeId}.png) is kept regardless of the actual image
        // format so objectExists / generatePresignedUrl / deleteImage stay stable. The real
        // format is conveyed via the Content-Type header, which is what browsers honor when
        // rendering the presigned URL.
        String key = "recipes/" + recipeId + ".png";

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType != null && !contentType.isBlank() ? contentType : "image/png")
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(imageBytes));
        return key;
    }

    public String generatePresignedUrl(String s3Key) {
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofHours(1))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(bucket)
                        .key(s3Key)
                        .build())
                .build();
        return s3Presigner.presignGetObject(presignRequest).url().toString();
    }

    /**
     * Returns true if an object exists at the given key. Used to detect images that have been
     * removed from the bucket (e.g. by a past lifecycle rule) while the recipe still references
     * them, so callers can avoid handing out a presigned URL that would 404.
     */
    public boolean objectExists(String s3Key) {
        if (s3Key == null || s3Key.isBlank()) {
            return false;
        }
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            // A 404 can also surface as a generic S3Exception depending on bucket permissions.
            if (e.statusCode() == 404) {
                return false;
            }
            // For any other error (permissions, throttling, transient), assume the object is
            // present so we don't wrongly wipe a working image or trigger needless regeneration.
            log.warn("Could not verify existence of S3 object {} (status {}): {}", s3Key, e.statusCode(), e.getMessage());
            return true;
        }
    }

    public void deleteImage(String s3Key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();

        s3Client.deleteObject(request);
    }
}
