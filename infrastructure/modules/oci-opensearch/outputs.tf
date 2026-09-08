output "public_ip" {
  description = "Public IP of the OpenSearch VM (empty when disabled)."
  value       = local.enabled ? oci_core_instance.this[0].public_ip : ""
}

output "endpoint" {
  description = "HTTPS endpoint for OPENSEARCH_ENDPOINT (empty when disabled). Security plugin serves 9200 over TLS."
  value       = local.enabled ? "https://${oci_core_instance.this[0].public_ip}:9200" : ""
}

output "ssh_command" {
  description = "Convenience SSH command (Oracle Linux default user is 'opc')."
  value       = local.enabled ? "ssh opc@${oci_core_instance.this[0].public_ip}" : ""
}

output "generated_ssh_private_key_pem" {
  description = "PEM of the generated SSH private key (only when the module generated one; empty if you supplied a public key). SENSITIVE — write it to a file with chmod 600."
  value       = local.enabled && var.ssh_public_key == "" ? tls_private_key.ssh[0].private_key_pem : ""
  sensitive   = true
}

output "instance_id" {
  description = "OCID of the compute instance (empty when disabled)."
  value       = local.enabled ? oci_core_instance.this[0].id : ""
}

output "effective_heap_gb" {
  description = "OpenSearch JVM heap actually configured (GB)."
  value       = local.enabled ? local.heap_gb : 0
}
