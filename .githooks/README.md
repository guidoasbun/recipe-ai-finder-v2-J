# Git hooks

## pre-commit — secret guard

Blocks any commit whose staged changes contain a **real-looking API key or AWS access
key** (OpenAI `sk-proj-`/`sk-`, Stability, Google `AIzaSy`, AWS `AKIA`/`ASIA`). It only
matches real-length secrets, so short placeholders in comments/docs (e.g. `sk-...`,
`AIzaSy...`) and Secrets Manager ARNs are allowed.

### Enable (once per clone)

```
git config core.hooksPath .githooks
```

This repo is already configured with `core.hooksPath = .githooks`, but each fresh clone
must run the command above (git does not share this setting).

### Where secrets belong

- **Deployment:** AWS Secrets Manager (ECS injects them at runtime by ARN).
- **Local dev:** `backend/src/main/resources/application-local.properties` (gitignored).
- **Terraform (`*.tfvars`):** store only Secrets Manager **ARNs**, never raw keys.

### Bypass (only if you are certain it is a false positive)

```
git commit --no-verify
```
