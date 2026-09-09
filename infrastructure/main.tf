module "networking" {
  source       = "./modules/networking"
  project_name = var.project_name
  environment  = var.environment
}

module "ecr" {
  source       = "./modules/ecr"
  project_name = var.project_name
}

module "iam" {
  source                = "./modules/iam"
  project_name          = var.project_name
  cognito_user_pool_arn = module.cognito.user_pool_arn
}

module "dynamodb" {
  source              = "./modules/dynamodb"
  project_name        = var.project_name
  environment         = var.environment
  enable_catalog_full = var.enable_catalog_full
}

module "opensearch" {
  source       = "./modules/opensearch"
  project_name = var.project_name
  environment  = var.environment

  enable_opensearch      = var.enable_opensearch
  enable_batch_embedding = var.enable_batch_embedding
  task_role_arn          = module.iam.task_role_arn
  task_role_name         = module.iam.task_role_name
  admin_principals       = var.opensearch_admin_principals

  max_search_ocu   = var.opensearch_max_search_ocu
  max_indexing_ocu = var.opensearch_max_indexing_ocu

  budget_limit_amount       = var.opensearch_budget_limit_amount
  budget_notification_email = var.opensearch_budget_notification_email
}

# OpenSearch basic-auth password in Secrets Manager, so the ECS task can inject it as a secret
# (never plaintext in the task def). Created only for the self-hosted cutover (auth=basic) and
# sourced from the same sensitive var cloud-init uses, so the node and the app share one password.
resource "aws_secretsmanager_secret" "opensearch_password" {
  count = var.opensearch_auth == "basic" && var.oci_opensearch_admin_password != "" ? 1 : 0
  name  = "${var.project_name}-${var.environment}-opensearch-password"
}

resource "aws_secretsmanager_secret_version" "opensearch_password" {
  count         = var.opensearch_auth == "basic" && var.oci_opensearch_admin_password != "" ? 1 : 0
  secret_id     = aws_secretsmanager_secret.opensearch_password[0].id
  secret_string = var.oci_opensearch_admin_password
}

module "oci_opensearch" {
  source       = "./modules/oci-opensearch"
  project_name = var.project_name
  environment  = var.environment

  enable                    = var.enable_oci_opensearch
  compartment_ocid          = var.oci_compartment_ocid != "" ? var.oci_compartment_ocid : var.oci_tenancy_ocid
  ocpus                     = var.oci_opensearch_ocpus
  memory_gb                 = var.oci_opensearch_memory_gb
  boot_volume_gb            = var.oci_opensearch_boot_volume_gb
  admin_cidr                = var.oci_admin_cidr
  ssh_public_key            = var.oci_ssh_public_key
  opensearch_admin_password = var.oci_opensearch_admin_password
  image_ocid                = var.oci_opensearch_image_ocid
  # Whitelist the AWS NAT gateway's stable EIP on 9200 so the live ECS app can reach the node.
  app_egress_cidr = "${module.networking.nat_gateway_public_ip}/32"
}

module "s3" {
  source       = "./modules/s3"
  project_name = var.project_name
  environment  = var.environment
}

module "cognito" {
  source                           = "./modules/cognito"
  project_name                     = var.project_name
  environment                      = var.environment
  domain_name                      = var.domain_name
  cognito_google_client_id_ssm     = var.cognito_google_client_id_ssm
  cognito_google_client_secret_ssm = var.cognito_google_client_secret_ssm
}

module "alb" {
  source             = "./modules/alb"
  project_name       = var.project_name
  environment        = var.environment
  vpc_id             = module.networking.vpc_id
  public_subnets     = module.networking.public_subnet_ids
  domain_name        = var.domain_name
  security_group_ids = [module.networking.alb_security_group_id]
}

module "ecs" {
  source = "./modules/ecs"
  # Ensure the NAT gateway + private route table + associations are fully live before ECS tasks
  # move into private subnets — new tasks pull their image (ECR) and read secrets at startup over
  # the NAT, so the egress path must exist first.
  depends_on = [module.networking]

  project_name                = var.project_name
  environment                 = var.environment
  aws_region                  = var.aws_region
  vpc_id                      = module.networking.vpc_id
  private_subnets             = module.networking.private_subnet_ids
  public_subnets              = module.networking.public_subnet_ids
  execution_role_arn          = module.iam.execution_role_arn
  task_role_arn               = module.iam.task_role_arn
  backend_ecr_url             = module.ecr.backend_repository_url
  frontend_ecr_url            = module.ecr.frontend_repository_url
  backend_tg_arn              = module.alb.backend_tg_arn
  frontend_tg_arn             = module.alb.frontend_tg_arn
  dynamodb_users_table        = module.dynamodb.users_table_name
  dynamodb_recipes_table      = module.dynamodb.recipes_table_name
  dynamodb_catalog_table      = module.dynamodb.catalog_table_name
  dynamodb_catalog_full_table = module.dynamodb.catalog_full_table_name
  dynamodb_consent_table      = module.dynamodb.consent_table_name
  dynamodb_audit_table        = module.dynamodb.audit_log_table_name

  catalog_search_backend   = var.catalog_search_backend
  catalog_search_mode      = var.catalog_search_mode
  catalog_semantic_enabled = var.catalog_semantic_enabled
  # Endpoint precedence: explicit override > self-hosted OCI node (auth=basic) > AWS collection.
  opensearch_endpoint = var.opensearch_endpoint != "" ? var.opensearch_endpoint : (
    var.opensearch_auth == "basic" ? module.oci_opensearch.endpoint : module.opensearch.collection_endpoint
  )
  opensearch_knn_ef_search    = var.opensearch_knn_ef_search
  opensearch_knn_quantization = var.opensearch_knn_quantization
  opensearch_auth             = var.opensearch_auth
  opensearch_username         = var.opensearch_username
  opensearch_password_arn = var.opensearch_password_arn != "" ? var.opensearch_password_arn : (
    length(aws_secretsmanager_secret.opensearch_password) > 0 ? aws_secretsmanager_secret.opensearch_password[0].arn : ""
  )
  opensearch_tls_verify = var.opensearch_tls_verify
  enable_monitoring     = var.enable_monitoring
  s3_bucket             = module.s3.bucket_name
  cognito_issuer_uri    = module.cognito.issuer_uri
  cognito_domain        = module.cognito.cognito_domain
  cognito_client_id     = module.cognito.client_id
  cognito_user_pool_id  = module.cognito.user_pool_id
  domain_name           = var.domain_name
  ecs_security_group_id = module.networking.ecs_security_group_id
  stability_api_key_arn = var.stability_api_key_arn
  openai_api_key_arn    = var.openai_api_key_arn
  google_api_key_arn    = var.google_api_key_arn
}

module "monitoring" {
  source       = "./modules/monitoring"
  project_name = var.project_name
  environment  = var.environment
  aws_region   = var.aws_region

  enable_monitoring  = var.enable_monitoring
  notification_email = var.monitoring_notification_email

  # Signal sources wired from the other modules.
  ecs_cluster_name         = module.ecs.cluster_name
  ecs_backend_service_name = module.ecs.backend_service_name
  alb_arn_suffix           = module.alb.alb_arn_suffix
  backend_tg_arn_suffix    = module.alb.backend_tg_arn_suffix
  nat_gateway_id           = module.networking.nat_gateway_id
  waf_web_acl_name         = module.waf.web_acl_name

  # DynamoDB tables to watch for throttling (catalog-full is "" when disabled → filtered out).
  dynamodb_table_names = [
    module.dynamodb.users_table_name,
    module.dynamodb.recipes_table_name,
    module.dynamodb.catalog_table_name,
    module.dynamodb.consent_table_name,
    module.dynamodb.audit_log_table_name,
    module.dynamodb.catalog_full_table_name,
  ]

  # App metrics + OCI-node health widgets/alarms turn on with the backend flag (Milestone B).
  enable_app_metrics = var.enable_monitoring && var.opensearch_auth == "basic"
  # Legacy AWS OpenSearch Serverless alarms/widgets only when that collection is enabled.
  enable_opensearch = var.enable_opensearch

  budget_limit_amount = var.monitoring_budget_limit_amount
}

module "waf" {
  source       = "./modules/waf"
  project_name = var.project_name
  environment  = var.environment
  alb_arn      = module.alb.alb_arn

  allowed_ips         = var.waf_allowed_ips
  allowed_ips_v6      = var.waf_allowed_ips_v6
  blocked_ips         = var.waf_blocked_ips
  blocked_ips_v6      = var.waf_blocked_ips_v6
  geo_block_countries = var.waf_geo_block_countries

  rate_limit_global       = var.waf_rate_limit_global
  rate_limit_recipe_gen   = var.waf_rate_limit_recipe_gen
  rate_limit_image_upload = var.waf_rate_limit_image_upload
  rate_limit_auth         = var.waf_rate_limit_auth

  waf_log_bucket_name = "aws-waf-logs-${var.project_name}-${var.environment}"
  # Give the existing WAF alarm a live destination: use the monitoring SNS topic when monitoring
  # is enabled, otherwise fall back to the (currently empty) explicit var.
  alarm_sns_topic_arn              = var.enable_monitoring ? module.monitoring.sns_topic_arn : var.waf_alarm_sns_topic_arn
  blocked_requests_alarm_threshold = var.waf_blocked_requests_alarm_threshold
  budget_limit_amount              = var.waf_budget_limit_amount
  budget_notification_email        = var.waf_budget_notification_email
}
