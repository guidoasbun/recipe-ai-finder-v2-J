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
  source       = "./modules/iam"
  project_name = var.project_name
}

module "dynamodb" {
  source       = "./modules/dynamodb"
  project_name = var.project_name
  environment  = var.environment
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
  source                 = "./modules/ecs"
  project_name           = var.project_name
  environment            = var.environment
  aws_region             = var.aws_region
  vpc_id                 = module.networking.vpc_id
  private_subnets        = module.networking.private_subnet_ids
  public_subnets         = module.networking.public_subnet_ids
  execution_role_arn     = module.iam.execution_role_arn
  task_role_arn          = module.iam.task_role_arn
  backend_ecr_url        = module.ecr.backend_repository_url
  frontend_ecr_url       = module.ecr.frontend_repository_url
  backend_tg_arn         = module.alb.backend_tg_arn
  frontend_tg_arn        = module.alb.frontend_tg_arn
  dynamodb_users_table   = module.dynamodb.users_table_name
  dynamodb_recipes_table = module.dynamodb.recipes_table_name
  s3_bucket              = module.s3.bucket_name
  cognito_issuer_uri     = module.cognito.issuer_uri
  cognito_domain         = module.cognito.cognito_domain
  cognito_client_id      = module.cognito.client_id
  domain_name            = var.domain_name
  ecs_security_group_id  = module.networking.ecs_security_group_id
}

