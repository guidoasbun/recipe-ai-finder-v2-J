# Architecture

Current deployment architecture for the Recipe AI Finder, including the self-hosted OpenSearch node
on Oracle Cloud and the NAT-gateway / private-subnet networking. This diagram is **Mermaid**, which
GitHub renders natively — edit the text below and the picture updates, no image regeneration needed.

## System diagram

```mermaid
flowchart TB
    user([User / Browser])
    dns[Route 53 DNS]

    subgraph aws["AWS — us-east-1"]
        acm[ACM TLS cert]
        waf[WAF Web ACL]

        subgraph vpc["VPC 10.0.0.0/16"]
            direction TB
            subgraph public["Public subnets (2 AZs)"]
                alb[Application Load Balancer<br/>HTTPS + path routing]
                nat[NAT Gateway<br/>stable Elastic IP]
            end
            subgraph private["Private subnets (2 AZs) — no public IPs"]
                fe[ECS Fargate<br/>Frontend Next.js]
                be[ECS Fargate<br/>Backend Spring Boot]
            end
        end

        subgraph awssvc["AWS services"]
            ddb[(DynamoDB<br/>users, recipes, catalog-full 2.2M<br/>+ embeddings = source of truth)]
            s3[(S3<br/>recipe images)]
            bedrock[Bedrock<br/>Claude / Nova / Llama<br/>+ Titan embeddings]
            cognito[Cognito<br/>Google OAuth2 / JWT]
            secrets[Secrets Manager<br/>API keys + OpenSearch pw]
            ecr[(ECR<br/>container images)]
        end
    end

    subgraph oci["Oracle Cloud — us-sanjose-1"]
        subgraph ocivcn["OCI VCN — security list: 9200 from NAT EIP only"]
            os[OpenSearch 2.17 on Ampere A1<br/>2 OCPU / 24 GB, fp16<br/>2.23M vector index]
        end
    end

    imggen[Stability AI / OpenAI / Google Imagen]

    %% ingress
    user -->|HTTPS| dns --> waf --> alb
    acm -.-> alb
    alb -->|/*| fe
    alb -->|/api/*| be

    %% backend egress all flows through the NAT
    be --> nat
    fe --> nat
    nat --> bedrock
    nat --> ddb
    nat --> s3
    nat --> cognito
    nat --> secrets
    nat -->|HTTPS basic auth<br/>k-NN + keyword search| os
    nat --> imggen
    fe -. pull image .-> ecr
    be -. pull image .-> ecr

    %% search index is derived from DynamoDB (rebuilt, no re-embed)
    ddb -. reindex: read embeddings<br/>no re-embedding .-> os

    classDef edge fill:#f4f4f4,stroke:#888;
    classDef awsbox fill:#eef6ff,stroke:#3b82f6;
    classDef ocibox fill:#fff3e0,stroke:#f59e0b;
    class user,dns,imggen edge;
    class alb,nat,fe,be,ddb,s3,bedrock,cognito,secrets,ecr,acm,waf awsbox;
    class os ocibox;
```

## How to read it

- **Only the ALB is internet-facing.** User traffic enters via Route 53 → WAF → ALB (in the public
  subnets). The ALB path-routes: `/*` to the frontend, `/api/*` to the backend.
- **ECS tasks run in private subnets with no public IPs.** All of their outbound traffic — AWS
  services (Bedrock, DynamoDB, S3, Cognito, Secrets Manager), image-generation APIs, and the
  self-hosted OpenSearch node — egresses through the **NAT gateway's single stable Elastic IP**.
- **The OpenSearch node lives on Oracle Cloud**, reached over HTTPS basic auth. Its firewall
  (OCI security list) only allows port 9200 from the NAT gateway's Elastic IP (plus the operator's
  admin IP), so the search port is never open to the public internet.
- **DynamoDB is the source of truth.** The OpenSearch vector index is derived from it and rebuilt by
  reading the persisted embeddings — no re-embedding — which is what made the AWS→Oracle migration
  cheap and reversible.

## Why this shape (cost + security)

- **Cost:** catalog search moved off Amazon OpenSearch Serverless (a large, always-resident vector
  index that held ~6 OCUs / ~36 GB RAM warm 24/7, an estimated several hundred to ~$1,000+/month) to
  a single fixed-price Oracle Ampere A1 VM (~$0–13/month). Full reasoning:
  [MIGRATION-aws-to-oracle-opensearch.md](MIGRATION-aws-to-oracle-opensearch.md).
- **Security:** moving ECS into private subnets behind a NAT gateway means the app containers have
  no public IPs (ALB-only exposure) and reach the internet from one known Elastic IP — which is also
  what locks down access to the Oracle search port.
