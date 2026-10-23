# Practical DevSecOps CI/CD from Scratch to Production

Welcome to the hands-on DevSecOps engineering lab. This workspace tracks our step-by-step implementation of an enterprise-grade CI/CD pipeline using **Jenkins, SonarQube, Trivy, Docker, Helm, and Kubernetes (Kind)**.

---

## Architecture Topology

```
[ Developer Commit ]
         │
         ▼
[ Jenkins Controller ] (Docker: jenkins-controller :8080)
         │  (Dispatches workload via JNLP)
         ▼
[ Jenkins Inbound Agent ] (Docker: jenkins-agent)
   ├── Java 21 & Maven 3.9 (Build & Test)
   ├── Docker Socket (Container Packaging)
   ├── Trivy CLI (SAST / Secret / SCA / Image Scan)
   ├── SonarQube Scanner (Code Quality via :8090)
   ├── Helm 3 (Chart packaging & templating)
   └── Kubectl (Deployments to Kind Cluster)
         │
         ▼
[ Deployment Target: Kind Cluster ] (Running on Local Laptop / Tunneled)
```

---

## Progressive Learning Roadmap

| Phase | Milestone | Focus Areas | Status |
|---|---|---|---|
| **Phase 1** | **Core CI Pipeline** | Agent topology, Maven Build, Unit Tests, JaCoCo Coverage, Artifact Archiving | 🟡 In Progress |
| **Phase 2** | **SAST & Quality Gate** | SonarQube integration, Security Hotspots, Quality Gate enforcement | ⚪ Pending |
| **Phase 3** | **Hardened Containerization** | Non-root Dockerfile, multi-stage builds, immutable semantic tagging | ⚪ Pending |
| **Phase 4** | **Shift-Left Security (Trivy)** | Secret scanning, SCA dependency checks, container vulnerability gating | ⚪ Pending |
| **Phase 5** | **EC2 to Laptop Kind Networking** | SSH reverse tunnel / Tailscale, Kubeconfig management, Jenkins credentials | ⚪ Pending |
| **Phase 6** | **Helm & Production CD** | Parameterized charts, Pod Security Standards, `helm --atomic`, auto-rollback | ⚪ Pending |

---

## Documentation Index

- [Phase 1: CI Foundations & Maven Build](docs/01-ci-foundations.md)
  - [Section 1-7: Architecture, Code Deep-Dive & Failure Analysis](docs/01-ci-foundations.md#1-architectural-overview)
  - [Section 8: Jenkins UI Practical Setup & Execution Guide](docs/01-ci-foundations.md#8-jenkins-ui-practical-walkthrough-creating--running-the-job)

---

## Phase 1 Quick-Start: Setting Up Jenkins Job

Follow these exact steps inside your Jenkins dashboard (`http://<EC2-IP>:8080`):

1. **Create Item**: Click **New Item** → Name: `devsecops-pipeline-phase1` → Select **Pipeline** → Click **OK**.
2. **SCM Configuration**:
   - Scroll to **Pipeline** at the bottom.
   - **Definition**: Select `Pipeline script from SCM`.
   - **SCM**: Select `Git`.
   - **Repository URL**: `https://github.com/SeshadriRC/Jenkins-Basics-To-Production.git`
   - **Credentials**: `- none -` (public repository).
   - **Branch Specifier**: `*/main` *(change from default `*/master`)*.
   - **Script Path**: `my-practice/Jenkinsfile.phase1` *(type the relative path; Jenkins does not auto-browse GitHub)*.
3. **Execute**:
   - Click **Save**.
   - Click **Build Now** on the left menu.
4. **Verify**:
   - Check **Console Output** for Maven build & JUnit test execution.
   - Verify green stages in **Stage View**.
   - Check **Test Result** (`2 tests / 0 failures`).
   - Download the built uber-JAR from **Last Successful Artifacts**.

For complete details, parameter breakdowns, and troubleshooting, read the full [Phase 1 Guide](docs/01-ci-foundations.md).

