package in.railops.identity.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import in.railops.common.web.PiiMasker;
import in.railops.identity.domain.AuditEntry;
import in.railops.identity.domain.Repositories.AuditEntryRepository;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Tamper-evident audit log: hash_n = SHA-256(hash_{n-1} || canonical(entry_n)).
 *
 * <p>Writers serialize on a transaction-scoped advisory lock so the chain has exactly one successor per
 * link. The hash covers a canonical re-serialization of the detail (keys sorted), never the raw jsonb
 * text: PostgreSQL reorders jsonb keys on storage, so hashing stored text would never verify.
 *
 * <p>Limits: this detects modification by anyone who can't recompute the whole chain. A DB superuser
 * could rewrite every row and every hash; the defence is exporting the head hash daily to WORM storage
 * outside the database's trust domain (Phase 12).
 */
@Service
public class AuditService {

    private static final long CHAIN_LOCK = 0x4155444954L; // "AUDIT"
    private static final byte[] GENESIS = new byte[32];

    private final AuditEntryRepository repo;
    private final EntityManager em;
    private final ObjectMapper canonical = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public AuditService(AuditEntryRepository repo, EntityManager em) {
        this.repo = repo;
        this.em = em;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actorId, String action, String targetType, String targetId,
                       Map<String, ?> detail, String ip) {
        append(null, actorId, action, targetType, targetId, detail, ip, Instant.now());
    }

    /** For events relayed from other services; dedupes on the source event id. */
    @Transactional
    public void recordRemote(UUID sourceEventId, String actorId, String action, String targetType, String targetId,
                             Map<String, ?> detail, Instant occurredAt) {
        if (repo.existsBySourceEventId(sourceEventId)) {
            return;
        }
        append(sourceEventId, actorId, action, targetType, targetId, detail, null, occurredAt);
    }

    private void append(UUID sourceEventId, String actorId, String action, String targetType, String targetId,
                        Map<String, ?> detail, String ip, Instant occurredAt) {
        em.createNativeQuery("SELECT pg_advisory_xact_lock(:k)").setParameter("k", CHAIN_LOCK).getSingleResult();
        byte[] prev = repo.findTopByOrderByIdDesc().map(AuditEntry::getHash).orElse(GENESIS);
        Instant at = occurredAt.truncatedTo(ChronoUnit.MICROS); // what timestamptz stores; must hash the same
        String detailJson = canonicalJson(maskValues(detail));
        byte[] hash = chainHash(prev, actorId, action, targetType, targetId, detailJson, ip, at);
        repo.save(new AuditEntry(sourceEventId, actorId, action, targetType, targetId, detailJson, ip, at, prev, hash));
    }

    public record VerifyResult(boolean intact, long entriesChecked, Long firstBrokenId) {
    }

    /** A published chain head: (id, hash) copied to write-once storage outside this database. */
    public record Anchor(long id, String hash) {
    }

    public record AnchorVerifyResult(boolean intact, VerifyResult chain, int anchorsChecked, List<Long> mismatchedAnchorIds,
                                     List<Long> missingAnchorIds) {
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Anchor> head() {
        return repo.findTopByOrderByIdDesc().map(e -> new Anchor(e.getId(), java.util.HexFormat.of().formatHex(e.getHash())));
    }

    /**
     * The chain alone proves internal consistency, not authenticity: someone able to write the table could rewrite
     * an entry AND recompute every later hash, and {@link #verify()} would still pass. Anchors close that gap: their
     * (id, hash) pairs were published to WORM storage when written, so a rewrite of anything at or before an anchor
     * changes a hash the attacker cannot change. A row deleted outright shows up as a missing anchor id.
     */
    @Transactional(readOnly = true)
    public AnchorVerifyResult verifyAgainst(List<Anchor> anchors) {
        VerifyResult chain = verify();
        List<Long> mismatched = new java.util.ArrayList<>();
        List<Long> missing = new java.util.ArrayList<>();
        for (Anchor a : anchors) {
            var row = repo.findById(a.id());
            if (row.isEmpty()) {
                missing.add(a.id());
            } else if (!MessageDigest.isEqual(row.get().getHash(), java.util.HexFormat.of().parseHex(a.hash()))) {
                mismatched.add(a.id());
            }
        }
        return new AnchorVerifyResult(chain.intact() && mismatched.isEmpty() && missing.isEmpty(), chain,
                anchors.size(), mismatched, missing);
    }

    /** Recomputes the chain from the start. Read-only; streams in pages of 1000. */
    @Transactional(readOnly = true)
    public VerifyResult verify() {
        byte[] prev = GENESIS;
        long lastId = 0;
        long checked = 0;
        while (true) {
            List<AuditEntry> page = repo.findByIdGreaterThanOrderByIdAsc(lastId, PageRequest.of(0, 1000));
            if (page.isEmpty()) {
                return new VerifyResult(true, checked, null);
            }
            for (AuditEntry e : page) {
                byte[] expected = chainHash(prev, e.getActorId(), e.getAction(), e.getTargetType(), e.getTargetId(),
                        canonicalJson(parse(e.getDetail())), e.getIp(), e.getOccurredAt());
                if (!MessageDigest.isEqual(e.getPrevHash(), prev) || !MessageDigest.isEqual(e.getHash(), expected)) {
                    return new VerifyResult(false, checked, e.getId());
                }
                prev = e.getHash();
                lastId = e.getId();
                checked++;
            }
            em.clear(); // keep the persistence context from growing over a long chain
        }
    }

    static byte[] chainHash(byte[] prev, String actor, String action, String targetType, String targetId,
                            String detailJson, String ip, Instant at) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(prev);
            // Length-prefix each field so ("ab","c") and ("a","bc") can't collide.
            for (String f : new String[]{actor, action, targetType, targetId, detailJson, ip == null ? "" : ip, at.toString()}) {
                byte[] b = f.getBytes(StandardCharsets.UTF_8);
                md.update(java.nio.ByteBuffer.allocate(4).putInt(b.length).array());
                md.update(b);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> maskValues(Map<String, ?> detail) {
        Map<String, Object> out = new TreeMap<>();
        if (detail != null) {
            detail.forEach((k, v) -> out.put(k, v instanceof String s ? PiiMasker.mask(s) : v));
        }
        return out;
    }

    private String canonicalJson(Map<String, ?> m) {
        try {
            return canonical.writeValueAsString(new TreeMap<>(m));
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> parse(String json) {
        try {
            return Optional.ofNullable(canonical.readValue(json, new TypeReference<Map<String, Object>>() {
            })).orElse(Map.of());
        } catch (JacksonException e) {
            throw new IllegalStateException("corrupt audit detail", e);
        }
    }
}
