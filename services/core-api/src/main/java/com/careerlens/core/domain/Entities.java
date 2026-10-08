package com.careerlens.core.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persistence-only model. API contracts deliberately live in their modules so database fields never leak
 * directly to the browser. Nested static entity classes keep the sample backend compact while remaining
 * valid JPA entities.
 */
public final class Entities {
    private Entities() {}

    @MappedSuperclass
    @Getter @Setter @NoArgsConstructor
    public abstract static class BaseEntity {
        @Id @UuidGenerator
        @Column(nullable = false, updatable = false)
        private UUID id;
        @Column(name = "created_at", nullable = false, updatable = false)
        private Instant createdAt;
        @Column(name = "updated_at", nullable = false)
        private Instant updatedAt;

        @PrePersist
        protected void createTimestamps() {
            if (id == null) id = UUID.randomUUID();
            Instant now = Instant.now();
            if (createdAt == null) createdAt = now;
            updatedAt = now;
        }

        @PreUpdate
        protected void updateTimestamp() { updatedAt = Instant.now(); }
    }

    @Entity(name = "AppUser") @Table(name = "app_user")
    @Getter @Setter @NoArgsConstructor
    public static class AppUser extends BaseEntity {
        @Column(nullable = false, unique = true, length = 254) private String email;
        @Column(name = "display_name", nullable = false, length = 100) private String displayName;
        @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
        @Column(nullable = false, length = 30) private String role;
        @Column(nullable = false, length = 30) private String status;
    }

    @Entity(name = "AccessToken") @Table(name = "access_token")
    @Getter @Setter @NoArgsConstructor
    public static class AccessToken extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;
        @Column(name = "expires_at", nullable = false) private Instant expiresAt;
        @Column(name = "last_used_at") private Instant lastUsedAt;
    }

    @Entity(name = "Resume") @Table(name = "resume")
    @Getter @Setter @NoArgsConstructor
    public static class Resume extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(nullable = false, length = 160) private String title;
        @Column(length = 240) private String headline;
        @Column(name = "current_version_number", nullable = false) private int currentVersionNumber;
        @Column(name = "current_version_id") private UUID currentVersionId;
    }

    @Entity(name = "ResumeDraft") @Table(name = "resume_draft")
    @Getter @Setter @NoArgsConstructor
    public static class ResumeDraft extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "resume_id", nullable = false, unique = true) private UUID resumeId;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "content_text", nullable = false) private String contentText;
        @Column(nullable = false) private long revision;
        @Version @Column(name = "lock_version", nullable = false) private long lockVersion;
    }

    @Entity(name = "ResumeVersion") @Table(name = "resume_version")
    @Getter @Setter @NoArgsConstructor
    public static class ResumeVersion extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "resume_id", nullable = false) private UUID resumeId;
        @Column(name = "version_number", nullable = false) private int versionNumber;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "content_text", nullable = false) private String contentText;
        @Column(nullable = false, length = 40) private String source;
        @Column(name = "source_version_id") private UUID sourceVersionId;
    }

    @Entity(name = "JobDescription") @Table(name = "job_description")
    @Getter @Setter @NoArgsConstructor
    public static class JobDescription extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(nullable = false, length = 180) private String company;
        @Column(name = "role_name", nullable = false, length = 180) private String roleName;
        @Column(length = 160) private String location;
        @Column(length = 120) private String salary;
        @Column(name = "current_version_number", nullable = false) private int currentVersionNumber;
        @Column(name = "current_version_id") private UUID currentVersionId;
    }

    @Entity(name = "JobDescriptionVersion") @Table(name = "job_description_version")
    @Getter @Setter @NoArgsConstructor
    public static class JobDescriptionVersion extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "job_description_id", nullable = false) private UUID jobDescriptionId;
        @Column(name = "version_number", nullable = false) private int versionNumber;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "raw_text", nullable = false) private String rawText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "structured_text", nullable = false) private String structuredText;
    }

    @Entity(name = "JdRequirement") @Table(name = "jd_requirement")
    @Getter @Setter @NoArgsConstructor
    public static class JdRequirement extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "jd_version_id", nullable = false) private UUID jdVersionId;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "requirement_text", nullable = false) private String requirementText;
        @Column(nullable = false, length = 80) private String category;
        @Column(nullable = false, length = 30) private String importance;
        @Column(name = "hard_condition", nullable = false) private boolean hardCondition;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "aliases_text", nullable = false) private String aliasesText;
        @Column(name = "sort_order", nullable = false) private int sortOrder;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name="quote_text") private String quoteText;
        @Column(name="quote_start") private int quoteStart = -1;
        @Column(name="quote_end") private int quoteEnd = -1;
        @Column(name="extraction_confidence") private double extractionConfidence;
        @Column(name="confirmed") private boolean confirmed;
    }

    @Entity(name = "MatchRun") @Table(name = "match_run")
    @Getter @Setter @NoArgsConstructor
    public static class MatchRun extends BaseEntity {
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name="input_snapshot") private String inputSnapshot;
        @Column(name="input_hash",length=64) private String inputHash;
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "resume_version_id", nullable = false) private UUID resumeVersionId;
        @Column(name = "jd_version_id", nullable = false) private UUID jdVersionId;
        @Column(name = "total_score", nullable = false, precision = 5, scale = 2) private BigDecimal totalScore;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "breakdown_text", nullable = false) private String breakdownText;
        @Column(name = "rule_version", nullable = false, length = 40) private String ruleVersion;
    }

    @Entity(name = "MatchItem") @Table(name = "match_item")
    @Getter @Setter @NoArgsConstructor
    public static class MatchItem extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "match_run_id", nullable = false) private UUID matchRunId;
        @Column(name = "requirement_id", nullable = false) private UUID requirementId;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "requirement_text", nullable = false) private String requirementText;
        @Column(nullable = false, length = 80) private String category;
        @Column(nullable = false, length = 30) private String importance;
        @Column(nullable = false, length = 30) private String verdict;
        @Column(nullable = false, precision = 6, scale = 2) private BigDecimal score;
        @Column(name = "max_score", nullable = false, precision = 6, scale = 2) private BigDecimal maxScore;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "jd_evidence", nullable = false) private String jdEvidence;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "resume_evidence", nullable = false) private String resumeEvidence;
        @Column(name = "resume_source", nullable = false, length = 240) private String resumeSource;
        @Column(name = "match_method", nullable = false, length = 80) private String matchMethod;
        @Column(nullable = false, precision = 5, scale = 4) private BigDecimal confidence;
    }

    @Entity(name = "SuggestionBatch") @Table(name = "suggestion_batch")
    @Getter @Setter @NoArgsConstructor
    public static class SuggestionBatch extends BaseEntity {
        @Column(name="provider_mode",length=160) private String providerMode;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name="warnings_text") private String warningsText;
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "match_run_id") private UUID matchRunId;
        @Column(name = "resume_version_id") private UUID resumeVersionId;
        @Column(name = "resume_id") private UUID resumeId;
        @Column(name = "draft_revision") private Long draftRevision;
        @Column(nullable = false, length = 30) private String status;
    }

    @Entity(name = "ResumeSuggestion") @Table(name = "resume_suggestion")
    @Getter @Setter @NoArgsConstructor
    public static class ResumeSuggestion extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "batch_id", nullable = false) private UUID batchId;
        @Column(name = "target_element_id", length = 80) private String targetElementId;
        @Column(name = "section_name", nullable = false, length = 100) private String sectionName;
        @Column(nullable = false, length = 240) private String title;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "before_text", nullable = false) private String beforeText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "after_text", nullable = false) private String afterText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "reason_text", nullable = false) private String reasonText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "evidence_text", nullable = false) private String evidenceText;
        @Column(name = "suggestion_kind", nullable = false, length = 30) private String suggestionKind;
        @Column(name = "suggestion_status", nullable = false, length = 30) private String suggestionStatus;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "patch_text", nullable = false) private String patchText;
    }

    @Entity(name = "JobApplication") @Table(name = "job_application")
    @Getter @Setter @NoArgsConstructor
    public static class JobApplication extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "platform_identity_id") private UUID platformIdentityId;
        @Column(nullable = false, length = 180) private String company;
        @Column(name = "role_name", nullable = false, length = 180) private String roleName;
        @Column(length = 160) private String location;
        @Column(nullable = false, length = 30) private String stage;
        @Column(name = "match_score", nullable = false) private int matchScore;
        @Column(name = "next_action", length = 240) private String nextAction;
        @Column(name = "logo_text", length = 10) private String logoText;
        @Column(name = "logo_tone", length = 20) private String logoTone;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "tags_text", nullable = false) private String tagsText;
        @Column(length = 30) private String platform;
        @Column(name = "external_job_id", length = 160) private String externalJobId;
        @Column(name = "resume_version_number") private Integer resumeVersionNumber;
        @Column(name = "action_type", length = 40) private String actionType;
        @Column(name = "automation_status", length = 40) private String automationStatus;
        @Column(length = 240) private String receipt;
        @Column(name="handoff_status",length=40) private String handoffStatus;
        @Column(name="handed_off_at") private Instant handedOffAt;
    }

    @Entity(name = "ApplicationEvent") @Table(name = "application_event")
    @Getter @Setter @NoArgsConstructor
    public static class ApplicationEvent extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "application_id", nullable = false) private UUID applicationId;
        @Column(name = "event_type", nullable = false, length = 60) private String eventType;
        @Column(name = "from_stage", length = 30) private String fromStage;
        @Column(name = "to_stage", length = 30) private String toStage;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "detail_text", nullable = false) private String detailText;
    }

    @Entity(name = "PlatformAccount") @Table(name = "platform_account")
    @Getter @Setter @NoArgsConstructor
    public static class PlatformAccount extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(nullable = false, length = 30) private String platform;
        @Column(name = "display_name", nullable = false, length = 100) private String displayName;
        @Column(name = "connection_type", nullable = false, length = 40) private String connectionType;
        @Column(name = "connection_status", nullable = false, length = 40) private String connectionStatus;
        @Column(name = "masked_identity", length = 160) private String maskedIdentity;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "capabilities_text", nullable = false) private String capabilitiesText;
        @Column(name = "adapter_version", length = 60) private String adapterVersion;
        @Column(name = "last_checked_at") private Instant lastCheckedAt;
    }

    @Entity(name = "PlatformIdentity") @Table(name = "platform_identity")
    @Getter @Setter @NoArgsConstructor
    public static class PlatformIdentity extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(nullable = false, length = 30) private String platform;
        @Column(name = "profile_name", nullable = false, length = 32) private String profileName;
        @Column(name = "display_name", nullable = false, length = 100) private String displayName;
        @Column(name = "account_fingerprint", length = 64) private String accountFingerprint;
        @Column(name = "masked_identity", length = 160) private String maskedIdentity;
        @Column(name = "connection_status", nullable = false, length = 40) private String connectionStatus;
        @Column(nullable = false) private boolean active;
        @Column(name = "last_checked_at") private Instant lastCheckedAt;
        @Column(name = "archived_at") private Instant archivedAt;
    }

    @Entity(name = "DiscoveryRun") @Table(name = "discovery_run")
    @Getter @Setter @NoArgsConstructor
    public static class DiscoveryRun extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "platform_identity_id") private UUID platformIdentityId;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "platforms_text", nullable = false) private String platformsText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "spec_text", nullable = false) private String specText;
        @Column(nullable = false, length = 40) private String status;
        @Column(nullable = false) private int progress;
        @Column(name = "discovered_count", nullable = false) private int discoveredCount;
        @Column(name = "duplicate_count", nullable = false) private int duplicateCount;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name="filter_stats_text",nullable=false) private String filterStatsText="{}";
        @Column(name = "archived_at") private Instant archivedAt;
    }

    @Entity(name = "DiscoveredJob") @Table(name = "discovered_job")
    @Getter @Setter @NoArgsConstructor
    public static class DiscoveredJob extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "platform_identity_id") private UUID platformIdentityId;
        @Column(name = "discovery_run_id") private UUID discoveryRunId;
        @Column(nullable = false, length = 30) private String platform;
        @Column(name = "external_job_id", nullable = false, length = 160) private String externalJobId;
        @Column(nullable = false, length = 180) private String company;
        @Column(name = "role_name", nullable = false, length = 180) private String roleName;
        @Column(length = 160) private String location;
        @Column(length = 120) private String salary;
        @Column(length = 120) private String experience;
        @Column(length = 120) private String degree;
        @Column(name = "published_at_text", length = 120) private String publishedAtText;
        @Column(length = 160) private String recruiter;
        @Column(name = "recruiter_id", length = 256) private String recruiterId;
        @Column(name = "recruiter_active", length = 120) private String recruiterActive;
        @Column(name = "company_size", length = 120) private String companySize;
        @Column(name = "recruiter_online", nullable = false) private boolean recruiterOnline;
        @Column(nullable = false) private boolean headhunter;
        @Column(nullable = false) private boolean contacted;
        @Column(name = "risk_score", nullable = false) private int riskScore;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "risk_reasons_text", nullable = false) private String riskReasonsText;
        @Column(name = "commute_distance_km") private Double commuteDistanceKm;
        @Column(name = "commute_duration_minutes") private Integer commuteDurationMinutes;
        @Column(name = "company_tone", length = 20) private String companyTone;
        @Column(name = "match_score", nullable = false) private int matchScore;
        @Column(nullable = false, length = 5) private String grade;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "matched_skills_text", nullable = false) private String matchedSkillsText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "missing_skills_text", nullable = false) private String missingSkillsText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "hard_conflicts_text", nullable = false) private String hardConflictsText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "summary_text", nullable = false) private String summaryText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "job_content_text", nullable = false) private String jobContentText;
        @Column(name = "content_hash", nullable = false, length = 64) private String contentHash;
        @Column(name = "canonical_url", length = 2048) private String canonicalUrl;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "platform_metadata_text") private String platformMetadataText;
        @Column(nullable = false) private boolean selected;
        @Column(name = "already_tracked", nullable = false) private boolean alreadyTracked;
    }

    @Entity(name = "ApplicationPlan") @Table(name = "application_plan")
    @Getter @Setter @NoArgsConstructor
    public static class ApplicationPlan extends BaseEntity {
        @Column(name="observation_id") private UUID observationId;
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "platform_identity_id") private UUID platformIdentityId;
        @Column(name = "identity_bound", nullable = false) private boolean identityBound;
        @Column(name = "discovered_job_id", nullable = false) private UUID discoveredJobId;
        @Column(nullable = false, length = 30) private String platform;
        @Column(name = "external_job_id", nullable = false, length = 160) private String externalJobId;
        @Column(name = "action_type", nullable = false, length = 40) private String actionType;
        @Column(name = "resume_version_id", nullable = false) private UUID resumeVersionId;
        @Column(name = "resume_version_number", nullable = false) private int resumeVersionNumber;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "greeting_text", nullable = false) private String greetingText;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "form_answers_text", nullable = false) private String formAnswersText;
        @Column(nullable = false) private boolean included;
        @Column(name = "approval_status", nullable = false, length = 30) private String approvalStatus;
        @Column(name = "plan_hash", nullable = false, length = 64) private String planHash;
        @Column(name = "approved_hash", length = 64) private String approvedHash;
        @Column(name = "approval_token_hash", length = 64) private String approvalTokenHash;
        @Column(name = "approval_expires_at") private Instant approvalExpiresAt;
        @Column(name="greeting_style",length=40) private String greetingStyle;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name="greeting_evidence_text",nullable=false) private String greetingEvidenceText="{}";
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name="greeting_candidates_text",nullable=false) private String greetingCandidatesText="[]";
        @Column(name = "archived_at") private Instant archivedAt;
    }

    @Entity(name = "AutomationTask") @Table(name = "automation_task")
    @Getter @Setter @NoArgsConstructor
    public static class AutomationTask extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "platform_identity_id") private UUID platformIdentityId;
        @Column(name = "application_plan_id") private UUID applicationPlanId;
        @Column(nullable = false, length = 30) private String platform;
        @Column(name = "external_job_id", nullable = false, length = 160) private String externalJobId;
        @Column(nullable = false, length = 180) private String company;
        @Column(name = "role_name", nullable = false, length = 180) private String roleName;
        @Column(name = "action_type", nullable = false, length = 40) private String actionType;
        @Column(name = "resume_version_number", nullable = false) private int resumeVersionNumber;
        @Column(name = "task_status", nullable = false, length = 40) private String taskStatus;
        @Column(nullable = false) private int progress;
        @Column(name = "current_step", nullable = false, length = 240) private String currentStep;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "human_action_text") private String humanActionText;
        @Column(length = 240) private String receipt;
        @Column(nullable = false) private int attempt;
        @Column(name = "state_version", nullable = false) private long stateVersion;
        @Column(name = "runner_task_id", length = 128) private String runnerTaskId;
        @Column(name = "runner_command_id", length = 128) private String runnerCommandId;
        @Column(name = "runner_phase", length = 40) private String runnerPhase;
        @Column(name = "receipt_source", length = 30) private String receiptSource;
        @Column(name = "platform_receipt_id", length = 240) private String platformReceiptId;
        @Column(name = "last_synced_at") private Instant lastSyncedAt;
        @Column(name = "archived_at") private Instant archivedAt;
        @Column(name = "retry_of_task_id") private UUID retryOfTaskId;
        @Column(name = "retry_root_task_id") private UUID retryRootTaskId;
        @Column(name = "failure_category", length = 60) private String failureCategory;
        @Column(nullable = false) private boolean retryable;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "execution_snapshot_text") private String executionSnapshotText;
    }

    @Entity(name = "HumanAction") @Table(name = "human_action")
    @Getter @Setter @NoArgsConstructor
    public static class HumanAction extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "automation_task_id", nullable = false) private UUID automationTaskId;
        @Column(name = "action_kind", nullable = false, length = 40) private String actionKind;
        @Column(nullable = false, length = 180) private String title;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "description_text", nullable = false) private String descriptionText;
        @Column(name = "action_status", nullable = false, length = 30) private String actionStatus;
        @Column(name = "expires_at") private Instant expiresAt;
        @Column(name = "resolved_at") private Instant resolvedAt;
    }

    @Entity(name = "AuditEvent") @Table(name = "audit_event")
    @Getter @Setter @NoArgsConstructor
    public static class AuditEvent extends BaseEntity {
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(name = "aggregate_type", nullable = false, length = 60) private String aggregateType;
        @Column(name = "aggregate_id") private UUID aggregateId;
        @Column(name = "event_type", nullable = false, length = 80) private String eventType;
        @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.LONGVARCHAR) @Column(name = "detail_text", nullable = false) private String detailText;
        @Column(name = "request_id", length = 100) private String requestId;
    }
}
