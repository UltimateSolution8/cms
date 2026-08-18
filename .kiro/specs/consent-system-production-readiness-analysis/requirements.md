# Requirements Document

## Introduction

This document specifies the requirements for a comprehensive Production Readiness Analysis of the UDS Group Consent Management System. The analysis system shall evaluate an existing implementation against regulatory requirements (DPDP Act 2023, TRAI TCCCPR, GDPR, multi-jurisdiction compliance), architectural soundness, code quality, production-grade operational readiness, and industry best practices. The system shall identify critical gaps, security vulnerabilities, missing features, and provide prioritized remediation recommendations aligned with regulatory deadlines (13 May 2027 for DPDP compliance).

The UDS Group operates 9+ subsidiaries across India, UK, Malaysia, Singapore, and Korea, processing consent for ~76,000 workforce and handling B2B contact databases through Denave. The existing implementation includes a Java/Maven multi-module architecture with ISO/IEC TS 27560:2023 compliant data model, hash-chained append-only ledger, offline-first snapshot architecture, and 359 tests across modules.

## Glossary

- **Analysis_System**: The production readiness analysis tool being specified
- **Target_System**: The UDS Consent Management System being analyzed
- **DPDP_Act**: Digital Personal Data Protection Act 2023 (India)
- **TRAI_TCCCPR**: Telecom Regulatory Authority of India - Telecom Commercial Communications Customer Preference Regulations
- **MeitY_BRD**: Ministry of Electronics and Information Technology Business Requirements Document for Consent Management
- **Regulatory_Gap**: Missing or incomplete implementation of statutory requirements
- **Architecture_Issue**: Design or structural problem affecting scalability, reliability, or maintainability
- **Code_Vulnerability**: Security weakness, bug, or quality issue in implementation
- **Production_Feature**: Operational capability required for production deployment (monitoring, backup, DR)
- **Priority_Level**: Classification of remediation urgency (P0: critical/pre-Phase-1, P1: Phase-2-blocker, P2: Phase-4-blocker, P3: enhancement)
- **tsi_dpdp_cms**: Open source MeitY BRD-compliant consent management system (Apache-2.0, Java/Maven, 413 commits)
- **Phase_1**: Core platform completion (weeks 5-16)
- **Phase_2**: Denave pilot (weeks 14-30)
- **Phase_4**: Group rollout (weeks 32-60)

## Requirements

### Requirement 1: Regulatory Compliance Gap Analysis

**User Story:** As a compliance officer, I want comprehensive identification of all regulatory compliance gaps, so that I can ensure the system meets all statutory obligations before the 13 May 2027 deadline.

#### Acceptance Criteria

1. WHEN analyzing DPDP Act 2023 compliance, THE Analysis_System SHALL identify all missing implementations of Sections 5, 6, 7, 8, 9 and DPDP Rules 2025
2. WHEN analyzing TRAI TCCCPR requirements, THE Analysis_System SHALL identify gaps in DLT registration, transactional consent 7-day expiry, inferred consent contract-lifetime expiry, and DND/NCPR scrubbing
3. WHEN analyzing GDPR/ePrivacy compliance, THE Analysis_System SHALL identify cookie consent issues, legitimate interest assessment gaps, and cross-border transfer controls
4. WHEN analyzing multi-jurisdiction requirements, THE Analysis_System SHALL verify Korea PIPA separate consent mechanics, Malaysia PDPA biometric data handling, and Singapore DNC registry integration
5. WHEN analyzing MeitY BRD requirements, THE Analysis_System SHALL compare implementation against official government specification and identify deviations
6. WHEN analyzing consent manager interoperability, THE Analysis_System SHALL verify readiness for DPDP Rule 4 compliance (13 Nov 2026 activation)
7. WHEN analyzing dark pattern compliance, THE Analysis_System SHALL identify Rule 8 violations in UI implementations
8. WHEN analyzing 22-language notice requirements, THE Analysis_System SHALL identify coverage gaps and missing translations
9. WHEN analyzing children's consent (Section 9), THE Analysis_System SHALL verify verifiable parental consent mechanisms and prohibition of behavioral tracking for under-18
10. WHEN analyzing cross-border data transfer controls, THE Analysis_System SHALL verify Rule 15 blacklist model implementation and Rule 13 SDF transfer restrictions

### Requirement 2: Architectural Soundness Assessment

**User Story:** As a system architect, I want identification of all architectural and design weaknesses, so that I can address scalability, reliability, and maintainability issues before production deployment.

#### Acceptance Criteria

1. WHEN analyzing multi-entity isolation, THE Analysis_System SHALL verify entity access guards, row-level security policies, and connection pool session variable handling
2. WHEN analyzing offline-first implementation, THE Analysis_System SHALL verify signed snapshot generation, idempotency key handling, sequence number conflict resolution, and sync mechanisms
3. WHEN analyzing event sourcing and CQRS, THE Analysis_System SHALL verify append-only enforcement, projection correctness, and event ordering guarantees
4. WHEN analyzing signed snapshot security, THE Analysis_System SHALL verify Ed25519 key management, signature verification, key rotation procedures, and snapshot expiry handling
5. WHEN analyzing cache invalidation strategies, THE Analysis_System SHALL verify Redis snapshot cache expiry, policy cache refresh, and notice cache negative-handling
6. WHEN analyzing scalability for 76,000+ workforce, THE Analysis_System SHALL identify bottlenecks in decision API, ledger writes, and snapshot generation
7. WHEN analyzing high-availability design, THE Analysis_System SHALL verify sweeper advisory locks, outbox relay mechanisms, and failure recovery
8. WHEN analyzing disaster recovery, THE Analysis_System SHALL identify missing backup procedures, restore verification, and chain integrity validation post-restore
9. WHEN analyzing performance against p95 < 30ms decision API target, THE Analysis_System SHALL identify latency sources and optimization opportunities
10. WHEN analyzing circuit breakers and rate limiting, THE Analysis_System SHALL identify missing resilience patterns for external dependencies

### Requirement 3: Code Quality and Security Vulnerability Assessment

**User Story:** As a security engineer, I want identification of all code-level vulnerabilities and bugs, so that I can remediate security risks before production deployment.

#### Acceptance Criteria

1. WHEN analyzing authentication and authorization, THE Analysis_System SHALL identify weaknesses in HTTP Basic auth, RBAC implementation, and credential management
2. WHEN analyzing concurrency and race conditions, THE Analysis_System SHALL identify unsafe shared state, inadequate locking, and transaction boundary issues
3. WHEN analyzing transaction boundaries, THE Analysis_System SHALL verify ACID guarantees for multi-purpose consent capture and withdrawal operations
4. WHEN analyzing error handling, THE Analysis_System SHALL identify unhandled exceptions, insufficient logging, and fail-open vs fail-closed mismatches
5. WHEN analyzing idempotency key collision risks, THE Analysis_System SHALL verify uniqueness enforcement across distributed offline-first devices
6. WHEN analyzing hash chain integrity, THE Analysis_System SHALL verify continuous verification, tampering detection, and genesis hash handling
7. WHEN analyzing clock skew handling, THE Analysis_System SHALL verify sequence number conflict resolution for distributed field force devices
8. WHEN analyzing SQL injection and data validation, THE Analysis_System SHALL verify prepared statements, input sanitization, and output encoding
9. WHEN analyzing sensitive data logging, THE Analysis_System SHALL identify exposure of PII, credentials, or cryptographic material in logs
10. WHEN analyzing identifier hashing, THE Analysis_System SHALL verify pepper usage, hash algorithm strength, and reversibility prevention

### Requirement 4: Production-Grade Feature Completeness Assessment

**User Story:** As a DevOps engineer, I want identification of all missing production-grade operational features, so that I can ensure the system is deployable and maintainable in production.

#### Acceptance Criteria

1. WHEN analyzing observability, THE Analysis_System SHALL identify gaps in metrics exposition, distributed tracing, structured logging, and correlation ID propagation
2. WHEN analyzing monitoring and alerting, THE Analysis_System SHALL verify alerting rules for failed evidence writes, outbox backlog, rights SLA breaches, and integrity failures
3. WHEN analyzing backup and restore procedures, THE Analysis_System SHALL verify automated backup schedules, point-in-time recovery, and restore testing procedures
4. WHEN analyzing key rotation mechanisms, THE Analysis_System SHALL verify procedures for identifier pepper rotation, snapshot signing key rotation, and DPA key rotation
5. WHEN analyzing audit trail completeness, THE Analysis_System SHALL verify admin actions logging, access logging, and immutable audit records
6. WHEN analyzing provenance tracking, THE Analysis_System SHALL verify third-party data source recording, acquisition date tracking, and quarantine enforcement
7. WHEN analyzing retention policy enforcement, THE Analysis_System SHALL verify automated retention sweepers, pre-erasure notices, and proposal-not-deletion semantics
8. WHEN analyzing breach detection and notification, THE Analysis_System SHALL verify two-stage DPDP Rule 7 clock, affected population calculation, and multi-jurisdiction notification deadlines
9. WHEN analyzing rights request fulfillment (DSAR), THE Analysis_System SHALL verify intake workflows, statutory clock tracking, federated retrieval, and grievance routing
10. WHEN analyzing API rate limiting, THE Analysis_System SHALL identify missing throttling controls for decision API and ingestion endpoints

### Requirement 5: Technology Stack Risk Assessment

**User Story:** As a technical lead, I want evaluation of technology choices and their associated risks, so that I can make informed decisions about stack adoption and tuning.

#### Acceptance Criteria

1. WHEN analyzing Java 21 adoption, THE Analysis_System SHALL identify compatibility risks, support lifecycle, and virtual threads usage
2. WHEN analyzing PostgreSQL configuration, THE Analysis_System SHALL verify partitioning strategy for consent_event table, connection pool sizing, and query optimization
3. WHEN analyzing Kafka justification, THE Analysis_System SHALL evaluate necessity versus simpler event bus alternatives for current scale
4. WHEN analyzing Redis snapshot cache, THE Analysis_System SHALL verify expiry strategy, eviction policy, and cache warming procedures
5. WHEN analyzing OPA/Rego policy engine integration, THE Analysis_System SHALL verify policy compilation, evaluation performance, and policy versioning
6. WHEN analyzing service mesh considerations, THE Analysis_System SHALL evaluate necessity for mTLS, circuit breaking, and observability at current scale
7. WHEN analyzing container orchestration, THE Analysis_System SHALL verify Kubernetes manifests, health checks, and rolling update strategies
8. WHEN analyzing CI/CD pipeline, THE Analysis_System SHALL identify gaps in automated testing, security scanning, and deployment automation
9. WHEN analyzing dependency management, THE Analysis_System SHALL verify Spring Boot 3.5.16 compatibility, CVE scanning, and update procedures
10. WHEN analyzing database schema evolution, THE Analysis_System SHALL verify Flyway migration safety, rollback procedures, and zero-downtime deployment support

### Requirement 6: Documentation Completeness Assessment

**User Story:** As a platform consumer, I want comprehensive documentation of all integration points and operational procedures, so that I can successfully integrate with and operate the consent system.

#### Acceptance Criteria

1. WHEN analyzing API documentation, THE Analysis_System SHALL verify OpenAPI completeness, authentication examples, and error response documentation
2. WHEN analyzing deployment runbooks, THE Analysis_System SHALL identify missing procedures for initial deployment, upgrades, and rollbacks
3. WHEN analyzing operational procedures, THE Analysis_System SHALL verify completeness of OPERATIONS.md coverage for all runtime scenarios
4. WHEN analyzing integration guides, THE Analysis_System SHALL verify documentation for DenCRM, DenSFA, iSFA, myDEN, dialer, and HRMS integration
5. WHEN analyzing SDK documentation, THE Analysis_System SHALL verify TypeScript, Kotlin, Swift, Flutter, and React Native SDK guides
6. WHEN analyzing migration guides, THE Analysis_System SHALL identify missing data migration procedures from legacy systems
7. WHEN analyzing rollback procedures, THE Analysis_System SHALL verify documented rollback steps for all deployment scenarios
8. WHEN analyzing architecture decision records (ADRs), THE Analysis_System SHALL verify documentation of key design choices and their rationale
9. WHEN analyzing security documentation, THE Analysis_System SHALL verify threat model, security controls, and incident response procedures
10. WHEN analyzing compliance documentation, THE Analysis_System SHALL verify mapping of implementation to regulatory requirements

### Requirement 7: Test Coverage Gap Assessment

**User Story:** As a QA engineer, I want identification of all testing gaps, so that I can ensure adequate test coverage before production deployment.

#### Acceptance Criteria

1. WHEN analyzing load testing, THE Analysis_System SHALL identify missing performance tests for 76,000+ workforce and field force device count
2. WHEN analyzing security testing, THE Analysis_System SHALL identify missing penetration testing, vulnerability scanning, and security regression tests
3. WHEN analyzing compliance testing, THE Analysis_System SHALL identify gaps in DPDP, TRAI, GDPR, and multi-jurisdiction regulation validation
4. WHEN analyzing multi-jurisdiction test coverage, THE Analysis_System SHALL verify test cases for India, UK, Malaysia, Singapore, and Korea specific rules
5. WHEN analyzing chaos engineering, THE Analysis_System SHALL identify missing fault injection, partition tolerance, and Byzantine failure tests
6. WHEN analyzing property-based testing, THE Analysis_System SHALL identify opportunities for generative testing of hash chain integrity, idempotency, and conflict resolution
7. WHEN analyzing integration test coverage, THE Analysis_System SHALL verify coverage of DenCRM, DenSFA, iSFA, myDEN, and external system integration
8. WHEN analyzing end-to-end test coverage, THE Analysis_System SHALL verify user journey tests for consent capture, withdrawal, DSAR, and breach notification workflows
9. WHEN analyzing snapshot verification testing, THE Analysis_System SHALL verify offline-first scenarios, clock skew handling, and out-of-order event processing
10. WHEN analyzing regression test coverage, THE Analysis_System SHALL verify protection against known bugs and compliance violations

### Requirement 8: Industry Standards Comparison

**User Story:** As a technical architect, I want comparison against industry standards and open source implementations, so that I can identify gaps and opportunities for improvement.

#### Acceptance Criteria

1. WHEN comparing against ISO/IEC TS 27560:2023, THE Analysis_System SHALL verify consent record structure compliance and identify deviations
2. WHEN comparing against ISO/IEC 29184:2020, THE Analysis_System SHALL verify notice layer compliance and identify gaps
3. WHEN comparing against W3C Data Privacy Vocabulary (DPV), THE Analysis_System SHALL verify purpose and legal basis taxonomy alignment
4. WHEN comparing against MeitY BRD specifications, THE Analysis_System SHALL identify missing functional requirements
5. WHEN comparing against DEPA/ReBIT Account Aggregator specs, THE Analysis_System SHALL verify consent artefact model alignment
6. WHEN comparing against tsi-coop/tsi-dpdp-cms (Apache-2.0, 413 commits), THE Analysis_System SHALL identify functional gaps in RoPA, grievance workflows, breach detection, s.9 parental consent, and court-ready evidence
7. WHEN comparing against 68publishers/consent-management-platform, THE Analysis_System SHALL evaluate schema design and admin console patterns
8. WHEN comparing against osano/cookieconsent (2B+ impressions/month), THE Analysis_System SHALL evaluate UX patterns and Consent Mode integration
9. WHEN comparing against c15t/c15t (Apache-2.0, self-hostable), THE Analysis_System SHALL evaluate web capture layer and backend architecture
10. WHEN comparing against consent management best practices, THE Analysis_System SHALL verify append-only event sourcing, CQRS, policy-as-code, offline-first, and zero-trust patterns

### Requirement 9: Prioritized Remediation Plan Generation

**User Story:** As a program manager, I want a prioritized action plan with P0-P3 classifications, so that I can allocate resources and schedule remediation work aligned with regulatory deadlines.

#### Acceptance Criteria

1. WHEN generating P0 priorities, THE Analysis_System SHALL identify critical security and compliance gaps that must be fixed before Phase 1 completion
2. WHEN generating P1 priorities, THE Analysis_System SHALL identify production-readiness blockers that must be fixed before Phase 2 Denave pilot
3. WHEN generating P2 priorities, THE Analysis_System SHALL identify performance and scalability issues that must be fixed before Phase 4 group rollout
4. WHEN generating P3 priorities, THE Analysis_System SHALL identify nice-to-have improvements that can be deferred post-production
5. WHEN estimating remediation effort, THE Analysis_System SHALL provide story point estimates for each identified gap
6. WHEN sequencing remediation work, THE Analysis_System SHALL identify dependencies between issues
7. WHEN grouping related issues, THE Analysis_System SHALL cluster issues by affected component or subsystem
8. WHEN identifying quick wins, THE Analysis_System SHALL highlight low-effort high-impact fixes
9. WHEN mapping to regulatory deadlines, THE Analysis_System SHALL align priorities with 13 Nov 2026 (Consent Manager) and 13 May 2027 (DPDP substantive) dates
10. WHEN generating executive summary, THE Analysis_System SHALL provide dashboard-ready metrics on issue count by severity and category

### Requirement 10: Risk Assessment and Exposure Quantification

**User Story:** As a compliance officer, I want quantification of regulatory, security, and operational risks, so that I can communicate exposure to executive leadership and prioritize remediation.

#### Acceptance Criteria

1. WHEN assessing regulatory penalty exposure, THE Analysis_System SHALL calculate potential fines based on ₹250cr+ per DPDP violation, Korea PIPA 10% turnover, and GDPR 4%/€20M caps
2. WHEN assessing security breach risks, THE Analysis_System SHALL identify attack vectors, data exposure scenarios, and likelihood ratings
3. WHEN assessing data integrity risks, THE Analysis_System SHALL identify tampering risks, hash chain vulnerabilities, and audit trail gaps
4. WHEN assessing availability risks, THE Analysis_System SHALL identify single points of failure, absence of circuit breakers, and disaster recovery gaps
5. WHEN assessing reputational risks, THE Analysis_System SHALL quantify customer impact of compliance failures and data breaches
6. WHEN assessing timeline slippage risks, THE Analysis_System SHALL identify critical path dependencies and mitigation strategies
7. WHEN assessing third-party dependency risks, THE Analysis_System SHALL evaluate vendor lock-in, OSS license compliance, and supply chain security
8. WHEN assessing operational risks, THE Analysis_System SHALL identify manual procedures, missing automation, and operational complexity
9. WHEN assessing scalability risks, THE Analysis_System SHALL quantify capacity limits and growth headroom
10. WHEN generating risk heat map, THE Analysis_System SHALL visualize likelihood vs impact for all identified risks
