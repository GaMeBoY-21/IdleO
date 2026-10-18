package com.idleo.controlplane.hosts.internal;

import com.idleo.controlplane.SemanticVersion;
import com.idleo.controlplane.hosts.AgentReleaseCached;
import com.idleo.controlplane.hosts.AgentReleases;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Reads the Windows app's release feed (ADR 0034): every {@code hosts.releases.refresh-every}, {@code
 * stable/latest.json} and its {@code .sig} from the {@code releases} bucket. A manifest is cached only once its
 * Ed25519 signature verifies against the configured release key and its fields check out; one that fails is logged as
 * an error and ignored, and so is one older than the cached one (a replayed old manifest). The last good one is kept.
 * A new manifest (a newer version, or the same one with a raised rollout) is announced ({@link AgentReleaseCached}) so
 * connected agents are offered it.
 *
 * <p>The S3 clients are this feed's own, never beans, so the sessions module's storage clients stay the only ones
 * injected by type.
 */
@Service
@EnableConfigurationProperties(AgentReleaseProperties.class)
class AgentReleaseFeed implements AgentReleases, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(AgentReleaseFeed.class);
    static final String MANIFEST_KEY = ReleaseManifest.CHANNEL + "/latest.json";
    static final String SIGNATURE_KEY = MANIFEST_KEY + ".sig";
    static final String SETUP_FILENAME = "IdleO-Setup.exe";

    /** The links a release's files are downloaded from, valid for {@code hosts.releases.link-ttl}. */
    record Links(String app, String distro, String setup) {}

    private final AgentReleaseProperties properties;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final @Nullable PublicKey publicKey;
    private final @Nullable S3Client s3;
    private final @Nullable S3Presigner presigner;
    private final AtomicReference<@Nullable ReleaseManifest> latest = new AtomicReference<>();

    AgentReleaseFeed(AgentReleaseProperties properties, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.events = events;
        if (!properties.configured()) {
            log.info("The Windows app's release feed is off: hosts.releases needs an endpoint, keys and public-key");
            this.publicKey = null;
            this.s3 = null;
            this.presigner = null;
            return;
        }
        this.publicKey = ReleaseManifest.publicKey(Objects.requireNonNull(properties.publicKey()));
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKeyId(), properties.secretAccessKey()));
        var pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
        this.s3 = S3Client.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .httpClient(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(10))
                        .build())
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(properties.endpoint())
                .region(Region.of(properties.region()))
                .credentialsProvider(credentials)
                .serviceConfiguration(pathStyle)
                .build();
    }

    /** Reads the feed now and every {@code refresh-every}; never throws, so the schedule keeps going. */
    @Scheduled(initialDelayString = "0", fixedDelayString = "${hosts.releases.refresh-every:5m}")
    void refresh() {
        if (s3 == null || publicKey == null) {
            return;
        }
        byte[] manifest;
        byte[] signature;
        try {
            manifest = read(MANIFEST_KEY);
            signature = Base64.getDecoder().decode(new String(read(SIGNATURE_KEY), StandardCharsets.US_ASCII).strip());
        } catch (NoSuchKeyException none) {
            log.debug("No release published yet ({} is missing)", MANIFEST_KEY);
            return;
        } catch (SdkException | IllegalArgumentException e) {
            log.warn("Couldn't read the release feed; keeping the last good manifest: {}", e.getMessage());
            return;
        }
        accept(manifest, signature);
    }

    /** Verifies and caches a manifest read from the bucket. */
    void accept(byte[] manifest, byte[] signature) {
        ReleaseManifest verified;
        try {
            verified = ReleaseManifest.verify(manifest, signature, Objects.requireNonNull(publicKey));
        } catch (ReleaseManifest.Rejected e) {
            log.error("Ignored a release manifest that failed verification: {}", e.getMessage());
            return;
        }
        var previous = latest.get();
        if (previous != null && verified.semver().compareTo(previous.semver()) < 0) {
            log.error(
                    "Ignored release manifest {}: it's older than the cached {} (a replayed manifest?)",
                    verified.version(),
                    previous.version());
            return;
        }
        latest.set(verified);
        if (previous == null || !Arrays.equals(previous.bytes(), verified.bytes())) {
            log.info("Cached release {} ({}% rollout)", verified.version(), verified.rolloutPercent());
            events.publishEvent(new AgentReleaseCached(verified.version()));
        }
    }

    /** The cached manifest, if any. */
    Optional<ReleaseManifest> latest() {
        return Optional.ofNullable(latest.get());
    }

    /** Fresh download links for a release's three files. */
    Links links(ReleaseManifest manifest) {
        return new Links(
                presign(manifest.app().file(), null),
                presign(manifest.distro().file(), null),
                presign(manifest.setup().file(), "attachment; filename=\"" + SETUP_FILENAME + "\""));
    }

    @Override
    public Optional<Update> updateFor(UUID hostId, String agentVersion) {
        return eligible(hostId, agentVersion).map(manifest -> {
            var links = links(manifest);
            return new Update(manifest.version(), manifest.bytes(), manifest.signature(), links.app(), links.distro());
        });
    }

    /**
     * The release a host should update to: newer than {@code agentVersion}, the host in its rollout, and not rolled
     * back there. The one rule, for {@code UpdateAgent} and for the machine page's "Updating when idle".
     */
    Optional<ReleaseManifest> eligible(UUID hostId, @Nullable String agentVersion) {
        var manifest = latest.get();
        if (manifest == null || agentVersion == null) {
            return Optional.empty();
        }
        var running = SemanticVersion.parse(agentVersion);
        if (running.isEmpty() || manifest.semver().compareTo(running.get()) <= 0) {
            return Optional.empty();
        }
        if (!manifest.includes(hostId) || rolledBack(hostId, manifest.version())) {
            return Optional.empty();
        }
        return Optional.of(manifest);
    }

    private boolean rolledBack(UUID hostId, String version) {
        return jdbc.sql("""
                        select exists (select 1 from hosts.update_results
                                       where host_id = :hostId and version = :version and outcome = 'rolled_back')
                        """)
                .param("hostId", hostId)
                .param("version", version)
                .query(Boolean.class)
                .single();
    }

    private byte[] read(String key) {
        return Objects.requireNonNull(s3)
                .getObjectAsBytes(GetObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .build())
                .asByteArray();
    }

    private String presign(String file, @Nullable String disposition) {
        var get = GetObjectRequest.builder().bucket(properties.bucket()).key(ReleaseManifest.CHANNEL + "/" + file);
        if (disposition != null) {
            get.responseContentDisposition(disposition);
        }
        return Objects.requireNonNull(presigner)
                .presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(properties.linkTtl())
                        .getObjectRequest(get.build())
                        .build())
                .url()
                .toString();
    }

    @Override
    public void destroy() {
        if (s3 != null) {
            s3.close();
        }
        if (presigner != null) {
            presigner.close();
        }
    }
}
