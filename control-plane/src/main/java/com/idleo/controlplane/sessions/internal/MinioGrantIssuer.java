package com.idleo.controlplane.sessions.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.sts.StsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Local runs, tests and the end-to-end test (ADR 0016): MinIO's STS {@code AssumeRole} with an inline session
 * policy allowing exactly the given prefixes, so MinIO itself refuses anything else. Refused under {@code prod}.
 */
class MinioGrantIssuer implements GrantIssuer {

    /**
     * STS refuses anything shorter than 15 minutes. A shorter configured lifetime (the tests') still holds for the
     * agent, which stops using a grant once its {@code expires_in} has passed.
     */
    static final long MIN_STS_SECONDS = 900;

    private final StsClient sts;
    private final StorageProperties properties;
    private final JsonMapper json;

    MinioGrantIssuer(StsClient sts, StorageProperties properties, JsonMapper json) {
        this.sts = sts;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public StorageGrant issue(List<String> prefixes, boolean readWrite) {
        var credentials = sts.assumeRole(request -> request
                        // MinIO ignores the role; AWS's request shape needs one.
                        .roleArn("arn:minio:iam:::role/idleo-session")
                        .roleSessionName("idleo-session")
                        .durationSeconds(Math.toIntExact(
                                Math.max(MIN_STS_SECONDS, properties.grantTtl().toSeconds())))
                        .policy(policy(prefixes, readWrite)))
                .credentials();
        return new StorageGrant(
                properties.endpoint().toString(),
                properties.bucket(),
                credentials.accessKeyId(),
                credentials.secretAccessKey(),
                credentials.sessionToken(),
                prefixes,
                readWrite,
                properties.grantTtl());
    }

    /** An IAM policy: object actions under each prefix, and listing only those prefixes. */
    String policy(List<String> prefixes, boolean readWrite) {
        var bucket = "arn:aws:s3:::" + properties.bucket();
        var objects =
                prefixes.stream().map(prefix -> bucket + "/" + prefix + "*").toList();
        var actions = new ArrayList<>(List.of("s3:GetObject"));
        if (readWrite) {
            actions.addAll(List.of("s3:PutObject", "s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"));
        }
        var objectStatement = new LinkedHashMap<String, Object>();
        objectStatement.put("Effect", "Allow");
        objectStatement.put("Action", actions);
        objectStatement.put("Resource", objects);
        var listStatement = new LinkedHashMap<String, Object>();
        listStatement.put("Effect", "Allow");
        listStatement.put("Action", List.of("s3:ListBucket"));
        listStatement.put("Resource", List.of(bucket));
        listStatement.put(
                "Condition",
                Map.of(
                        "StringLike",
                        Map.of(
                                "s3:prefix",
                                prefixes.stream().map(prefix -> prefix + "*").toList())));
        return json.writeValueAsString(
                Map.of("Version", "2012-10-17", "Statement", List.of(objectStatement, listStatement)));
    }
}
