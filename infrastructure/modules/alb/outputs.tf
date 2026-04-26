output "alb_dns" {
  value = aws_lb.main.dns_name
}

output "backend_tg_arn" {
  value = aws_lb_target_group.backend.arn
}

output "frontend_tg_arn" {
  value = aws_lb_target_group.frontend.arn
}

output "alb_security_group_id" {
  value = tolist(aws_lb.main.security_groups)[0]
}
