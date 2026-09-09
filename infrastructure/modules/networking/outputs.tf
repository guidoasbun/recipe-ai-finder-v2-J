output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "alb_security_group_id" {
  value = aws_security_group.alb.id
}

output "ecs_security_group_id" {
  value = aws_security_group.ecs.id
}

output "nat_gateway_public_ip" {
  description = "Stable Elastic IP of the NAT gateway — the source IP of all private-subnet egress. Whitelist this on the OpenSearch node's 9200 rule."
  value       = aws_eip.nat.public_ip
}

output "nat_gateway_id" {
  description = "ID of the single NAT gateway (the whole-app egress chokepoint). Used as the NatGatewayId dimension for AWS/NATGateway CloudWatch alarms in the monitoring module."
  value       = aws_nat_gateway.main.id
}
