# Implementation Plan: UDS Consent Management System Production Readiness Analysis

## Overview

This implementation plan converts the Production Readiness Analysis design into discrete, executable coding tasks. The analysis system will evaluate the existing UDS consent management platform against regulatory requirements (DPDP, TRAI, GDPR, PIPA, PDPA), architectural soundness, code quality, security vulnerabilities, production readiness, and industry best practices.

The implementation follows a dependency-aware approach where foundational components (data models, collectors) are built first, followed by analyzers and rule engines, then risk quantification and report generation.

## Tasks

- [ ] 1. Project setup and core infrastructure
  - [x] 1.1 Create Maven project structure with multi-module layout
    - Create parent POM with dependency management
    - Create modules: analysis-core, collectors, analyzers, rule-engines, reports, cli
    - Configure Java 21 with preview features enabled
    - Set up JUnit 5, AssertJ, Mockito for testing
    - Configure Maven plugins: compiler, surefire, jar-with-dependencies
    - _Requirements: All (foundational infrastructure)_
    - _Priority: P0_
    - _Effort: 3 SP_

  - [-] 1.2 Add core dependencies and library versions
    - Add JavaParser 3.25.x for AST analysis
    - Add SpotBugs 4.8.x annotations
    - Add PMD 7.0.x for code analysis
    - Add OWASP Dependency-Check Maven plugin 9.0.x
    - Add Jackson 2.16.x for JSON processing
    - Add Commons IO, Commons Lang3, Guava for utilities
    - Add SLF4J + Logback for logging
    - Add Picocli 4.7.x for CLI framework
    - _Requirements: 3.8, 5.8_
    - _Priority: P0_
    - _Effort: 2 SP_

  - [-] 1.3 Set up logging framework with structured output
    - Configure Logback with JSON encoder
    - Create log levels: ERROR, WARN, INFO, DEBUG
    - Implement correlation ID propagation pattern
    - Create logging utility class with contextual methods
    - Configure separate log files for analysis stages
    - _Requirements: 4.1, 4.5_
    - _Priority: P0_
    - _Effort: 2 SP_

  - [ ] 1.4 Create core configuration framework
    - Implement Configuration class with builder pattern
    - Support loading from YAML, JSON, and command-line args
    - Define configuration schema: repository path, output directory, enabled analyzers, rule sets
    - Implement validation for configuration parameters
    - Create default configuration with sensible defaults
    - _Requirements: All (cross-cutting)_
    - _Priority: P0_
    - _Effort: 3 SP_

- [ ] 2. Core data models and interfaces
  - [~] 2.1 Implement Finding data model
    - Create Finding class with id, category, severity, priority, title, description
    - Implement Location class for file path, line numbers, component
    - Implement Evidence class for code snippets, config samples
    - Create FindingCategory enum: REGULATORY, ARCHITECTURAL, CODE_QUALITY, SECURITY, PRODUCTION, TECHNOLOGY, DOCUMENTATION, TEST_COVERAGE, STANDARDS
    - Create Severity enum: CRITICAL, HIGH, MEDIUM, LOW
    - Create Priority enum: P0, P1, P2, P3
    - Add builder pattern for fluent Finding creation
    - Implement equals, hashCode, toString
    - _Requirements: 9.1, 9.2, 9.3, 9.4_
    - _Priority: P0_
    - _Effort: 3 SP_

  - [~] 2.2 Implement RiskAssessment data model
    - Create RiskAssessment class with category, title, description
    - Create RiskCategory enum: REGULATORY, SECURITY, AVAILABILITY, TIMELINE, OPERATIONAL
    - Implement Impact class with level, description, financial value, affected users
    - Create ImpactLevel enum: NEGLIGIBLE, LOW, MEDIUM, HIGH, CRITICAL
    - Add likelihood (0.0-1.0), residual likelihood, mitigation fields
    - Implement risk score calculation: likelihood × impact
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9_
    - _Priority: P0_
    - _Effort: 2 SP_

  - [~] 2.3 Implement ComplianceGap data model
    - Create ComplianceGap class extending Finding
    - Add regulation field: DPDP, TRAI, GDPR, PIPA, PDPA
    - Create Jurisdiction enum: INDIA, UK, EU, KOREA, MALAYSIA, SINGAPORE
    - Add section, requirement, implementation status fields
    - Create ImplementationStatus enum: MISSING, PARTIAL, COMPLETE
    - Add regulatory deadline field with LocalDate
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_
    - _Priority: P0_
    - _Effort: 2 SP_

  - [~] 2.4 Implement SecurityVulnerability data model
    - Create SecurityVulnerability class extending Finding
    - Add cweId, owaspCategory fields
    - Implement CvssScore class with v3.1 metrics
    - Create AttackVector enum: NETWORK, ADJACENT, LOCAL, PHYSICAL
    - Create AttackComplexity enum: LOW, HIGH
    - Add exploitAvailable, affectedComponents fields
    - Implement CVSS score calculation method
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.8_
    - _Priority: P0_
    - _Effort: 3 SP_

  - [~] 2.5 Implement ArchitecturalIssue and RemediationItem models
    - Create ArchitecturalIssue extending Finding
    - Add ArchitectureCategory enum: SCALABILITY, RELIABILITY, MAINTAINABILITY, SECURITY
    - Add affectedComponents, pattern, antiPattern fields
    - Create RemediationItem class with finding reference
    - Add Phase enum: PHASE_1, PHASE_2, PHASE_4, POST_PROD
    - Add effortStoryPoints, targetDate, dependencies, conflicts fields
    - Create RemediationStatus enum: NOT_STARTED, IN_PROGRESS, COMPLETED
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10; 9.5, 9.6_
    - _Priority: P0_
    - _Effort: 3 SP_

  - [~] 2.6 Implement report data models
    - Create AnalysisResult class with findings list, errors, completeness map
    - Create AnalysisError class with analyzer name, message, severity
    - Create CompletenessStatus enum with percentage
    - Create ComparisonMatrix class for feature comparison
    - Create FeatureSupport enum: FULL, PARTIAL, NONE, UNKNOWN
    - Create report metadata classes: AnalysisMetadata, Summary
    - _Requirements: 6.1, 6.2, 8.6, 8.7, 8.8, 8.9, 8.10_
    - _Priority: P0_
    - _Effort: 2 SP_

- [~] 3. Checkpoint - Core models complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 4. Implement collectors
  - [~] 4.1 Implement SourceCodeCollector
    - Create CodeRepository class to hold parsed code structure
    - Implement recursive Java file scanning using Files.walk
    - Integrate JavaParser for AST parsing of .java files
    - Extract classes, methods, annotations, imports from AST
    - Parse Flyway SQL migrations from db/migration/ directory
    - Parse application.yml, docker-compose.yml configuration files
    - Build package structure map
    - Handle parse errors gracefully (log and continue)
    - _Requirements: 1.1, 2.1, 3.1, 5.1_
    - _Priority: P0_
    - _Effort: 5 SP_

  - [~] 4.2 Implement DocumentationCollector
    - Scan docs/ directory for .md files recursively
    - Parse markdown structure (headers, code blocks, links)
    - Extract README.md, OPERATIONS.md, REGULATORY_HANDOFF.md
    - Detect OpenAPI specification files (swagger.yml, openapi.json)
    - Calculate documentation completeness scores per type
    - Create DocumentationType enum: API, DEPLOYMENT, OPERATIONS, INTEGRATION, SECURITY, COMPLIANCE
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.8, 6.9, 6.10_
    - _Priority: P0_
    - _Effort: 3 SP_

  - [~] 4.3 Implement TestCollector
    - Scan src/test/ directories across all Maven modules
    - Parse JUnit test files with JavaParser
    - Classify tests: unit (no external dependencies), integration (@SpringBootTest), property-based (contains generators)
    - Extract test method names, assertions, setup/teardown
    - Parse JaCoCo coverage reports (XML) if present
    - Calculate coverage metrics: line, branch, instruction coverage
    - Analyze test quality: assertions per test, test isolation
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.6, 7.8, 7.10_
    - _Priority: P0_
    - _Effort: 5 SP_

  - [~] 4.4 Implement DependencyCollector
    - Parse pom.xml files using Maven model reader
    - Extract direct and transitive dependencies
    - Query OWASP Dependency-Check CVE database
    - Query NVD API for vulnerability data (with caching)
    - Check dependency versions against Maven Central latest
    - Extract license information from POM metadata
    - Identify license compatibility issues (GPL, AGPL mixing)
    - _Requirements: 5.9, 10.7_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 4.5 Implement MetricsCollector
    - Calculate cyclomatic complexity using JavaParser AST traversal
    - Calculate method length, class length, nesting depth
    - Detect code duplication using token-based comparison
    - Calculate comment density (comment lines / total lines)
    - Count lines of code, excluding comments and whitespace
    - Generate metrics per class, package, and module
    - _Requirements: 3.2, 3.3, 5.2_
    - _Priority: P1_
    - _Effort: 5 SP_

- [~] 5. Checkpoint - Collectors complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Implement rule engines
  - [~] 6.1 Create ComplianceRuleEngine framework
    - Define ComplianceRule interface with id, jurisdiction, regulation, section, requirement, check predicate
    - Implement RuleRepository to load rules from JSON files
    - Create rule execution engine with parallel rule evaluation
    - Implement rule result aggregation
    - Add rule enablement/disablement configuration
    - Handle rule execution exceptions (log and continue)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6_
    - _Priority: P0_
    - _Effort: 5 SP_

  - [~] 6.2 Implement DPDP Act 2023 compliance rules
    - Rule: DPDP-S5 - Lawful processing grounds verification
    - Rule: DPDP-S6-1 - Affirmative action (no pre-ticked checkboxes)
    - Rule: DPDP-S6-2 - Separate consent per purpose (no bundling)
    - Rule: DPDP-S6-3 - Withdrawal mechanism as easy as granting
    - Rule: DPDP-S7 - Notice layer completeness (identity, purpose, grievance officer)
    - Rule: DPDP-S8 - Rights request fulfillment (access, correction, erasure)
    - Rule: DPDP-S9 - Children's consent (parental verification, no behavioral tracking under-18)
    - Rule: DPDP-Rule-2 - 22 language notice support
    - Rule: DPDP-Rule-4 - Consent Manager interoperability (13 Nov 2026)
    - Rule: DPDP-Rule-7 - Breach notification (72h to board, immediate to affected)
    - Rule: DPDP-Rule-8 - Dark pattern prohibition
    - _Requirements: 1.1, 1.7, 1.8, 1.9, 1.10_
    - _Priority: P0_
    - _Effort: 8 SP_

  - [~] 6.3 Implement TRAI TCCCPR compliance rules
    - Rule: TRAI-DLT - DLT registration verification
    - Rule: TRAI-TRANS-7DAY - Transactional consent 7-day expiry
    - Rule: TRAI-INFERRED - Inferred consent contract-lifetime expiry
    - Rule: TRAI-DND - DND/NCPR scrubbing integration
    - Rule: TRAI-EXPIRY - Expiry timestamp on all consent records
    - Rule: TRAI-SWEEP - Automated expiry sweeper existence
    - _Requirements: 1.2_
    - _Priority: P0_
    - _Effort: 5 SP_

  - [~] 6.4 Implement GDPR and ePrivacy compliance rules
    - Rule: GDPR-A6 - Lawful basis for processing
    - Rule: GDPR-A7 - Conditions for consent (clear, affirmative action)
    - Rule: GDPR-A13/14 - Information to be provided to data subject
    - Rule: GDPR-A15-22 - Data subject rights (access, rectification, erasure, portability, object)
    - Rule: GDPR-A33/34 - Breach notification (72h to SA, immediate to subjects)
    - Rule: GDPR-A44-49 - International data transfer controls
    - Rule: EPRIVACY-A5 - Cookie consent (explicit for non-essential)
    - Rule: GDPR-A6(1)(f) - Legitimate interest assessment documentation
    - _Requirements: 1.3_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 6.5 Implement multi-jurisdiction compliance rules
    - Rule: PIPA-KOREA - Separate consent per purpose (stricter than GDPR)
    - Rule: PIPA-KOREA - Consent Manager registration with PIPC
    - Rule: PDPA-MALAYSIA - Biometric data special handling
    - Rule: PDPA-MALAYSIA - Cross-border transfer consent
    - Rule: PDPA-SINGAPORE - DNC registry integration
    - Rule: PDPA-SINGAPORE - Data breach notification to PDPC
    - Rule: DPDP-XBORDER - Rule 15 blacklist model for transfers
    - Rule: DPDP-XBORDER - Rule 13 SDF transfer restrictions
    - _Requirements: 1.4, 1.10_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 6.6 Implement SecurityRuleEngine with OWASP patterns
    - Rule: CWE-89 - SQL injection detection (non-parameterized queries)
    - Rule: CWE-79 - XSS detection (unescaped user input in responses)
    - Rule: CWE-287 - Broken authentication (weak auth mechanisms)
    - Rule: CWE-359 - Sensitive data exposure (PII in logs)
    - Rule: CWE-639 - Broken access control (missing authorization checks)
    - Rule: CWE-502 - Insecure deserialization detection
    - Rule: CWE-778 - Insufficient logging for security events
    - Rule: CWE-798 - Hardcoded secrets detection
    - Implement CVSS v3.1 score calculation for each vulnerability
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.8, 3.9_
    - _Priority: P0_
    - _Effort: 8 SP_

  - [~] 6.7 Implement QualityRuleEngine with PMD patterns
    - Rule: Cyclomatic complexity > 15 per method
    - Rule: Method length > 50 lines
    - Rule: Class length > 500 lines
    - Rule: Parameter count > 5 per method
    - Rule: Nesting depth > 4 levels
    - Rule: Code duplication > 5% across codebase
    - Rule: Comment density outside 10-20% range
    - Anti-pattern: God Class detection
    - Anti-pattern: Long Method detection
    - Anti-pattern: Feature Envy detection
    - Anti-pattern: Dead Code detection
    - _Requirements: 3.2, 3.3, 3.4_
    - _Priority: P1_
    - _Effort: 5 SP_

- [~] 7. Checkpoint - Rule engines complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 8. Implement core analyzers (regulatory and architectural)
  - [~] 8.1 Implement RegulatoryComplianceAnalyzer
    - Integrate ComplianceRuleEngine with DPDP, TRAI, GDPR, PIPA, PDPA rules
    - Execute all enabled compliance rules against CodeRepository
    - Generate ComplianceGap findings for violations
    - Map violations to regulatory sections and deadlines
    - Classify severity: CRITICAL for statutory violations, HIGH for partial compliance
    - Generate evidence with file paths and code snippets
    - Calculate compliance percentage per regulation
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10_
    - _Priority: P0_
    - _Effort: 8 SP_

  - [~] 8.2 Implement ArchitecturalSoundnessAnalyzer
    - Detect multi-entity isolation patterns (RLS policies, entity access guards)
    - Analyze offline-first implementation (snapshot signing, idempotency keys)
    - Validate event sourcing (append-only checks, projection correctness)
    - Check signed snapshot security (Ed25519 key usage, signature verification)
    - Analyze cache invalidation strategies (Redis configuration)
    - Identify scalability bottlenecks (N+1 queries, missing indexes)
    - Verify high-availability patterns (advisory locks, outbox relay)
    - Check circuit breaker and rate limiting implementations
    - Analyze database schema for partitioning, connection pooling
    - Generate ArchitecturalIssue findings with affected components
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 2.7, 2.8, 2.9, 2.10_
    - _Priority: P0_
    - _Effort: 8 SP_

  - [~] 8.3 Implement CodeQualityAnalyzer
    - Integrate SecurityRuleEngine and QualityRuleEngine
    - Execute security rules to detect vulnerabilities
    - Execute quality rules to detect code smells
    - Analyze transaction boundaries (ACID guarantee verification)
    - Detect concurrency issues (race conditions, unsafe shared state)
    - Verify error handling completeness
    - Check idempotency key handling
    - Verify hash chain integrity validation code
    - Analyze clock skew handling for distributed systems
    - Generate SecurityVulnerability and Finding objects with CVSS scores
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10_
    - _Priority: P0_
    - _Effort: 8 SP_

- [ ] 9. Implement core analyzers (production and technology)
  - [~] 9.1 Implement ProductionReadinessAnalyzer
    - Check observability: OpenTelemetry instrumentation, Prometheus metrics
    - Verify monitoring: alert rules for failed evidence writes, outbox backlog, rights SLA breaches
    - Validate backup/restore procedures documentation
    - Check key rotation mechanisms (identifier pepper, snapshot signing keys)
    - Verify audit trail completeness (admin actions, access logs)
    - Analyze provenance tracking implementation
    - Validate retention policy enforcement (automated sweepers)
    - Check breach detection and notification workflows
    - Verify rights request fulfillment (DSAR workflows, statutory clock tracking)
    - Check API rate limiting implementation
    - Generate ProductionGap findings
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 9.2 Implement TechnologyStackAnalyzer
    - Analyze Java 21 adoption and support lifecycle
    - Verify PostgreSQL configuration (partitioning strategy, connection pool sizing)
    - Evaluate Kafka justification vs simpler alternatives
    - Check Redis configuration (eviction policy, clustering)
    - Verify OPA/Rego integration (policy compilation, evaluation performance)
    - Assess service mesh necessity for current scale
    - Review container orchestration (Kubernetes manifests, health checks)
    - Analyze CI/CD pipeline completeness
    - Check dependency versions against latest stable
    - Verify database schema evolution strategy (Flyway migrations)
    - Generate TechnologyRisk findings
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9, 5.10_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 9.3 Implement DocumentationAnalyzer
    - Verify API documentation completeness (OpenAPI spec)
    - Check deployment runbooks existence and completeness
    - Validate OPERATIONS.md coverage for runtime scenarios
    - Verify integration guides for DenCRM, DenSFA, iSFA, myDEN, dialer, HRMS
    - Check SDK documentation (TypeScript, Kotlin, Swift, Flutter, React Native)
    - Validate migration guides from legacy systems
    - Verify rollback procedures documentation
    - Check architecture decision records (ADRs)
    - Verify security documentation (threat model, incident response)
    - Validate compliance documentation (regulatory mapping)
    - Calculate completeness scores per documentation type
    - Generate DocumentationGap findings
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 9.4 Implement TestCoverageAnalyzer
    - Analyze load testing coverage for 76K+ workforce scale
    - Check security testing coverage (penetration tests, vulnerability scans)
    - Verify compliance testing per jurisdiction
    - Analyze multi-jurisdiction test coverage
    - Check chaos engineering tests (fault injection, partition tolerance)
    - Identify property-based testing opportunities
    - Verify integration test coverage for external systems
    - Check end-to-end test coverage for user journeys
    - Verify offline-first snapshot verification tests
    - Assess regression test coverage
    - Calculate test quality metrics
    - Generate TestGap findings
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9, 7.10_
    - _Priority: P1_
    - _Effort: 5 SP_

- [~] 10. Checkpoint - Core analyzers complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 11. Implement standards and comparison analyzers
  - [~] 11.1 Implement StandardsComplianceAnalyzer
    - Verify ISO/IEC TS 27560:2023 consent record structure compliance
    - Check ISO/IEC 29184:2020 notice layer compliance
    - Validate W3C Data Privacy Vocabulary alignment
    - Compare against MeitY BRD specifications
    - Verify DEPA/ReBIT Account Aggregator consent artefact alignment
    - Check event sourcing pattern implementation
    - Verify CQRS pattern implementation
    - Check offline-first pattern implementation
    - Verify zero-trust architecture patterns
    - Generate StandardsGap findings with industry references
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.10_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 11.2 Implement OSS repository fetcher and analyzer
    - Clone tsi-coop/tsi-dpdp-cms repository from GitHub
    - Clone 68publishers/consent-management-platform repository
    - Clone osano/cookieconsent repository
    - Clone c15t/c15t repository
    - Run SourceCodeCollector on each OSS repository
    - Extract features: RoPA, grievance workflows, breach detection, parental consent, court-ready evidence
    - Build feature support matrix (FULL, PARTIAL, NONE)
    - Handle clone failures gracefully (network issues, missing repos)
    - _Requirements: 8.6, 8.7, 8.8, 8.9_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 11.3 Implement feature comparison matrix generator
    - Define feature list: Hash-chained ledger, CQRS, Offline-first, Multi-entity, RoPA, Grievance workflows, Breach detection, Parental consent, Consent Manager interop, ISO 27560 compliance
    - Compare UDS implementation against each OSS reference
    - Classify support level: FULL, PARTIAL, NONE, UNKNOWN
    - Generate ComparisonMatrix with notes and references
    - Identify gaps where OSS has features UDS lacks
    - Highlight areas where UDS exceeds OSS capabilities
    - Generate recommendations based on comparison
    - _Requirements: 8.6, 8.7, 8.8, 8.9, 8.10_
    - _Priority: P1_
    - _Effort: 5 SP_

- [ ] 12. Implement prioritization and remediation analyzers
  - [~] 12.1 Implement GapSeverityScorer
    - Implement severity scoring algorithm: statutory violation + imminent deadline → CRITICAL
    - Score security vulnerabilities using CVSS >= 9.0 → CRITICAL, >= 7.0 → HIGH
    - Score regulatory violations with deadline mapping
    - Score production blockers (no backup, no monitoring) → HIGH
    - Score performance issues → MEDIUM
    - Score documentation gaps → MEDIUM
    - Score code smells → LOW
    - Apply scoring to all Finding objects
    - _Requirements: 9.1, 9.2, 9.3, 9.4_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 12.2 Implement PriorityClassifier
    - Implement priority classification logic: CRITICAL + Phase 1 → P0
    - Classify security CVSS >= 9.0 → P0
    - Classify (CRITICAL or HIGH) + Phase 2 → P1
    - Classify HIGH + Phase 4 → P2
    - Classify everything else → P3
    - Map findings to phases: Phase 1 (core platform), Phase 2 (Denave pilot), Phase 4 (group rollout)
    - Apply priority to all Finding objects
    - _Requirements: 9.1, 9.2, 9.3, 9.4_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 12.3 Implement EffortEstimator
    - Define base story points per category: regulatory 8 SP, architectural 13 SP, security 5 SP, production 8 SP, documentation 3 SP, test 5 SP, config 2 SP, quality 3 SP
    - Apply complexity multipliers: schema change ×1.5, multi-module ×1.3, third-party integration ×1.4, regulatory review ×1.2
    - Adjust for dependency count: >3 dependencies ×1.2
    - Calculate effort for all RemediationItem objects
    - _Requirements: 9.5_
    - _Priority: P1_
    - _Effort: 2 SP_

  - [~] 12.4 Implement DependencyResolver
    - Build directed graph of Finding dependencies
    - Detect dependency cycles using DFS
    - Log cyclic dependencies as warnings
    - Implement topological sort using Kahn's algorithm
    - Generate dependency-ordered list of RemediationItem objects
    - Handle BLOCKS, RELATED, CONFLICTS dependency types
    - _Requirements: 9.6_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 12.5 Implement RemediationPlanAnalyzer
    - Integrate GapSeverityScorer, PriorityClassifier, EffortEstimator, DependencyResolver
    - Generate prioritized list of RemediationItem objects
    - Group related issues by component/subsystem
    - Identify quick wins (low effort, high impact)
    - Map priorities to regulatory deadlines (13 Nov 2026, 13 May 2027)
    - Generate executive summary metrics (issue count by severity, priority)
    - Calculate total remediation effort in story points
    - Generate remediation roadmap with phases
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8, 9.9, 9.10_
    - _Priority: P1_
    - _Effort: 5 SP_

- [~] 13. Checkpoint - Prioritization complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. Implement risk assessment analyzers
  - [~] 14.1 Implement FinancialRiskCalculator
    - Calculate DPDP penalty exposure: ₹250 crore per violation
    - Calculate PIPA (Korea) penalty: 10% of total turnover for severe tier
    - Calculate GDPR penalty: €20M or 4% of global annual turnover (whichever higher)
    - Calculate PDPA (Malaysia) penalty: RM 500,000 per offense
    - Calculate PDPA (Singapore) penalty: SGD 1M per breach
    - Aggregate penalty exposure across all jurisdictions
    - Generate per-regulation, per-jurisdiction exposure breakdown
    - Convert currencies to common denominator (INR, USD, EUR)
    - _Requirements: 10.1_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 14.2 Implement SecurityRiskScorer
    - Implement CVSS v3.1 base score calculation
    - Calculate Impact Subscore from C/I/A impact metrics
    - Calculate Exploitability Subscore from AV/AC/PR/UI metrics
    - Apply Scope modifier for scope change
    - Calculate final base score (0.0-10.0 scale)
    - Classify vulnerabilities: CRITICAL (9.0-10.0), HIGH (7.0-8.9), MEDIUM (4.0-6.9), LOW (0.1-3.9)
    - Identify attack vectors and exploit availability
    - Generate risk score for all SecurityVulnerability findings
    - _Requirements: 10.2_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 14.3 Implement AvailabilityRiskAssessor
    - Detect single points of failure: database (no replication), Redis (no clustering), external deps (no circuit breakers)
    - Calculate RTO (Recovery Time Objective) for each SPOF
    - Calculate RPO (Recovery Point Objective) for data stores
    - Identify missing circuit breakers for external dependencies
    - Identify cascading failure risks
    - Identify missing disaster recovery procedures
    - Generate SinglePointOfFailure findings with impact
    - _Requirements: 10.4_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 14.4 Implement TimelineRiskAnalyzer
    - Build project network from RemediationItem dependencies
    - Calculate earliest start/finish times (forward pass)
    - Calculate latest start/finish times (backward pass)
    - Identify critical path (zero slack activities)
    - Implement Monte Carlo simulation with 10,000 iterations
    - Use triangular distribution for effort estimates (optimistic, most likely, pessimistic)
    - Calculate probability of meeting Phase 1, Phase 2, Phase 4 gates
    - Identify high-variance tasks requiring buffer
    - Generate TimelineRisk with critical path and probability
    - _Requirements: 10.6_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 14.5 Implement RiskAssessmentAnalyzer
    - Integrate FinancialRiskCalculator, SecurityRiskScorer, AvailabilityRiskAssessor, TimelineRiskAnalyzer
    - Generate RiskAssessment objects for all risk categories
    - Calculate likelihood × impact for each risk
    - Generate risk heat map data (likelihood vs impact matrix)
    - Identify residual risk after mitigation
    - Generate mitigation recommendations for each risk
    - Calculate total risk exposure across all categories
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 10.9, 10.10_
    - _Priority: P1_
    - _Effort: 5 SP_

- [~] 15. Checkpoint - Risk assessment complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 16. Implement report generation
  - [~] 16.1 Implement MarkdownReportGenerator for comprehensive report
    - Generate executive summary section with overall readiness score, issue counts, effort estimate, financial risk
    - Generate regulatory compliance section per jurisdiction with status and critical gaps
    - Generate architectural soundness section with findings by category
    - Generate code quality & security section with vulnerabilities and CVSS scores
    - Generate production readiness section with operational gaps
    - Generate technology stack section with technology risks
    - Generate documentation completeness section with coverage scores
    - Generate test coverage section with metrics and gaps
    - Generate standards compliance section with comparison matrices
    - Generate prioritized remediation roadmap section organized by P0/P1/P2/P3
    - Generate risk assessment section with financial, security, availability, timeline risks
    - Generate comparison section with OSS feature matrices
    - Generate appendices: detailed findings, compliance checklist, risk heat map
    - Write report to comprehensive-report.md
    - _Requirements: 9.10, 10.10_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 16.2 Implement MarkdownReportGenerator for executive summary
    - Extract key metrics: total findings, by severity, by priority, total effort, readiness score
    - Generate 1-page executive summary with critical highlights
    - Include top 5 P0 issues with brief descriptions
    - Include financial risk exposure summary
    - Include compliance status per regulation
    - Include recommended next steps
    - Write report to executive-summary.md
    - _Requirements: 9.10_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 16.3 Implement JSONExporter for machine-readable output
    - Create JSON schema for AnalysisResult
    - Serialize analysisMetadata: analysis date, target system, version, analyzer
    - Serialize summary: total findings, by severity, by priority, total effort, readiness score
    - Serialize findings array with all Finding objects
    - Serialize complianceMatrix with per-regulation, per-jurisdiction breakdown
    - Serialize riskAssessment with financial, security, availability, timeline risks
    - Serialize comparisonMatrix with OSS feature comparison
    - Write JSON to analysis-result.json
    - _Requirements: 9.10_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 16.4 Implement remediation roadmap generator
    - Generate Gantt chart data for remediation timeline
    - Group RemediationItem objects by phase and priority
    - Calculate start/end dates based on dependencies and effort
    - Generate resource allocation recommendations
    - Generate milestone dates aligned with regulatory deadlines
    - Generate critical path visualization data
    - Write roadmap to remediation-roadmap.md
    - _Requirements: 9.9_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 16.5 Implement risk heat map generator
    - Create likelihood (0.0-1.0) × impact (NEGLIGIBLE to CRITICAL) matrix
    - Plot all RiskAssessment objects on heat map
    - Generate HTML/SVG visualization
    - Color code: green (low), yellow (medium), orange (high), red (critical)
    - Include interactive tooltips with risk details
    - Write visualization to risk-heatmap.html
    - _Requirements: 10.10_
    - _Priority: P2_
    - _Effort: 5 SP_

- [ ] 17. Implement analysis pipeline orchestration
  - [~] 17.1 Create AnalysisEngine main orchestrator
    - Implement six-phase pipeline: Discovery → Analysis → Comparison → Prioritization → Risk → Reporting
    - Phase 1: Execute all collectors in parallel (SourceCode, Documentation, Test, Dependency, Metrics)
    - Phase 2: Execute 10 analyzers in parallel (Regulatory, Architectural, CodeQuality, Production, Technology, Documentation, TestCoverage, Standards, Remediation, RiskAssessment)
    - Phase 3: Execute comparison framework (OSS repository comparison)
    - Phase 4: Execute prioritization (Severity, Priority, Effort, Dependencies)
    - Phase 5: Execute risk quantification (Financial, Security, Availability, Timeline)
    - Phase 6: Execute report generation (Markdown comprehensive, Markdown executive, JSON, Roadmap, Heat map)
    - Implement graceful error handling (continue on analyzer failure)
    - Implement progress reporting with phase completion percentage
    - Implement parallelization using ExecutorService with configurable thread pool
    - _Requirements: All (orchestration)_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 17.2 Implement AnalysisResult aggregator
    - Collect findings from all analyzers
    - Merge duplicate findings (same issue detected by multiple analyzers)
    - Calculate overall completeness percentage
    - Aggregate errors from all analyzers
    - Calculate readiness score: weighted average of compliance (40%), security (30%), production (20%), quality (10%)
    - Generate summary statistics
    - _Requirements: All (cross-cutting)_
    - _Priority: P1_
    - _Effort: 3 SP_

- [~] 18. Checkpoint - Analysis pipeline complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 19. Implement CLI interface
  - [~] 19.1 Create command-line interface with Picocli
    - Implement @Command for main analysis command
    - Add --repository-path option (required, path to consent system repo)
    - Add --output-directory option (optional, default: ./analysis-output)
    - Add --enabled-analyzers option (optional, comma-separated list, default: all)
    - Add --rule-sets option (optional, comma-separated list of jurisdictions, default: all)
    - Add --config-file option (optional, path to YAML/JSON config)
    - Add --verbose flag for debug logging
    - Add --quiet flag for errors-only logging
    - Add --help flag for usage information
    - Implement argument validation
    - _Requirements: All (user interface)_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 19.2 Implement progress indicators for CLI
    - Display progress bar for each analysis phase
    - Display spinner during long-running operations (OSS repo cloning)
    - Display completion percentage per analyzer
    - Display estimated time remaining
    - Use ANSI escape codes for colored output
    - Handle non-TTY environments gracefully (CI/CD)
    - _Requirements: All (user experience)_
    - _Priority: P2_
    - _Effort: 3 SP_

  - [~] 19.3 Implement error reporting for CLI
    - Display clear error messages for common failures (repository not found, invalid config)
    - Display stack traces only in verbose mode
    - Display summary of errors at end of analysis
    - Exit with appropriate status codes (0 success, 1 analysis errors, 2 configuration errors)
    - _Requirements: All (error handling)_
    - _Priority: P2_
    - _Effort: 2 SP_

  - [~] 19.4 Implement output directory management
    - Create output directory if not exists
    - Generate timestamped subdirectory for each analysis run
    - Write all reports to output directory
    - Create index.html landing page linking to all reports
    - Display output directory path at end of analysis
    - _Requirements: All (output management)_
    - _Priority: P2_
    - _Effort: 2 SP_

- [ ] 20. Implement testing suite
  - [~] 20.1 Create unit tests for data models
    - Test Finding builder pattern and validation
    - Test RiskAssessment risk score calculation
    - Test ComplianceGap regulatory deadline mapping
    - Test SecurityVulnerability CVSS score calculation
    - Test ArchitecturalIssue and RemediationItem construction
    - Test equals, hashCode, toString methods
    - _Requirements: All (correctness)_
    - _Priority: P1_
    - _Effort: 3 SP_

  - [~] 20.2 Create unit tests for collectors
    - Test SourceCodeCollector Java parsing with valid/invalid files
    - Test DocumentationCollector markdown parsing
    - Test TestCollector JUnit test detection and classification
    - Test DependencyCollector POM parsing and CVE lookup (mocked)
    - Test MetricsCollector complexity calculation
    - Test graceful handling of malformed files
    - _Requirements: All (correctness)_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 20.3 Create unit tests for rule engines
    - Test DPDP compliance rules with violating/compliant code samples
    - Test TRAI compliance rules with expiry handling code
    - Test GDPR compliance rules with consent capture code
    - Test security rules with vulnerable/secure code samples
    - Test quality rules with code smells/clean code
    - Test rule execution exception handling
    - _Requirements: 1.1, 1.2, 1.3, 3.1, 3.8_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 20.4 Create unit tests for analyzers
    - Test RegulatoryComplianceAnalyzer finding generation
    - Test ArchitecturalSoundnessAnalyzer pattern detection
    - Test CodeQualityAnalyzer vulnerability detection
    - Test ProductionReadinessAnalyzer observability checks
    - Test TechnologyStackAnalyzer configuration analysis
    - Test DocumentationAnalyzer completeness scoring
    - Test TestCoverageAnalyzer coverage calculation
    - Test StandardsComplianceAnalyzer standards mapping
    - Mock collectors and rule engines
    - _Requirements: All analyzers_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 20.5 Create unit tests for prioritization components
    - Test GapSeverityScorer scoring algorithm
    - Test PriorityClassifier phase mapping
    - Test EffortEstimator complexity multipliers
    - Test DependencyResolver topological sort
    - Test DependencyResolver cycle detection
    - Test RemediationPlanAnalyzer integration
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 20.6 Create unit tests for risk assessment
    - Test FinancialRiskCalculator penalty calculation per jurisdiction
    - Test SecurityRiskScorer CVSS v3.1 formula
    - Test AvailabilityRiskAssessor SPOF detection
    - Test TimelineRiskAnalyzer critical path calculation
    - Test TimelineRiskAnalyzer Monte Carlo simulation
    - Test RiskAssessmentAnalyzer integration
    - _Requirements: 10.1, 10.2, 10.3, 10.4, 10.6_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 20.7 Create integration tests for analysis pipeline
    - Create sample consent management repository with known issues (50+ issues across categories)
    - Test complete analysis pipeline end-to-end
    - Verify all 10 analyzers execute successfully
    - Verify expected findings are generated for known issues
    - Verify all reports are generated (Markdown, JSON, roadmap)
    - Verify overall completeness > 95%
    - Test graceful error handling (missing files, malformed config)
    - _Requirements: All (integration)_
    - _Priority: P1_
    - _Effort: 8 SP_

  - [~] 20.8 Create regression tests with golden set
    - Create golden set repository with 50 known issues
    - Document each known issue with location and expected severity
    - Test that all known issues are detected
    - Track false positive rate (target < 10%)
    - Track false negative rate (target < 10%)
    - Update golden set as new issues are discovered
    - _Requirements: All (accuracy)_
    - _Priority: P2_
    - _Effort: 5 SP_

- [~] 21. Checkpoint - Testing complete
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 22. Documentation and finalization
  - [~] 22.1 Create README.md with usage guide
    - Write project overview and purpose
    - Document prerequisites (Java 21, Maven)
    - Document build instructions (mvn clean install)
    - Document usage examples (CLI commands)
    - Document configuration options (YAML, command-line)
    - Document output report descriptions
    - Add troubleshooting section
    - Add FAQ section
    - _Requirements: All (documentation)_
    - _Priority: P2_
    - _Effort: 3 SP_

  - [~] 22.2 Create ARCHITECTURE.md
    - Document high-level architecture diagram
    - Document six-phase pipeline flow
    - Document component responsibilities (collectors, analyzers, rule engines, risk assessment, reports)
    - Document data models with class diagrams
    - Document extension points (custom rules, custom analyzers)
    - Document design decisions and rationale
    - _Requirements: All (documentation)_
    - _Priority: P2_
    - _Effort: 3 SP_

  - [~] 22.3 Create EXTENDING.md guide
    - Document how to add new compliance rules
    - Document how to add new security patterns
    - Document how to add new analyzers
    - Document how to add new jurisdictions
    - Document how to customize scoring algorithms
    - Document how to add new report formats
    - Provide code examples for each extension point
    - _Requirements: All (extensibility)_
    - _Priority: P2_
    - _Effort: 3 SP_

  - [~] 22.4 Generate JavaDoc for all public APIs
    - Document all public classes with purpose and usage
    - Document all public methods with parameters, return values, exceptions
    - Document data models with field descriptions
    - Generate JavaDoc HTML with mvn javadoc:javadoc
    - _Requirements: All (API documentation)_
    - _Priority: P2_
    - _Effort: 2 SP_

- [ ] 23. Final integration and validation
  - [~] 23.1 Run analysis on actual UDS consent management system
    - Clone UDS consent system repository
    - Run full analysis with all analyzers enabled
    - Review generated reports for accuracy
    - Validate findings against manual code review
    - Tune rule thresholds to reduce false positives
    - Document any issues or gaps discovered
    - _Requirements: All (validation)_
    - _Priority: P1_
    - _Effort: 5 SP_

  - [~] 23.2 Generate sample analysis report for documentation
    - Run analysis on sample consent system
    - Export comprehensive report, executive summary, JSON, roadmap
    - Sanitize any sensitive information
    - Include reports in docs/examples/ directory
    - Reference sample reports in README.md
    - _Requirements: All (documentation)_
    - _Priority: P2_
    - _Effort: 2 SP_

- [~] 24. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- **Language**: Implementation uses Java (as specified in the design document)
- **Architecture**: Modular design with clear separation between collectors, analyzers, rule engines, and report generators
- **Parallelization**: Collectors and analyzers run in parallel for performance
- **Error Handling**: Graceful degradation - analyzer failures don't block overall analysis
- **Extensibility**: Plugin architecture for custom rules and analyzers
- **Testing**: Comprehensive unit, integration, and regression tests
- **Priorities**:
  - **P0 tasks**: Core framework, data models, key collectors (source, docs, tests), core analyzers (regulatory, architectural, code quality), DPDP/TRAI rule engines, security rule engine
  - **P1 tasks**: All analyzers, all rule engines, comparison framework, prioritization, risk assessment, report generation, CLI, testing
  - **P2 tasks**: CLI enhancements (progress indicators, error reporting), risk heat map visualization, documentation, sample reports

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "1.3", "1.4"] },
    { "id": 2, "tasks": ["2.1", "2.2", "2.3", "2.4", "2.5", "2.6"] },
    { "id": 3, "tasks": ["4.1", "4.2", "4.3", "4.4", "4.5"] },
    { "id": 4, "tasks": ["6.1"] },
    { "id": 5, "tasks": ["6.2", "6.3", "6.4", "6.5", "6.6", "6.7"] },
    { "id": 6, "tasks": ["8.1", "8.2", "8.3"] },
    { "id": 7, "tasks": ["9.1", "9.2", "9.3", "9.4", "11.1", "11.2"] },
    { "id": 8, "tasks": ["11.3", "12.1", "12.2", "12.3", "12.4"] },
    { "id": 9, "tasks": ["12.5", "14.1", "14.2", "14.3", "14.4"] },
    { "id": 10, "tasks": ["14.5"] },
    { "id": 11, "tasks": ["16.1", "16.2", "16.3", "16.4", "16.5"] },
    { "id": 12, "tasks": ["17.1", "17.2"] },
    { "id": 13, "tasks": ["19.1", "19.2", "19.3", "19.4"] },
    { "id": 14, "tasks": ["20.1", "20.2", "20.3", "20.4", "20.5", "20.6"] },
    { "id": 15, "tasks": ["20.7", "20.8"] },
    { "id": 16, "tasks": ["22.1", "22.2", "22.3", "22.4"] },
    { "id": 17, "tasks": ["23.1", "23.2"] }
  ]
}
```
