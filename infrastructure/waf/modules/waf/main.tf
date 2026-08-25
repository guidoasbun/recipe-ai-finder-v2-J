# WAF Web ACL and ALB Association
#
# This file will contain:
# - aws_wafv2_web_acl resource with all rule statements (IP sets, rate-based,
#   geo-match, size constraint, managed rule groups) ordered by priority
# - aws_wafv2_web_acl_association linking the Web ACL to the ALB
# - Custom response bodies for rate-limit and block responses
