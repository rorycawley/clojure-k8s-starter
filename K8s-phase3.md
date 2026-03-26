# Phase 3: GitHub Actions CI/CD

Set up automated build, test, and deployment so every push to `main` deploys to staging and production requires a manual approval click.

**Prerequisite:** Phase 1 + 2 complete — infrastructure running, app deployed to staging via manual `helm upgrade`.

**End state:** Push to `main` triggers: `bb check` → Docker build + push → Helm deploy to staging (auto) → Helm deploy to production (after approval). No more manual `docker push` or `helm upgrade`.

---

## Table of Contents

1. [What you're building](#1-what-youre-building)
2. [Step 1 — Create GitHub environments](#2-step-1--create-github-environments)
3. [Step 2 — Configure GitHub secrets](#3-step-2--configure-github-secrets)
4. [Step 3 — Create the workflow file](#4-step-3--create-the-workflow-file)
5. [Step 4 — Understand the workflow](#5-step-4--understand-the-workflow)
6. [Step 5 — Test the pipeline (staging)](#6-step-5--test-the-pipeline)
7. [Step 6 — Test the production gate](#7-step-6--test-the-production-gate)
8. [Step 7 — Verify the full flow](#8-step-7--verify-the-full-flow)
9. [Troubleshooting](#9-troubleshooting)
10. [Checklist — Phase 3 complete](#10-checklist--phase-3-complete)

---

## 1. What you're building

```
 git push to main
       │
       ▼
┌─ build-and-test ─────────────────────────────────────┐
│                                                       │
│  1. Checkout code                                     │
│  2. Install Clojure + bb                              │
│  3. bb check (lint + format + smoke)                  │
│  4. Auth to GCP via Workload Identity Federation      │
│  5. docker build + push to Artifact Registry          │
│     tag: <full-git-sha>                               │
│                                                       │
└───────────────────┬───────────────────────────────────┘
                    │ (automatic)
                    ▼
┌─ deploy-staging ──────────────────────────────────────┐
│                                                       │
│  1. Auth to GCP                                       │
│  2. Get GKE credentials for staging cluster           │
│  3. helm upgrade --install --set image.tag=<sha>      │
│  4. --wait (blocks until pods are healthy)            │
│                                                       │
└───────────────────┬───────────────────────────────────┘
                    │ (waits for approval)
                    ▼
┌─ deploy-production ───────────────────────────────────┐
│                                                       │
│  ⏳ You click "Approve" in GitHub UI                   │
│                                                       │
│  1. Auth to GCP                                       │
│  2. Get GKE credentials for production cluster        │
│  3. helm upgrade --install --set image.tag=<sha>      │
│     (same image that was tested on staging)           │
│  4. --wait                                            │
│                                                       │
└───────────────────────────────────────────────────────┘
```

### How auth works (no GCP keys stored in GitHub)

Workload Identity Federation (WIF) lets GitHub Actions authenticate to GCP without storing any keys:

1. GitHub mints a short-lived OIDC token saying "I am repo X, branch main"
2. GCP validates the token against the WIF pool/provider created by OpenTofu in Phase 1
3. GCP issues temporary credentials (1 hour TTL)
4. The workflow uses those credentials to push images and deploy

The OpenTofu `iam` module already created the WIF pool, provider, and deployer service account. This phase just wires them into GitHub.

---

## 2. Step 1 — Create GitHub environments

Go to your GitHub repo → **Settings** → **Environments**.

### Create `staging` environment

1. Click **New environment**
2. Name: `staging`
3. No protection rules — leave everything unchecked
4. Click **Configure environment**

### Create `production` environment

1. Click **New environment**
2. Name: `production`
3. Check **Required reviewers**
4. Add yourself (or your team) as a reviewer
5. Click **Configure environment**

```
Repo Settings → Environments
├── staging       (no protection — auto-deploys)
└── production    (required reviewer: you)
```

---

## 3. Step 2 — Configure GitHub secrets

You need two values from OpenTofu outputs. Get them:

```bash
cd infra/environments/staging

tofu output workload_identity_provider
tofu output github_deployer_sa
```

The `workload_identity_provider` output looks like:
```
projects/123456789/locations/global/workloadIdentityPools/github-actions-staging/providers/github-oidc
```

The `github_deployer_sa` output looks like:
```
github-deployer-staging@YOUR_PROJECT.iam.gserviceaccount.com
```

### Repository-level secret

Go to repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**:

| Name | Value |
|------|-------|
| `GCP_PROJECT_ID` | Your GCP project ID (e.g., `my-k8s-project-123`) |

### Environment-level secrets — staging

Go to repo → **Settings** → **Environments** → **staging** → **Environment secrets** → **Add secret**:

| Name | Value |
|------|-------|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Output of `tofu output workload_identity_provider` (from staging) |
| `GCP_SERVICE_ACCOUNT` | Output of `tofu output github_deployer_sa` (from staging) |

### Environment-level secrets — production

Go to repo → **Settings** → **Environments** → **production** → **Environment secrets** → **Add secret**:

| Name | Value |
|------|-------|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | Output of `tofu output workload_identity_provider` (from **production** — once Phase 1 is done for production) |
| `GCP_SERVICE_ACCOUNT` | Output of `tofu output github_deployer_sa` (from **production**) |

**If you haven't provisioned production yet:** Skip the production secrets for now. The `deploy-production` job will fail when it tries to auth, but that's fine — staging deploys will still work. Come back and add these when you run Phase 1 for production.

### Verify secrets are set

```
Repo Settings → Secrets → Actions
├── Repository secrets
│   └── GCP_PROJECT_ID
├── Environment: staging
│   ├── GCP_WORKLOAD_IDENTITY_PROVIDER
│   └── GCP_SERVICE_ACCOUNT
└── Environment: production
    ├── GCP_WORKLOAD_IDENTITY_PROVIDER  (skip if prod not provisioned)
    └── GCP_SERVICE_ACCOUNT             (skip if prod not provisioned)
```

---

## 4. Step 3 — Create the workflow file

```bash
mkdir -p .github/workflows
```

Create `.github/workflows/deploy.yaml`:

```yaml
name: Build and Deploy

on:
  push:
    branches: [main]

env:
  REGION: europe-west2
  IMAGE: europe-west2-docker.pkg.dev/${{ secrets.GCP_PROJECT_ID }}/registry-api/registry-api

# Required for Workload Identity Federation OIDC token
permissions:
  id-token: write
  contents: read

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4

      - name: Install Clojure & bb
        uses: DeLaGuardo/setup-clojure@13
        with:
          cli: latest
          bb: latest

      - name: Quality checks
        run: bb check

      - name: Authenticate to GCP
        uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Configure Docker
        run: gcloud auth configure-docker ${{ env.REGION }}-docker.pkg.dev --quiet

      - name: Build and push image
        run: |
          docker build -t ${{ env.IMAGE }}:${{ github.sha }} .
          docker push ${{ env.IMAGE }}:${{ github.sha }}

  deploy-staging:
    needs: build-and-test
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4

      - name: Install Helm
        uses: azure/setup-helm@v5

      - name: Authenticate to GCP
        uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Get GKE credentials
        uses: google-github-actions/get-gke-credentials@v3
        with:
          cluster_name: staging
          location: ${{ env.REGION }}

      - name: Deploy with Helm
        run: |
          helm upgrade --install registry-api helm/registry-api \
            -f helm/registry-api/values-staging.yaml \
            --set image.tag=${{ github.sha }} \
            -n registry-api --create-namespace \
            --wait --timeout 5m

  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production
    steps:
      - uses: actions/checkout@v4

      - name: Install Helm
        uses: azure/setup-helm@v5

      - name: Authenticate to GCP
        uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Get GKE credentials
        uses: google-github-actions/get-gke-credentials@v3
        with:
          cluster_name: production
          location: ${{ env.REGION }}

      - name: Deploy with Helm
        run: |
          helm upgrade --install registry-api helm/registry-api \
            -f helm/registry-api/values-prod.yaml \
            --set image.tag=${{ github.sha }} \
            -n registry-api --create-namespace \
            --wait --timeout 5m
```

---

## 5. Step 4 — Understand the workflow

### Why `environment: staging` on `build-and-test`?

The GCP secrets (`GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`) are environment-scoped, not repo-scoped. The build job needs them to push the Docker image, so it must reference an environment.

### Why `--wait --timeout 5m`?

Helm blocks until all pods are `Ready` or 5 minutes pass. If pods don't become healthy, the job fails and shows red in GitHub — making broken deploys immediately visible.

### Why full SHA for the image tag?

`${{ github.sha }}` is the full 40-character commit SHA. This guarantees:
- Every image is uniquely tagged
- Staging and production deploy the **exact same image** (same SHA)
- You can always trace a running pod back to the exact commit

### What `permissions: id-token: write` does

GitHub Actions needs this permission to mint the OIDC token that WIF validates. Without it, the `google-github-actions/auth` step fails with "Unable to get OIDC token."

### What happens if production isn't provisioned yet?

The `deploy-production` job will fail at the auth step because the production secrets aren't set. That's fine — staging still deploys. Once you provision production (Phase 1 again with production tfvars), add the secrets and it'll work.

---

## 6. Step 5 — Test the pipeline

### First run: push to main

```bash
# Make sure you're on main
git checkout main

# Stage the new files
git add .github/workflows/deploy.yaml

# Commit
git commit -m "Add CI/CD pipeline with GitHub Actions

Deploy to staging on push to main, production with approval gate."

# Push
git push origin main
```

### Watch the run

Go to your repo → **Actions** tab. You should see a workflow run starting.

Click into it to watch the jobs:

```
build-and-test   ● Running    (takes ~3-5 min)
deploy-staging   ○ Waiting    (starts after build)
deploy-production ○ Waiting   (starts after staging, needs approval)
```

### What to expect on the first run

1. **build-and-test**: Installs Clojure/bb, runs `bb check`, builds Docker image, pushes to Artifact Registry
2. **deploy-staging**: Authenticates, gets GKE credentials, runs `helm upgrade`. Since you already deployed manually in Phase 2, this upgrades the existing release with the new image tag
3. **deploy-production**: Waits for approval. If production isn't provisioned, click "Reject" or let it time out

---

## 7. Step 6 — Test the production gate

If production is provisioned and secrets are set:

1. After `deploy-staging` succeeds, you'll see a yellow banner: **"Review deployments"**
2. Click **Review deployments**
3. Check the **production** checkbox
4. Click **Approve and deploy**
5. Watch `deploy-production` run

If you don't want to deploy to production yet, just leave it pending or click **Reject**.

---

## 8. Step 7 — Verify the full flow

### Make a small code change

```bash
git checkout -b test/cicd-verify

# Make a trivial change — e.g., add a comment
echo ";; CI/CD test" >> src/registry_api/main.clj

git add src/registry_api/main.clj
git commit -m "Test CI/CD pipeline"
git push -u origin test/cicd-verify

# Create and merge a PR
gh pr create --title "Test CI/CD" --body "Verifying the pipeline works end-to-end"
gh pr merge test/cicd-verify --squash --delete-branch
```

### Watch the deployment

1. Go to **Actions** tab — a new run should start from the merge to main
2. **build-and-test** runs `bb check` and pushes a new image
3. **deploy-staging** deploys the new image
4. After staging succeeds, check the app:

```bash
STAGING_IP=$(kubectl get ingress registry-api -n registry-api \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}' --context gke-staging)
curl http://$STAGING_IP/health
```

5. The running pods should have the new image:

```bash
kubectl get pods -n registry-api -o jsonpath='{.items[0].spec.containers[0].image}' --context gke-staging
# Should end with the full SHA of your merge commit
```

### Revert the test change

```bash
git checkout main
git pull
# Remove the test comment you added
# Edit src/registry_api/main.clj
git add src/registry_api/main.clj
git commit -m "Remove CI/CD test comment"
git push origin main
```

---

## 9. Troubleshooting

### "Unable to get OIDC token" or "Error: Unable to detect a credential"

The `permissions` block is missing or wrong. Ensure at the top level of the workflow:

```yaml
permissions:
  id-token: write
  contents: read
```

### "Error: google-github-actions/auth failed with: unable to retrieve..."

The WIF provider string in the GitHub secret is wrong. Verify:

```bash
cd infra/environments/staging
tofu output workload_identity_provider
```

Copy the **exact** output (including `projects/...`) into the `GCP_WORKLOAD_IDENTITY_PROVIDER` environment secret. No trailing whitespace.

### "Workload Identity Federation: the caller does not have permission"

The deployer SA doesn't have the right bindings. Check:

```bash
# The WIF pool should allow the repo
gcloud iam workload-identity-pools providers describe github-oidc \
  --workload-identity-pool=github-actions-staging \
  --location=global \
  --format="value(attributeCondition)"
# Should print: assertion.repository == 'your-org/clojure-k8s-starter'
```

Verify the repo path matches **exactly** — case-sensitive, no `.git` suffix.

### "bb check" fails (lint, format, or smoke)

Fix the code locally and push again. The pipeline is working correctly — it caught a real issue.

### Docker push fails with "denied: Permission denied"

The deployer SA doesn't have `roles/artifactregistry.writer`. Check:

```bash
gcloud artifacts repositories get-iam-policy registry-api \
  --location=europe-west2 \
  --format="table(bindings.role,bindings.members)"
```

Should include `roles/artifactregistry.writer` for `github-deployer-staging@...`.

### Helm deploy fails with "timed out waiting for the condition"

Pods didn't become ready in 5 minutes. Check from your laptop:

```bash
kubectl config use-context gke-staging
kubectl get pods -n registry-api
kubectl describe pod <failing-pod> -n registry-api
kubectl logs <failing-pod> -c registry-api -n registry-api
kubectl logs <failing-pod> -c cloud-sql-proxy -n registry-api
```

Common cause: the K8s Secret `registry-api-db-credentials` doesn't exist in the namespace. The `--create-namespace` flag creates the namespace but not the secret. If the namespace was recreated, re-run the secret creation from Phase 2 Step 6.

### deploy-production fails but deploy-staging succeeded

Check that:
1. Production infrastructure is provisioned (Phase 1 for production)
2. Production environment secrets are set in GitHub
3. The `production` cluster name matches in the workflow and in GKE

### Workflow doesn't trigger on push to main

Check that the workflow file is at exactly `.github/workflows/deploy.yaml` and the `on:` trigger is correct:

```yaml
on:
  push:
    branches: [main]
```

Also check that the file is committed to the `main` branch (not just a feature branch).

---

## 10. Checklist — Phase 3 complete

- [ ] `.github/workflows/deploy.yaml` exists and is committed to `main`
- [ ] GitHub environments `staging` and `production` are created
- [ ] `production` environment has "Required reviewers" enabled
- [ ] Repository secret `GCP_PROJECT_ID` is set
- [ ] Staging environment secrets `GCP_WORKLOAD_IDENTITY_PROVIDER` and `GCP_SERVICE_ACCOUNT` are set
- [ ] A push to `main` triggers the workflow (visible in Actions tab)
- [ ] `build-and-test` job passes (bb check + Docker push)
- [ ] `deploy-staging` job passes (Helm deploy + pods healthy)
- [ ] After staging deploy, `curl http://$STAGING_IP/health` returns success
- [ ] `deploy-production` job waits for approval (yellow banner visible)
- [ ] You can approve or reject production deploys from the GitHub UI

### Files created in this phase

```
.github/workflows/deploy.yaml
```

Plus: GitHub environments and secrets configured in the repo settings (not in code).

### What manual deploys look like now

You can still deploy manually if needed — the CI/CD pipeline doesn't remove that ability:

```bash
# Manual deploy to staging (same as Phase 2)
bb ctx-staging
helm upgrade --install registry-api helm/registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag=<tag> \
  -n registry-api --create-namespace \
  --wait --timeout 5m
```

But now you shouldn't need to — every push to main handles it.

**Next:** [Phase 4 — HashiCorp Vault](K8s-phase4.md)
